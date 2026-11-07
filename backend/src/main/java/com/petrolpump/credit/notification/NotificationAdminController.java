package com.petrolpump.credit.notification;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Admin: which events go to which channel, the message templates, and the SMS / WhatsApp outbox. */
@RestController
@RequestMapping("/api/notification-settings")
@PreAuthorize("hasAuthority('NOTIFICATION_CONFIG')")
public class NotificationAdminController {

    public record EventSetting(String eventType, String description, boolean inAppEnabled, boolean smsEnabled,
                               boolean whatsappEnabled, String titleTemplate, String bodyTemplate,
                               String updatedByName, OffsetDateTime updatedAt) {}

    public record UpdateEvent(boolean inAppEnabled, boolean smsEnabled, boolean whatsappEnabled,
                              @NotBlank @Size(max = 200) String titleTemplate, @NotBlank @Size(max = 1000) String bodyTemplate) {}

    public record DeliveryRow(long id, String channel, String destination, String status, int attempts, String lastError,
                              String providerRef, OffsetDateTime createdAt, OffsetDateTime sentAt, String eventType,
                              String title, String recipientName) {}

    private static final String SELECT = """
            SELECT s.event_type, s.description, s.in_app_enabled, s.sms_enabled, s.whatsapp_enabled, s.title_template,
                   s.body_template, u.full_name AS updated_by_name, s.updated_at
              FROM notification_settings s LEFT JOIN users u ON u.id = s.updated_by
            """;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MessageSender sender;

    public NotificationAdminController(JdbcTemplate jdbc, AuditService audit, MessageSender sender) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.sender = sender;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("provider", sender.provider(),
                "events", jdbc.query(SELECT + " ORDER BY s.event_type", new DataClassRowMapper<>(EventSetting.class)));
    }

    @PutMapping("/{event}")
    @Transactional
    public EventSetting update(@AuthenticationPrincipal CurrentUser user, @PathVariable String event,
                               @Valid @RequestBody UpdateEvent req) {
        EventSetting before = jdbc.query(SELECT + " WHERE s.event_type = ?", new DataClassRowMapper<>(EventSetting.class), event)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown event " + event));
        jdbc.update("""
                UPDATE notification_settings SET in_app_enabled = ?, sms_enabled = ?, whatsapp_enabled = ?,
                                                 title_template = ?, body_template = ?, updated_by = ?, updated_at = now()
                 WHERE event_type = ?
                """, req.inAppEnabled(), req.smsEnabled(), req.whatsappEnabled(), req.titleTemplate().trim(),
                req.bodyTemplate().trim(), user.id(), event);
        EventSetting after = jdbc.queryForObject(SELECT + " WHERE s.event_type = ?", new DataClassRowMapper<>(EventSetting.class), event);
        audit.log(user, "NOTIFICATION_SETTING_CHANGED", "NOTIFICATION_SETTING", event, before, after, null);
        return after;
    }

    @GetMapping("/deliveries")
    public List<DeliveryRow> deliveries(@RequestParam(required = false) String status) {
        return jdbc.query("""
                SELECT d.id, d.channel, d.destination, d.status, d.attempts, d.last_error, d.provider_ref, d.created_at,
                       d.sent_at, n.event_type, n.title, u.full_name AS recipient_name
                  FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id
                  JOIN users u ON u.id = n.user_id
                 WHERE (?::text IS NULL OR d.status = ?)
                 ORDER BY d.created_at DESC LIMIT 300
                """, new DataClassRowMapper<>(DeliveryRow.class), status, status);
    }

    @PostMapping("/deliveries/{id}/retry")
    public void retry(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        int n = jdbc.update("UPDATE notification_deliveries SET status = 'QUEUED', attempts = 0 WHERE id = ? AND status = 'FAILED'", id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed messages can be retried");
        audit.log(user, "NOTIFICATION_RETRY", "NOTIFICATION_DELIVERY", id, null, null, null);
    }
}
