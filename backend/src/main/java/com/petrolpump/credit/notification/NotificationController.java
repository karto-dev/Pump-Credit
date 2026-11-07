package com.petrolpump.credit.notification;

import com.petrolpump.credit.security.CurrentUser;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    public record NotificationRow(long id, String eventType, String title, String message, String entityType,
                                  Long entityId, boolean isRead, OffsetDateTime createdAt) {}

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> mine(@AuthenticationPrincipal CurrentUser user) {
        List<NotificationRow> rows = jdbc.query("""
                SELECT id, event_type, title, message, entity_type, entity_id, is_read, created_at
                  FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT 50
                """, new DataClassRowMapper<>(NotificationRow.class), user.id());
        Integer unread = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND NOT is_read",
                Integer.class, user.id());
        return Map.of("unread", unread == null ? 0 : unread, "items", rows);
    }

    @PostMapping("/{id}/read")
    public void markRead(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        jdbc.update("UPDATE notifications SET is_read = TRUE, read_at = now() WHERE id = ? AND user_id = ? AND NOT is_read",
                id, user.id());
    }

    @PostMapping("/read-all")
    public void markAllRead(@AuthenticationPrincipal CurrentUser user) {
        jdbc.update("UPDATE notifications SET is_read = TRUE, read_at = now() WHERE user_id = ? AND NOT is_read", user.id());
    }
}
