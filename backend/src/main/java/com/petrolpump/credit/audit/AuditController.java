package com.petrolpump.credit.audit;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Read-only view of the append-only audit log. */
@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
public class AuditController {

    public record AuditRow(long id, OffsetDateTime occurredAt, String actorName, String actorRole, String action,
                           String entityType, String entityId, String oldValue, String newValue, String reason,
                           String ipAddress) {}

    private final JdbcTemplate jdbc;

    public AuditController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<AuditRow> search(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) String entityType,
                                 @RequestParam(required = false) String entityId,
                                 @RequestParam(required = false) Long actorId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.occurred_at, u.full_name AS actor_name, a.actor_role, a.action, a.entity_type, a.entity_id,
                       a.old_value::text AS old_value, a.new_value::text AS new_value, a.reason, a.ip_address
                  FROM audit_logs a LEFT JOIN users u ON u.id = a.actor_user_id
                 WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            sql.append(" AND (lower(a.action) LIKE ? OR lower(coalesce(a.reason, '')) LIKE ? OR lower(coalesce(u.full_name, '')) LIKE ?)");
            args.addAll(List.of(like, like, like));
        }
        if (entityType != null && !entityType.isBlank()) { sql.append(" AND a.entity_type = ?"); args.add(entityType); }
        if (entityId != null && !entityId.isBlank()) { sql.append(" AND a.entity_id = ?"); args.add(entityId); }
        if (actorId != null) { sql.append(" AND a.actor_user_id = ?"); args.add(actorId); }
        if (from != null) {
            sql.append(" AND a.occurred_at >= (?::date)::timestamp AT TIME ZONE 'Asia/Kolkata'");
            args.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND a.occurred_at < (?::date + 1)::timestamp AT TIME ZONE 'Asia/Kolkata'");
            args.add(to.toString());
        }
        sql.append(" ORDER BY a.occurred_at DESC, a.id DESC LIMIT 500");
        return jdbc.query(sql.toString(), (rs, i) -> new AuditRow(rs.getLong(1), rs.getObject(2, OffsetDateTime.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11)), args.toArray());
    }
}
