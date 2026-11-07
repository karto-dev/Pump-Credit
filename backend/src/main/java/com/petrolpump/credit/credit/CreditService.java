package com.petrolpump.credit.credit;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class CreditService {

    /**
     * outstanding: confirmed ledger balance. openIndents: estimated value of indents not yet filled.
     * headroom = limit + exception - outstanding - openIndents.
     */
    public record CreditPosition(long customerId, String status, BigDecimal creditLimit, BigDecimal exceptionLimit,
                                 BigDecimal outstanding, BigDecimal openIndents) {
        public BigDecimal totalLimit() { return creditLimit.add(exceptionLimit); }
        public BigDecimal headroom() { return totalLimit().subtract(outstanding).subtract(openIndents); }
        public int utilisationPercent() {
            if (totalLimit().signum() <= 0) return outstanding.signum() > 0 ? 100 : 0;
            return outstanding.multiply(BigDecimal.valueOf(100)).divide(totalLimit(), 0, java.math.RoundingMode.HALF_UP).intValue();
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CreditService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public CreditPosition position(long customerId) {
        return jdbc.query("""
                SELECT c.id, c.status, c.credit_limit, b.active_exception_limit, b.confirmed_outstanding,
                       COALESCE((SELECT SUM(COALESCE(i.requested_amount,
                                                     i.requested_quantity * (SELECT r.rate_per_litre FROM fuel_rates r
                                                                              WHERE r.fuel_type = i.fuel_type AND r.effective_from <= now()
                                                                              ORDER BY r.effective_from DESC LIMIT 1),
                                                     0))
                                   FROM indents i WHERE i.customer_id = c.id AND i.status IN ('PENDING','ACCEPTED')), 0) AS open_indents
                  FROM customers c JOIN v_customer_balances b ON b.customer_id = c.id
                 WHERE c.id = ?
                """, (rs, i) -> new CreditPosition(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)), customerId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
    }

    /**
     * Age of what is still unpaid. Payments clear the oldest debits first (FIFO), so the outstanding balance
     * is made of the newest debits: walk debits newest-first until the balance is covered. Ages are in IST days.
     * overdue = the unpaid part older than the customer's credit period plus OVERDUE_GRACE_DAYS.
     */
    public record Ageing(long customerId, BigDecimal outstanding, BigDecimal upTo30, BigDecimal days31to60,
                         BigDecimal days61to90, BigDecimal over90, BigDecimal overdue, OffsetDateTime oldestUnpaidAt) {}

    public List<Ageing> ageing(Long customerId) {
        int grace = Integer.parseInt(setting("OVERDUE_GRACE_DAYS", "0"));
        String oneCustomer = customerId == null ? "" : " AND l.customer_id = " + customerId.longValue();
        return jdbc.query("""
                WITH bal AS (
                    SELECT customer_id, SUM(debit) - SUM(credit) AS outstanding FROM ledger_entries l
                     WHERE 1 = 1 %1$s GROUP BY customer_id
                ), d AS (
                    SELECT l.customer_id, l.entry_at, l.debit,
                           SUM(l.debit) OVER (PARTITION BY l.customer_id ORDER BY l.entry_at DESC, l.id DESC) AS cum
                      FROM ledger_entries l WHERE l.debit > 0 %1$s
                ), u AS (
                    SELECT d.customer_id, d.entry_at,
                           (now() AT TIME ZONE 'Asia/Kolkata')::date - (d.entry_at AT TIME ZONE 'Asia/Kolkata')::date AS age,
                           GREATEST(0, LEAST(d.debit, b.outstanding - (d.cum - d.debit))) AS unpaid
                      FROM d JOIN bal b ON b.customer_id = d.customer_id
                     WHERE b.outstanding > 0
                )
                SELECT c.id, COALESCE(b.outstanding, 0),
                       COALESCE(SUM(u.unpaid) FILTER (WHERE u.age <= 30), 0),
                       COALESCE(SUM(u.unpaid) FILTER (WHERE u.age BETWEEN 31 AND 60), 0),
                       COALESCE(SUM(u.unpaid) FILTER (WHERE u.age BETWEEN 61 AND 90), 0),
                       COALESCE(SUM(u.unpaid) FILTER (WHERE u.age > 90), 0),
                       COALESCE(SUM(u.unpaid) FILTER (WHERE u.age > c.credit_period_days + ?), 0),
                       MIN(u.entry_at) FILTER (WHERE u.unpaid > 0)
                  FROM customers c
                  LEFT JOIN bal b ON b.customer_id = c.id
                  LEFT JOIN u ON u.customer_id = c.id
                 WHERE %2$s
                 GROUP BY c.id, b.outstanding, c.credit_period_days
                """.formatted(oneCustomer, customerId == null ? "TRUE" : "c.id = " + customerId.longValue()),
                (rs, i) -> new Ageing(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        rs.getObject(8, OffsetDateTime.class)), grace);
    }

    public BigDecimal overdue(long customerId) {
        return ageing(customerId).stream().findFirst().map(Ageing::overdue).orElse(BigDecimal.ZERO);
    }

    /** Daily housekeeping: exceptions past valid-until become EXPIRED. Returns the expired rows. */
    @Transactional
    public List<Map<String, Object>> expireExceptions() {
        return jdbc.queryForList("""
                UPDATE credit_limit_exceptions SET status = 'EXPIRED'
                 WHERE status = 'ACTIVE' AND valid_until < now()
                RETURNING id, customer_id, extra_limit, valid_until
                """);
    }

    public String setting(String key, String fallback) {
        return jdbc.query("SELECT value FROM system_settings WHERE key = ?", (rs, i) -> rs.getString(1), key)
                .stream().findFirst().orElse(fallback);
    }

    /** Admin-only temporary headroom. Callers enforce the ADMIN role; this enforces the business limits. */
    @Transactional
    public long grantException(long customerId, BigDecimal extraLimit, OffsetDateTime validFrom,
                               OffsetDateTime validUntil, String reason, CurrentUser actor) {
        OffsetDateTime from = validFrom != null ? validFrom : OffsetDateTime.now();
        if (!validUntil.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid-until must be after valid-from");
        }
        if (validUntil.isAfter(from.plusDays(90))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Exceptions are temporary - maximum validity is 90 days. Change the credit limit instead.");
        }
        // Default start is the DB clock so the exception is already active for checks later in this transaction.
        Long id = jdbc.queryForObject("""
                INSERT INTO credit_limit_exceptions (customer_id, extra_limit, valid_from, valid_until, reason, authorised_by)
                VALUES (?, ?, COALESCE(?::timestamptz, now()), ?, ?, ?) RETURNING id
                """, Long.class, customerId, extraLimit, validFrom == null ? null : Timestamp.from(validFrom.toInstant()),
                Timestamp.from(validUntil.toInstant()), reason.trim(), actor.id());
        audit.log(actor, "CREDIT_EXCEPTION_GRANTED", "CUSTOMER", customerId, null,
                java.util.Map.of("exceptionId", id, "extraLimit", extraLimit, "validFrom", from.toString(),
                        "validUntil", validUntil.toString()), reason);
        return id;
    }
}
