package com.petrolpump.credit.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notifications rendered from notification_settings templates: an in-app row per user, plus SMS / WhatsApp
 * outbox rows (notification_deliveries) for the channels the admin has switched on for that event.
 */
@Service
public class NotificationService {

    private static final Pattern VAR = Pattern.compile("\\{(\\w+)}");

    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every active login linked to the customer account. */
    public void toCustomer(long customerId, String event, Map<String, ?> vars, String entityType, Long entityId) {
        send(jdbc.queryForList("SELECT id FROM users WHERE customer_id = ? AND is_active", Long.class, customerId),
                event, vars, entityType, entityId);
    }

    /** Every active staff user who effectively holds the permission (role grants and user overrides). */
    public void toPermission(String permission, String event, Map<String, ?> vars, String entityType, Long entityId) {
        List<Long> ids = jdbc.queryForList("""
                SELECT u.id FROM users u
                 WHERE u.is_active AND u.customer_id IS NULL
                   AND (EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                                 WHERE rp.role_id = u.role_id AND p.code = ?)
                        OR EXISTS (SELECT 1 FROM user_permission_overrides o JOIN permissions p ON p.id = o.permission_id
                                    WHERE o.user_id = u.id AND p.code = ? AND o.granted))
                   AND NOT EXISTS (SELECT 1 FROM user_permission_overrides o JOIN permissions p ON p.id = o.permission_id
                                    WHERE o.user_id = u.id AND p.code = ? AND NOT o.granted)
                """, Long.class, permission, permission, permission);
        send(ids, event, vars, entityType, entityId);
    }

    public void toUser(long userId, String event, Map<String, ?> vars, String entityType, Long entityId) {
        send(jdbc.queryForList("SELECT id FROM users WHERE id = ? AND is_active", Long.class, userId),
                event, vars, entityType, entityId);
    }

    public void toRole(String roleCode, String event, Map<String, ?> vars, String entityType, Long entityId) {
        send(jdbc.queryForList("""
                SELECT u.id FROM users u JOIN roles r ON r.id = u.role_id WHERE r.code = ? AND u.is_active
                """, Long.class, roleCode), event, vars, entityType, entityId);
    }

    private void send(List<Long> userIds, String event, Map<String, ?> vars, String entityType, Long entityId) {
        if (userIds.isEmpty()) return;
        Map<String, Object> tpl = jdbc.queryForMap("""
                SELECT in_app_enabled, sms_enabled, whatsapp_enabled, title_template, body_template
                  FROM notification_settings WHERE event_type = ?
                """, event);
        boolean inApp = Boolean.TRUE.equals(tpl.get("in_app_enabled"));
        boolean sms = Boolean.TRUE.equals(tpl.get("sms_enabled"));
        boolean whatsapp = Boolean.TRUE.equals(tpl.get("whatsapp_enabled"));
        if (!inApp && !sms && !whatsapp) return;
        String title = render((String) tpl.get("title_template"), vars);
        String body = render((String) tpl.get("body_template"), vars);
        for (Long userId : userIds) {
            // The row is also the source text for SMS/WhatsApp; with in-app off it is stored already read.
            Long id = jdbc.queryForObject("""
                    INSERT INTO notifications (user_id, event_type, title, message, entity_type, entity_id, is_read, read_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END) RETURNING id
                    """, Long.class, userId, event, title, body, entityType, entityId, !inApp, !inApp);
            if (sms || whatsapp) queueDeliveries(id, userId, sms, whatsapp);
        }
    }

    /** Outbox rows for {@link DeliveryWorker}. A customer login without its own phone uses the account's phone. */
    private void queueDeliveries(long notificationId, long userId, boolean sms, boolean whatsapp) {
        String phone = jdbc.query("""
                SELECT COALESCE(NULLIF(u.phone, ''), c.phone) FROM users u LEFT JOIN customers c ON c.id = u.customer_id
                 WHERE u.id = ?
                """, (rs, i) -> rs.getString(1), userId).stream().findFirst().orElse(null);
        if (phone == null || phone.isBlank()) return;
        String to = phone.startsWith("+") ? phone : "+91" + phone;
        if (sms) jdbc.update("INSERT INTO notification_deliveries (notification_id, channel, destination) VALUES (?, 'SMS', ?)",
                notificationId, to);
        if (whatsapp) jdbc.update("INSERT INTO notification_deliveries (notification_id, channel, destination) VALUES (?, 'WHATSAPP', ?)",
                notificationId, to);
    }

    static String render(String template, Map<String, ?> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
