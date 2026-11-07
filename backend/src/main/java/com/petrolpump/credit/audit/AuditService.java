package com.petrolpump.credit.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petrolpump.credit.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Writes to the append-only audit_logs table. Every financial action must go through here. */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Audit an action by the signed-in user; IP and user agent come from the current request. */
    public void log(CurrentUser actor, String action, String entityType, Object entityId,
                    Object oldValue, Object newValue, String reason) {
        HttpServletRequest req = currentRequest();
        record(new AuditEvent(actor.id(), actor.role(), action, entityType,
                entityId == null ? null : entityId.toString(), oldValue, newValue, reason,
                req == null ? null : req.getRemoteAddr(), req == null ? null : req.getHeader("User-Agent")));
    }

    public void record(AuditEvent e) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_user_id, actor_role, action, entity_type, entity_id,
                                        old_value, new_value, reason, ip_address, user_agent)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """,
                e.actorUserId(), e.actorRole(), e.action(), e.entityType(), e.entityId(),
                toJson(e.oldValue()), toJson(e.newValue()), e.reason(), e.ipAddress(), truncate(e.userAgent(), 300));
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise audit value", ex);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public record AuditEvent(Long actorUserId, String actorRole, String action, String entityType, String entityId,
                             Object oldValue, Object newValue, String reason, String ipAddress, String userAgent) {}
}
