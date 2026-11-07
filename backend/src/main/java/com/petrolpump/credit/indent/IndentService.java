package com.petrolpump.credit.indent;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.credit.CreditService;
import com.petrolpump.credit.credit.CreditService.CreditPosition;
import com.petrolpump.credit.notification.NotificationService;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class IndentService {

    public record IndentRow(long id, String indentNo, long customerId, String customerName, String customerCode,
                            long vehicleId, String vehicleNumber, String fuelType, BigDecimal requestedQuantity,
                            BigDecimal requestedAmount, boolean fullTank, String instructions, String status,
                            String holdReason, Long creditExceptionId, String createdByName, OffsetDateTime createdAt,
                            String acceptedByName, OffsetDateTime acceptedAt, OffsetDateTime completedAt,
                            String cancelReason, Long creditTransactionId, String txnNo, BigDecimal suppliedQuantity,
                            BigDecimal suppliedAmount) {}

    public record CreateIndent(Long customerId, long vehicleId, String fuelType, BigDecimal requestedQuantity,
                               BigDecimal requestedAmount, boolean fullTank, String instructions) {}

    public record Filter(String status, Long customerId, String q, LocalDate from, LocalDate to) {}

    private static final String SELECT = """
            SELECT i.id, i.indent_no, i.customer_id, c.name AS customer_name, c.customer_code, i.vehicle_id,
                   v.vehicle_number, i.fuel_type, i.requested_quantity, i.requested_amount, i.full_tank,
                   i.instructions, i.status, i.hold_reason, i.credit_exception_id,
                   cu.full_name AS created_by_name, i.created_at, au.full_name AS accepted_by_name, i.accepted_at,
                   i.completed_at, i.cancel_reason, t.id AS credit_transaction_id, t.txn_no,
                   t.quantity AS supplied_quantity, t.amount AS supplied_amount
              FROM indents i
              JOIN customers c ON c.id = i.customer_id
              JOIN vehicles v ON v.id = i.vehicle_id
              JOIN users cu ON cu.id = i.created_by
              LEFT JOIN users au ON au.id = i.accepted_by
              LEFT JOIN credit_transactions t ON t.indent_id = i.id
            """;
    private static final DataClassRowMapper<IndentRow> MAPPER = new DataClassRowMapper<>(IndentRow.class);

    private final JdbcTemplate jdbc;
    private final CreditService credit;
    private final NotificationService notify;
    private final AuditService audit;

    public IndentService(JdbcTemplate jdbc, CreditService credit, NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.credit = credit;
        this.notify = notify;
        this.audit = audit;
    }

    public List<IndentRow> list(Filter f) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (f.status() != null && !f.status().isBlank()) {
            sql.append(" AND i.status = ANY (string_to_array(?, ','))");
            args.add(f.status());
        }
        if (f.customerId() != null) { sql.append(" AND i.customer_id = ?"); args.add(f.customerId()); }
        if (f.from() != null) { sql.append(" AND i.created_at >= ?::date"); args.add(f.from().toString()); }
        if (f.to() != null) { sql.append(" AND i.created_at < ?::date + 1"); args.add(f.to().toString()); }
        if (f.q() != null && !f.q().isBlank()) {
            String like = "%" + f.q().trim().toLowerCase() + "%";
            sql.append(" AND (lower(c.name) LIKE ? OR lower(i.indent_no) LIKE ? OR v.vehicle_number LIKE ?)");
            args.addAll(List.of(like, like, "%" + f.q().replaceAll("[\\s-]", "").toUpperCase() + "%"));
        }
        sql.append(" ORDER BY i.created_at DESC LIMIT 300");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public IndentRow get(long id) {
        return jdbc.query(SELECT + " WHERE i.id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Indent not found"));
    }

    @Transactional
    public IndentRow create(CreateIndent req, CurrentUser actor) {
        long customerId = resolveCustomer(req.customerId(), actor);
        if (!req.fullTank() && req.requestedQuantity() == null && req.requestedAmount() == null) {
            throw bad("Enter an estimated quantity or amount, or choose full tank");
        }
        String vehicleNo = requireVehicle(customerId, req.vehicleId());
        // Lock the customer so two indents raised together cannot both slip under the limit.
        jdbc.queryForObject("SELECT id FROM customers WHERE id = ? FOR UPDATE", Long.class, customerId);
        CreditPosition pos = credit.position(customerId);
        if (!"ACTIVE".equals(pos.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This credit account is " + pos.status().toLowerCase()
                    + ". New indents are not allowed.");
        }

        BigDecimal estimate = estimate(req.fuelType(), req.requestedQuantity(), req.requestedAmount());
        BigDecimal headroomAfter = estimate == null ? pos.headroom() : pos.headroom().subtract(estimate);
        boolean overLimit = estimate == null ? headroomAfter.signum() <= 0 : headroomAfter.signum() < 0;
        String holdReason = null;
        if (overLimit) {
            holdReason = "Outstanding %s + open indents %s%s exceeds limit %s".formatted(
                    inr(pos.outstanding()), inr(pos.openIndents()),
                    estimate == null ? " (full tank)" : " + this indent " + inr(estimate), inr(pos.totalLimit()));
            if ("BLOCK".equalsIgnoreCase(credit.setting("OVER_LIMIT_INDENT_ACTION", "HOLD"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Credit limit exceeded. " + holdReason);
            }
        }
        // Within the limit but sitting on old dues: the OVERDUE_INDENT_ACTION setting decides.
        boolean overdueHold = false;
        String overdueAction = credit.setting("OVERDUE_INDENT_ACTION", "ALLOW").toUpperCase();
        if (!overLimit && !"ALLOW".equals(overdueAction)) {
            BigDecimal overdue = credit.overdue(customerId);
            if (overdue.signum() > 0) {
                holdReason = "Dues of %s are past the credit period. Please clear them or contact the pump."
                        .formatted(inr(overdue));
                if ("BLOCK".equals(overdueAction)) throw new ResponseStatusException(HttpStatus.CONFLICT, holdReason);
                overdueHold = true;
            }
        }
        boolean held = overLimit || overdueHold;

        Long id = jdbc.queryForObject("""
                INSERT INTO indents (customer_id, vehicle_id, fuel_type, requested_quantity, requested_amount, full_tank,
                                     instructions, status, hold_reason, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, customerId, req.vehicleId(), req.fuelType(), req.requestedQuantity(),
                req.requestedAmount(), req.fullTank(), blankToNull(req.instructions()),
                held ? "ON_HOLD_CREDIT_LIMIT" : "PENDING", holdReason, actor.id(), actor.id());
        IndentRow row = get(id);
        audit.log(actor, overLimit ? "INDENT_HELD_CREDIT_LIMIT" : overdueHold ? "INDENT_HELD_OVERDUE" : "INDENT_CREATED",
                "INDENT", id, null, row, holdReason);

        Map<String, Object> vars = vars(row);
        if (held) {
            vars.put("reason", holdReason);
            notify.toCustomer(customerId, "INDENT_ON_HOLD", vars, "INDENT", id);
            notify.toRole(Role.ADMIN, overLimit ? "CREDIT_LIMIT_EXCEEDED" : "INDENT_HELD_OVERDUE", vars, "INDENT", id);
        } else {
            notify.toPermission("INDENT_PROCESS", "INDENT_RECEIVED", vars, "INDENT", id);
            if (pos.totalLimit().signum() > 0) warnIfApproaching(pos, estimate, row);
        }
        return row;
    }

    @Transactional
    public IndentRow accept(long id, CurrentUser actor) {
        IndentRow before = get(id);
        transition(id, "PENDING", "ACCEPTED", "accepted_by = ?, accepted_at = now()", actor.id());
        IndentRow after = get(id);
        audit.log(actor, "INDENT_ACCEPTED", "INDENT", id, Map.of("status", before.status()), Map.of("status", "ACCEPTED"), null);
        notify.toCustomer(after.customerId(), "INDENT_PROCESSED", vars(after), "INDENT", id);
        return after;
    }

    /** Staff rejection. A credit-limit hold can only be rejected by an admin. */
    @Transactional
    public IndentRow reject(long id, String reason, CurrentUser actor) {
        IndentRow before = get(id);
        if ("ON_HOLD_CREDIT_LIMIT".equals(before.status()) && !Role.ADMIN.equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an admin can decide on a credit-limit hold");
        }
        if (!List.of("PENDING", "ACCEPTED", "ON_HOLD_CREDIT_LIMIT").contains(before.status())) {
            throw conflict("Only an open indent can be rejected");
        }
        transition(id, before.status(), "REJECTED", "cancelled_by = ?, cancelled_at = now(), cancel_reason = ?",
                actor.id(), reason.trim());
        audit.log(actor, "INDENT_REJECTED", "INDENT", id, Map.of("status", before.status()), Map.of("status", "REJECTED"), reason);
        Map<String, Object> vars = vars(before);
        vars.put("action", "rejected");
        vars.put("reason", reason);
        notify.toCustomer(before.customerId(), "INDENT_REJECTED", vars, "INDENT", id);
        return get(id);
    }

    /** Customer withdraws their own indent before fuel is supplied. */
    @Transactional
    public IndentRow cancel(long id, String reason, CurrentUser actor) {
        IndentRow before = get(id);
        if (!java.util.Objects.equals(before.customerId(), actor.customerId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Indent not found");
        if (!List.of("PENDING", "ON_HOLD_CREDIT_LIMIT").contains(before.status())) {
            throw conflict("This indent is already being processed and can no longer be cancelled");
        }
        transition(id, before.status(), "CANCELLED", "cancelled_by = ?, cancelled_at = now(), cancel_reason = ?",
                actor.id(), reason.trim());
        audit.log(actor, "INDENT_CANCELLED", "INDENT", id, Map.of("status", before.status()), Map.of("status", "CANCELLED"), reason);
        Map<String, Object> vars = vars(before);
        vars.put("action", "cancelled by customer");
        vars.put("reason", reason);
        notify.toPermission("INDENT_PROCESS", "INDENT_REJECTED", vars, "INDENT", id);
        return get(id);
    }

    /**
     * Admin releases a credit-limit hold. With extraLimit a temporary exception is granted and linked;
     * without it the hold is released only if the customer is now within limit (e.g. limit raised or paid).
     */
    @Transactional
    public IndentRow authorise(long id, BigDecimal extraLimit, OffsetDateTime validUntil, String reason, CurrentUser actor) {
        IndentRow before = get(id);
        if (!"ON_HOLD_CREDIT_LIMIT".equals(before.status())) throw conflict("This indent is not on hold");
        Long exceptionId = null;
        if (extraLimit != null) {
            if (validUntil == null) throw bad("Valid-until is required for an exception");
            exceptionId = credit.grantException(before.customerId(), extraLimit, null, validUntil, reason, actor);
        }
        CreditPosition pos = credit.position(before.customerId());
        BigDecimal estimate = estimate(before.fuelType(), before.requestedQuantity(), before.requestedAmount());
        BigDecimal after = estimate == null ? pos.headroom() : pos.headroom().subtract(estimate);
        if (estimate == null ? after.signum() <= 0 : after.signum() < 0) {
            throw conflict("Still over the limit by " + inr(after.negate().max(BigDecimal.ONE))
                    + ". Authorise a larger exception or raise the credit limit.");
        }
        transition(id, "ON_HOLD_CREDIT_LIMIT", "PENDING", "credit_exception_id = ?, hold_reason = NULL", exceptionId);
        IndentRow row = get(id);
        audit.log(actor, "INDENT_HOLD_AUTHORISED", "INDENT", id, Map.of("status", before.status()),
                Map.of("status", "PENDING", "creditExceptionId", String.valueOf(exceptionId)), reason);
        notify.toCustomer(row.customerId(), "INDENT_AUTHORISED", vars(row), "INDENT", id);
        notify.toPermission("INDENT_PROCESS", "INDENT_RECEIVED", vars(row), "INDENT", id);
        return row;
    }

    /** Called by the sales flow once fuel is supplied against the indent. */
    public void markCompleted(long id, long actorId) {
        int rows = jdbc.update("""
                UPDATE indents SET status = 'COMPLETED', completed_at = now(), updated_by = ?, version = version + 1,
                       accepted_by = COALESCE(accepted_by, ?), accepted_at = COALESCE(accepted_at, now())
                 WHERE id = ? AND status IN ('PENDING','ACCEPTED')
                """, actorId, actorId, id);
        if (rows == 0) throw conflict("Indent is not open for supply");
    }

    public BigDecimal estimate(String fuelType, BigDecimal qty, BigDecimal amount) {
        if (amount != null) return amount;
        if (qty == null) return null;
        BigDecimal rate = jdbc.query("""
                SELECT rate_per_litre FROM fuel_rates WHERE fuel_type = ? AND effective_from <= now()
                 ORDER BY effective_from DESC LIMIT 1
                """, (rs, i) -> rs.getBigDecimal(1), fuelType).stream().findFirst().orElse(null);
        return rate == null ? null : qty.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private void warnIfApproaching(CreditPosition pos, BigDecimal estimate, IndentRow row) {
        int warnAt = Integer.parseInt(credit.setting("CREDIT_LIMIT_WARN_PERCENT", "80"));
        BigDecimal projected = pos.outstanding().add(pos.openIndents()).add(estimate == null ? BigDecimal.ZERO : estimate);
        int pct = projected.multiply(BigDecimal.valueOf(100)).divide(pos.totalLimit(), 0, RoundingMode.HALF_UP).intValue();
        if (pct >= warnAt) {
            notify.toCustomer(row.customerId(), "CREDIT_LIMIT_APPROACHING",
                    Map.of("percent", pct, "available", inr(pos.totalLimit().subtract(projected))), "CUSTOMER", row.customerId());
        }
    }

    private void transition(long id, String from, String to, String extraSet, Object... extraArgs) {
        List<Object> args = new ArrayList<>(List.of(extraArgs));
        args.add(id);
        args.add(from);
        int rows = jdbc.update("UPDATE indents SET status = '" + to + "', version = version + 1, " + extraSet
                + " WHERE id = ? AND status = ?", args.toArray());
        if (rows == 0) throw conflict("Indent status changed meanwhile. Reload and try again.");
    }

    private long resolveCustomer(Long requested, CurrentUser actor) {
        if (Role.CUSTOMER.equals(actor.role())) {
            if (actor.customerId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Login not linked to a customer");
            if (requested != null && !requested.equals(actor.customerId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
            }
            return actor.customerId();
        }
        if (requested == null) throw bad("Select a customer");
        return requested;
    }

    private String requireVehicle(long customerId, long vehicleId) {
        return jdbc.query("SELECT vehicle_number FROM vehicles WHERE id = ? AND customer_id = ? AND is_active",
                        (rs, i) -> rs.getString(1), vehicleId, customerId).stream().findFirst()
                .orElseThrow(() -> bad("Vehicle is not an active vehicle of this customer"));
    }

    private static Map<String, Object> vars(IndentRow r) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("indentNo", r.indentNo());
        m.put("customerName", r.customerName());
        m.put("fuelType", r.fuelType());
        m.put("vehicleNo", r.vehicleNumber());
        return m;
    }

    static String inr(BigDecimal v) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
        return f.format(v);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
