package com.petrolpump.credit.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Drains the SMS / WhatsApp outbox. Rows are claimed with SKIP LOCKED so several backend instances never send
 * the same message twice. A failed message is retried on later runs and marked FAILED after 3 attempts.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final MessageSender sender;

    public DeliveryWorker(JdbcTemplate jdbc, MessageSender sender) {
        this.jdbc = jdbc;
        this.sender = sender;
    }

    @Scheduled(initialDelayString = "${app.jobs.delivery-initial-ms:20000}", fixedDelayString = "${app.jobs.delivery-ms:30000}")
    @Transactional
    public void run() {
        List<Map<String, Object>> batch = jdbc.queryForList("""
                SELECT d.id, d.channel, d.destination, d.attempts, n.title, n.message
                  FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id
                 WHERE d.status = 'QUEUED' ORDER BY d.created_at LIMIT 50 FOR UPDATE OF d SKIP LOCKED
                """);
        for (Map<String, Object> d : batch) {
            long id = ((Number) d.get("id")).longValue();
            int attempts = ((Number) d.get("attempts")).intValue() + 1;
            try {
                String ref = sender.send((String) d.get("channel"), (String) d.get("destination"),
                        d.get("title") + ": " + d.get("message"));
                jdbc.update("""
                        UPDATE notification_deliveries SET status = 'SENT', attempts = ?, provider_ref = ?, sent_at = now(),
                                                           last_error = NULL
                         WHERE id = ?
                        """, attempts, ref, id);
            } catch (RuntimeException e) {
                String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                jdbc.update("UPDATE notification_deliveries SET status = ?, attempts = ?, last_error = ? WHERE id = ?",
                        attempts >= MAX_ATTEMPTS ? "FAILED" : "QUEUED", attempts, err.length() > 500 ? err.substring(0, 500) : err, id);
                log.warn("Delivery {} attempt {} failed: {}", id, attempts, err);
            }
        }
    }
}
