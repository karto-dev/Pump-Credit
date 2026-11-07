package com.petrolpump.credit.admin;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.user.Role;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Role -> permission matrix. The ADMIN role always holds every permission and is not editable. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('PERMISSION_MANAGE')")
public class PermissionAdminController {

    public record RoleDto(String code, String name, String description) {}

    public record PermissionDto(String code, String module, String description) {}

    public record Matrix(List<RoleDto> roles, List<PermissionDto> permissions, Map<String, Set<String>> grants) {}

    public record RolePermissionsRequest(@NotNull Set<String> permissions, @NotBlank @Size(max = 500) String reason) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PermissionAdminController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/permission-matrix")
    public Matrix matrix() {
        List<RoleDto> roles = jdbc.query("SELECT code, name, description FROM roles ORDER BY id",
                new DataClassRowMapper<>(RoleDto.class));
        List<PermissionDto> perms = jdbc.query("SELECT code, module, description FROM permissions ORDER BY module, code",
                new DataClassRowMapper<>(PermissionDto.class));
        Map<String, Set<String>> grants = new LinkedHashMap<>();
        roles.forEach(r -> grants.put(r.code(), grantsFor(r.code())));
        return new Matrix(roles, perms, grants);
    }

    @PutMapping("/roles/{roleCode}/permissions")
    @Transactional
    public Matrix setRolePermissions(@AuthenticationPrincipal CurrentUser actor, @PathVariable String roleCode,
                                     @Valid @RequestBody RolePermissionsRequest req) {
        if (Role.ADMIN.equals(roleCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN permissions are fixed and cannot be changed");
        }
        Long roleId = jdbc.query("SELECT id FROM roles WHERE code = ?", (rs, i) -> rs.getLong(1), roleCode)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found"));
        // Exceptions to credit limits are admin-only by policy; granting the permission to others would have no effect.
        if (req.permissions().contains("CREDIT_LIMIT_EXCEPTION")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only the ADMIN role can authorise credit-limit exceptions");
        }
        Set<String> before = grantsFor(roleCode);
        jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
        for (String code : req.permissions()) {
            int rows = jdbc.update("""
                    INSERT INTO role_permissions (role_id, permission_id, granted_by)
                    SELECT ?, id, ? FROM permissions WHERE code = ?
                    """, roleId, actor.id(), code);
            if (rows == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown permission " + code);
        }
        Set<String> after = grantsFor(roleCode);
        if (!before.equals(after)) {
            audit.log(actor, "ROLE_PERMISSIONS_CHANGED", "ROLE", roleCode,
                    Map.of("permissions", before), Map.of("permissions", after), req.reason());
        }
        return matrix();
    }

    private Set<String> grantsFor(String roleCode) {
        return new TreeSet<>(jdbc.queryForList("""
                SELECT p.code FROM role_permissions rp
                  JOIN roles r ON r.id = rp.role_id JOIN permissions p ON p.id = rp.permission_id
                 WHERE r.code = ?
                """, String.class, roleCode));
    }
}
