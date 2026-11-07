package com.petrolpump.credit.admin;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.common.Passwords;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/users")
public class UserAdminController {

    public record UserRow(long id, String username, String fullName, String email, String phone, String roleCode,
                          Long customerId, String customerName, boolean isActive, boolean mustChangePassword,
                          OffsetDateTime lastLoginAt, OffsetDateTime lockedUntil, OffsetDateTime createdAt) {}

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$",
                    message = "3-60 characters: letters, digits, dot, underscore, hyphen") String username,
            @NotBlank @Size(max = 120) String fullName,
            @Email @Size(max = 150) String email,
            @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10-digit Indian mobile number") String phone,
            @NotBlank @Pattern(regexp = "CUSTOMER|CASHIER|MANAGER|ADMIN") String roleCode,
            Long customerId) {}

    public record UpdateUserRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Email @Size(max = 150) String email,
            @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10-digit Indian mobile number") String phone,
            @NotBlank @Pattern(regexp = "CUSTOMER|CASHIER|MANAGER|ADMIN") String roleCode,
            Long customerId,
            @Size(max = 500) String reason) {}

    public record StatusRequest(@NotNull Boolean active, @NotBlank @Size(max = 500) String reason) {}

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    public record PermissionState(String code, String module, String description, boolean fromRole,
                                  Boolean override, boolean effective) {}

    public record OverrideItem(@NotBlank String code, Boolean granted) {}

    public record OverridesRequest(@NotNull List<@Valid OverrideItem> overrides, @NotBlank @Size(max = 500) String reason) {}

    private static final String SELECT = """
            SELECT u.id, u.username, u.full_name, u.email, u.phone, r.code AS role_code, u.customer_id,
                   c.name AS customer_name, u.is_active, u.must_change_password, u.last_login_at, u.locked_until,
                   u.created_at
              FROM users u JOIN roles r ON r.id = u.role_id LEFT JOIN customers c ON c.id = u.customer_id
            """;
    private static final DataClassRowMapper<UserRow> MAPPER = new DataClassRowMapper<>(UserRow.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public UserAdminController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AuditService audit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public List<UserRow> list(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
                              @RequestParam(required = false) Long customerId) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            sql.append(" AND (lower(u.username) LIKE ? OR lower(u.full_name) LIKE ? OR coalesce(u.phone, '') LIKE ?)");
            args.addAll(List.of(like, like, like));
        }
        if (role != null && !role.isBlank()) {
            sql.append(" AND r.code = ?");
            args.add(role);
        }
        if (customerId != null) {
            sql.append(" AND u.customer_id = ?");
            args.add(customerId);
        }
        sql.append(" ORDER BY u.is_active DESC, r.code, u.full_name");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal CurrentUser actor, @Valid @RequestBody CreateUserRequest req) {
        requireCanAssignRole(actor, req.roleCode());
        Long customerId = validateCustomerBinding(req.roleCode(), req.customerId());
        String tempPassword = Passwords.temporary();
        Long id = jdbc.queryForObject("""
                INSERT INTO users (username, full_name, email, phone, password_hash, role_id, customer_id,
                                   must_change_password, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, (SELECT id FROM roles WHERE code = ?), ?, TRUE, ?, ?) RETURNING id
                """, Long.class, req.username().trim(), req.fullName().trim(), blankToNull(req.email()),
                blankToNull(req.phone()), passwordEncoder.encode(tempPassword), req.roleCode(), customerId,
                actor.id(), actor.id());
        UserRow created = get(id);
        audit.log(actor, "USER_CREATED", "USER", id, null, created, null);
        // The temporary password is returned once and never stored or logged in plain text.
        return Map.of("user", created, "temporaryPassword", tempPassword);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public UserRow update(@AuthenticationPrincipal CurrentUser actor, @PathVariable long id,
                          @Valid @RequestBody UpdateUserRequest req) {
        UserRow before = get(id);
        requireCanTouch(actor, before);
        requireCanAssignRole(actor, req.roleCode());
        Long customerId = validateCustomerBinding(req.roleCode(), req.customerId());
        boolean roleChanged = !before.roleCode().equals(req.roleCode());
        if (roleChanged && actor.id() == id) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot change your own role");
        }
        if (roleChanged && Role.ADMIN.equals(before.roleCode())) requireAnotherActiveAdmin(id);
        if (roleChanged && (req.reason() == null || req.reason().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required when changing a role");
        }
        jdbc.update("""
                UPDATE users SET full_name = ?, email = ?, phone = ?, role_id = (SELECT id FROM roles WHERE code = ?),
                       customer_id = ?, updated_by = ?, version = version + 1
                 WHERE id = ?
                """, req.fullName().trim(), blankToNull(req.email()), blankToNull(req.phone()), req.roleCode(),
                customerId, actor.id(), id);
        // Role or customer binding changed -> force re-login with the new access.
        if (roleChanged || !Objects.equals(before.customerId(), customerId)) revokeSessions(id, "ACCESS_CHANGED");
        UserRow after = get(id);
        audit.log(actor, roleChanged ? "USER_ROLE_CHANGED" : "USER_UPDATED", "USER", id, before, after, req.reason());
        return after;
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public UserRow setActive(@AuthenticationPrincipal CurrentUser actor, @PathVariable long id,
                             @Valid @RequestBody StatusRequest req) {
        UserRow before = get(id);
        requireCanTouch(actor, before);
        if (!req.active()) {
            if (actor.id() == id) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot deactivate yourself");
            if (Role.ADMIN.equals(before.roleCode())) requireAnotherActiveAdmin(id);
        }
        jdbc.update("UPDATE users SET is_active = ?, updated_by = ?, version = version + 1 WHERE id = ?",
                req.active(), actor.id(), id);
        if (!req.active()) revokeSessions(id, "DEACTIVATED");
        audit.log(actor, req.active() ? "USER_REACTIVATED" : "USER_DEACTIVATED", "USER", id,
                Map.of("isActive", before.isActive()), Map.of("isActive", req.active()), req.reason());
        return get(id);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public Map<String, Object> resetPassword(@AuthenticationPrincipal CurrentUser actor, @PathVariable long id,
                                             @Valid @RequestBody ReasonRequest req) {
        UserRow user = get(id);
        requireCanTouch(actor, user);
        String tempPassword = Passwords.temporary();
        jdbc.update("""
                UPDATE users SET password_hash = ?, must_change_password = TRUE, failed_login_attempts = 0,
                       locked_until = NULL, updated_by = ?, version = version + 1
                 WHERE id = ?
                """, passwordEncoder.encode(tempPassword), actor.id(), id);
        revokeSessions(id, "PASSWORD_RESET");
        audit.log(actor, "USER_PASSWORD_RESET", "USER", id, null, null, req.reason());
        return Map.of("temporaryPassword", tempPassword);
    }

    @GetMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('PERMISSION_MANAGE')")
    public List<PermissionState> permissions(@PathVariable long id) {
        get(id);
        return jdbc.query("""
                SELECT p.code, p.module, p.description,
                       (rp.permission_id IS NOT NULL) AS from_role,
                       CASE WHEN o.valid_until IS NULL OR o.valid_until > now() THEN o.granted END AS override
                  FROM permissions p
                  JOIN users u ON u.id = ?
                  LEFT JOIN role_permissions rp ON rp.role_id = u.role_id AND rp.permission_id = p.id
                  LEFT JOIN user_permission_overrides o ON o.user_id = u.id AND o.permission_id = p.id
                 ORDER BY p.module, p.code
                """, (rs, i) -> {
            boolean fromRole = rs.getBoolean("from_role");
            Boolean override = (Boolean) rs.getObject("override");
            return new PermissionState(rs.getString("code"), rs.getString("module"), rs.getString("description"),
                    fromRole, override, override != null ? override : fromRole);
        }, id);
    }

    /** Replaces this user's overrides. granted=true grants, false revokes, null clears (falls back to role). */
    @PutMapping("/{id}/permission-overrides")
    @PreAuthorize("hasAuthority('PERMISSION_MANAGE')")
    @Transactional
    public List<PermissionState> setOverrides(@AuthenticationPrincipal CurrentUser actor, @PathVariable long id,
                                              @Valid @RequestBody OverridesRequest req) {
        UserRow user = get(id);
        if (actor.id() == id) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot change your own permissions");
        }
        requireCanTouch(actor, user);
        Map<String, Boolean> before = currentOverrides(id);
        jdbc.update("DELETE FROM user_permission_overrides WHERE user_id = ?", id);
        for (OverrideItem item : req.overrides()) {
            if (item.granted() == null) continue;
            int rows = jdbc.update("""
                    INSERT INTO user_permission_overrides (user_id, permission_id, granted, reason, set_by)
                    SELECT ?, p.id, ?, ?, ? FROM permissions p WHERE p.code = ?
                    """, id, item.granted(), req.reason().trim(), actor.id(), item.code());
            if (rows == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown permission " + item.code());
        }
        Map<String, Boolean> after = currentOverrides(id);
        if (!before.equals(after)) {
            audit.log(actor, "USER_PERMISSIONS_CHANGED", "USER", id, before, after, req.reason());
        }
        return permissions(id);
    }

    private Map<String, Boolean> currentOverrides(long userId) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        jdbc.query("""
                SELECT p.code, o.granted FROM user_permission_overrides o JOIN permissions p ON p.id = o.permission_id
                 WHERE o.user_id = ? ORDER BY p.code
                """, rs -> { m.put(rs.getString(1), rs.getBoolean(2)); }, userId);
        return m;
    }

    private UserRow get(long id) {
        return jdbc.query(SELECT + " WHERE u.id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /** Only an admin may create, promote to, or manage admin accounts - stops privilege escalation via USER_MANAGE. */
    private static void requireCanAssignRole(CurrentUser actor, String roleCode) {
        if (Role.ADMIN.equals(roleCode) && !Role.ADMIN.equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an admin can assign the ADMIN role");
        }
    }

    private static void requireCanTouch(CurrentUser actor, UserRow target) {
        if (Role.ADMIN.equals(target.roleCode()) && !Role.ADMIN.equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an admin can manage admin accounts");
        }
    }

    private Long validateCustomerBinding(String roleCode, Long customerId) {
        if (Role.CUSTOMER.equals(roleCode)) {
            if (customerId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A customer login must be linked to a customer account");
            }
            Integer n = jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Integer.class, customerId);
            if (n == null || n == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer not found");
            return customerId;
        }
        if (customerId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only CUSTOMER logins can be linked to a customer");
        }
        return null;
    }

    private void requireAnotherActiveAdmin(long excludingUserId) {
        Integer others = jdbc.queryForObject("""
                SELECT count(*) FROM users u JOIN roles r ON r.id = u.role_id
                 WHERE r.code = 'ADMIN' AND u.is_active AND u.id <> ?
                """, Integer.class, excludingUserId);
        if (others == null || others == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one active admin must remain");
        }
    }

    private void revokeSessions(long userId, String reason) {
        jdbc.update("UPDATE user_sessions SET revoked_at = now(), revoked_reason = ? WHERE user_id = ? AND revoked_at IS NULL",
                reason, userId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
