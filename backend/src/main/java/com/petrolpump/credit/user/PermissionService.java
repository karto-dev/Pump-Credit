package com.petrolpump.credit.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Effective permissions = role permissions + per-user grants - per-user revokes.
 * Nothing is hardcoded here: admins change role_permissions / user_permission_overrides.
 */
@Service
public class PermissionService {

    private static final String EFFECTIVE_PERMISSIONS_SQL = """
            (SELECT p.code FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
              WHERE rp.role_id = ?
             UNION
             SELECT p.code FROM user_permission_overrides o JOIN permissions p ON p.id = o.permission_id
              WHERE o.user_id = ? AND o.granted AND (o.valid_until IS NULL OR o.valid_until > now()))
            EXCEPT
            SELECT p.code FROM user_permission_overrides o JOIN permissions p ON p.id = o.permission_id
             WHERE o.user_id = ? AND NOT o.granted AND (o.valid_until IS NULL OR o.valid_until > now())
            ORDER BY 1
            """;

    private final JdbcTemplate jdbc;

    public PermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> effectivePermissions(User user) {
        return new LinkedHashSet<>(jdbc.queryForList(EFFECTIVE_PERMISSIONS_SQL, String.class,
                user.getRole().getId(), user.getId(), user.getId()));
    }
}
