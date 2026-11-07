package com.petrolpump.credit.jobs;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.audit.AuditService.AuditEvent;
import com.petrolpump.credit.credit.CreditService;
import com.petrolpump.credit.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Credit-limit exception expiry (hourly) and outstanding-payment reminders (daily). Each run takes a Postgres
 * advisory lock so a second backend instance skips instead of sending reminders twice.
 */
@Component
public class HousekeepingJobs {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingJobs.class);
    private static final long LOCK_EXPIRY = 72_001;
    private static final long LOCK_REMINDERS = 72_002;

    private final JdbcTemplate jdbc;
    private final CreditService credit;
    private final NotificationService notify;
    private final AuditService audit;

    public HousekeepingJobs(JdbcTemplate jdbc, CreditService credit, NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.credit = credit;
        this.notify = notify;
        this.audit = audit;
    }

    @Scheduled(initialDelayString = "${app.jobs.expiry-initial-ms:60000}", fixedDelayString = "${app.jobs.expiry-ms:3600000}")
    @Transactional
    public void expireCreditExceptions() {
        if (!lock(LOCK_EXPIRY)) return;
        List<Map<String, Object>> expired = credit.expireExceptions();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
        for (Map<String, Object> e : expired) {
            long customerId = ((Number) e.get("customer_id")).longValue();
            OffsetDateTime until = ((java.sql.Timestamp) e.get("valid_until")).toInstant().atZone(ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
            audit.record(new AuditEvent(null, "SYSTEM", "CREDIT_EXCEPTION_EXPIRED", "CUSTOMER", String.valueOf(customerId),
                    Map.of("status", "ACTIVE"), Map.of("status", "EXPIRED", "exceptionId", e.get("id")), null, null, null));
            notify.toCustomer(customerId, "CREDIT_EXCEPTION_EXPIRED",
                    Map.of("amount", inr((BigDecimal) e.get("extra_limit")), "validUntil", f.format(until)), "CUSTOMER", customerId);
        }
        if (!expired.isEmpty()) log.info("Expired {} credit-limit exception(s)", expired.size());
    }

    /** Customers who owe money and have not been reminded within PAYMENT_REMINDER_DAYS (0 switches reminders off). */
    @Scheduled(cron = "${app.jobs.reminder-cron:0 0 10 * * *}", zone = "Asia/Kolkata")
    @Transactional
    public int sendReminders() {
        if (!lock(LOCK_REMINDERS)) return 0;
        int days = Integer.parseInt(credit.setting("PAYMENT_REMINDER_DAYS", "7"));
        if (days <= 0) return 0;
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT b.customer_id, b.confirmed_outstanding
                  FROM v_customer_balances b JOIN customers c ON c.id = b.customer_id
                 WHERE b.confirmed_outstanding > 0 AND c.status <> 'CLOSED'
                   AND EXISTS (SELECT 1 FROM users u WHERE u.customer_id = b.customer_id AND u.is_active)
                   AND NOT EXISTS (SELECT 1 FROM notifications n JOIN users u ON u.id = n.user_id
                                    WHERE u.customer_id = b.customer_id AND n.event_type = 'OUTSTANDING_REMINDER'
                                      AND n.created_at > now() - make_interval(days => ?))
                """, days);
        for (Map<String, Object> d : due) {
            long customerId = ((Number) d.get("customer_id")).longValue();
            BigDecimal overdue = credit.overdue(customerId);
            String text = inr((BigDecimal) d.get("confirmed_outstanding"))
                    + (overdue.signum() > 0 ? " (" + inr(overdue) + " overdue)" : "");
            notify.toCustomer(customerId, "OUTSTANDING_REMINDER", Map.of("outstanding", text), "CUSTOMER", customerId);
        }
        if (!due.isEmpty()) log.info("Sent outstanding reminders to {} customer(s)", due.size());
        return due.size();
    }

    private boolean lock(long key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, key));
    }

    private static String inr(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(Locale.of("en", "IN")).format(v);
    }
}
