package com.petrolpump.credit.credit;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.customer.CustomerAccess;
import com.petrolpump.credit.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Credit limits: changed only by holders of CREDIT_LIMIT_CHANGE (admin by default; admin may grant it to a manager).
 * Temporary exceptions: authorised by ADMIN only, regardless of permission grants.
 */
@RestController
public class CreditLimitController {

    public record LimitChangeRequest(@NotNull @DecimalMin("0.00") BigDecimal newLimit,
                                     @NotBlank @Size(max = 500) String reason) {}

    public record LimitChangeRow(long id, BigDecimal oldLimit, BigDecimal newLimit, String reason,
                                 String changedByName, OffsetDateTime changedAt) {}

    public record ExceptionRequest(@NotNull @DecimalMin("0.01") BigDecimal extraLimit,
                                   OffsetDateTime validFrom, @NotNull OffsetDateTime validUntil,
                                   @NotBlank @Size(max = 500) String reason) {}

    public record RevokeRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ExceptionRow(long id, long customerId, BigDecimal extraLimit, OffsetDateTime validFrom,
                               OffsetDateTime validUntil, String reason, String status, String authorisedByName,
                               OffsetDateTime authorisedAt, String revokedByName, OffsetDateTime revokedAt,
                               String revokeReason) {}

    private static final String EXCEPTION_SELECT = """
            SELECT e.id, e.customer_id, e.extra_limit, e.valid_from, e.valid_until, e.reason,
                   CASE WHEN e.status = 'ACTIVE' AND e.valid_until < now() THEN 'EXPIRED' ELSE e.status END AS status,
                   a.full_name AS authorised_by_name, e.authorised_at,
                   r.full_name AS revoked_by_name, e.revoked_at, e.revoke_reason
              FROM credit_limit_exceptions e
              JOIN users a ON a.id = e.authorised_by
              LEFT JOIN users r ON r.id = e.revoked_by
            """;

    private final JdbcTemplate jdbc;
    private final CustomerAccess access;
    private final AuditService audit;
    private final CreditService creditService;

    public CreditLimitController(JdbcTemplate jdbc, CustomerAccess access, AuditService audit, CreditService creditService) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.creditService = creditService;
    }

    @PutMapping("/api/customers/{customerId}/credit-limit")
    @PreAuthorize("hasAuthority('CREDIT_LIMIT_CHANGE')")
    @Transactional
    public Map<String, Object> changeLimit(@AuthenticationPrincipal CurrentUser user, @PathVariable long customerId,
                                           @Valid @RequestBody LimitChangeRequest req) {
        // Row lock so two concurrent edits cannot both read the same "old" limit.
        BigDecimal old = jdbc.query("SELECT credit_limit FROM customers WHERE id = ? FOR UPDATE",
                        (rs, i) -> rs.getBigDecimal(1), customerId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        if (old.compareTo(req.newLimit()) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New limit is the same as the current limit");
        }
        jdbc.update("INSERT INTO credit_limit_changes (customer_id, old_limit, new_limit, reason, changed_by) VALUES (?, ?, ?, ?, ?)",
                customerId, old, req.newLimit(), req.reason().trim(), user.id());
        jdbc.update("UPDATE customers SET credit_limit = ?, updated_by = ?, version = version + 1 WHERE id = ?",
                req.newLimit(), user.id(), customerId);
        audit.log(user, "CREDIT_LIMIT_CHANGED", "CUSTOMER", customerId,
                Map.of("creditLimit", old), Map.of("creditLimit", req.newLimit()), req.reason());
        return Map.of("customerId", customerId, "oldLimit", old, "newLimit", req.newLimit());
    }

    @GetMapping("/api/customers/{customerId}/credit-limit-history")
    public List<LimitChangeRow> history(@AuthenticationPrincipal CurrentUser user, @PathVariable long customerId) {
        access.requireCanView(user, customerId);
        return jdbc.query("""
                SELECT c.id, c.old_limit, c.new_limit, c.reason, u.full_name AS changed_by_name, c.changed_at
                  FROM credit_limit_changes c JOIN users u ON u.id = c.changed_by
                 WHERE c.customer_id = ? ORDER BY c.changed_at DESC
                """, new DataClassRowMapper<>(LimitChangeRow.class), customerId);
    }

    @GetMapping("/api/customers/{customerId}/credit-exceptions")
    public List<ExceptionRow> exceptions(@AuthenticationPrincipal CurrentUser user, @PathVariable long customerId) {
        access.requireCanView(user, customerId);
        return jdbc.query(EXCEPTION_SELECT + " WHERE e.customer_id = ? ORDER BY e.authorised_at DESC",
                new DataClassRowMapper<>(ExceptionRow.class), customerId);
    }

    @PostMapping("/api/customers/{customerId}/credit-exceptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CREDIT_LIMIT_EXCEPTION')")
    @Transactional
    public ExceptionRow grantException(@AuthenticationPrincipal CurrentUser user, @PathVariable long customerId,
                                       @Valid @RequestBody ExceptionRequest req) {
        long id = creditService.grantException(customerId, req.extraLimit(), req.validFrom(), req.validUntil(),
                req.reason(), user);
        return getException(id);
    }

    @PostMapping("/api/credit-exceptions/{id}/revoke")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CREDIT_LIMIT_EXCEPTION')")
    @Transactional
    public ExceptionRow revoke(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                               @Valid @RequestBody RevokeRequest req) {
        ExceptionRow before = getException(id);
        if (!"ACTIVE".equals(before.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an active exception can be revoked");
        }
        jdbc.update("""
                UPDATE credit_limit_exceptions SET status = 'REVOKED', revoked_by = ?, revoked_at = now(), revoke_reason = ?
                 WHERE id = ?
                """, user.id(), req.reason().trim(), id);
        ExceptionRow after = getException(id);
        audit.log(user, "CREDIT_EXCEPTION_REVOKED", "CUSTOMER", before.customerId(),
                Map.of("exceptionId", id, "status", before.status()),
                Map.of("exceptionId", id, "status", after.status()), req.reason());
        return after;
    }

    private ExceptionRow getException(long id) {
        return jdbc.query(EXCEPTION_SELECT + " WHERE e.id = ?", new DataClassRowMapper<>(ExceptionRow.class), id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exception not found"));
    }
}
