package com.petrolpump.credit.approval;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.audit.AuditService.AuditEvent;
import com.petrolpump.credit.credit.CreditService;
import com.petrolpump.credit.notification.NotificationService;
import com.petrolpump.credit.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Customer approval of a supplied bill. Approval is optional and never touches the ledger: the sale was
 * posted when the cashier saved it. Every status change is appended to customer_approvals.
 */
@Service
public class ApprovalService {

    /** States in which the customer may still answer. A late answer after NO_RESPONSE is accepted. */
    public static final List<String> ANSWERABLE = List.of("AWAITING_CUSTOMER_APPROVAL", "NO_RESPONSE");

    public record HistoryRow(long id, String fromStatus, String toStatus, String reason, String actedByName,
                             OffsetDateTime actedAt) {}

    /** The fields of a sale that approval and dispute handling need, read under a row lock. */
    public record LockedSale(long id, String txnNo, long customerId, String customerName, BigDecimal amount,
                             String vehicleNumber, long cashierId, String approvalStatus, String status) {}

    private final JdbcTemplate jdbc;
    private final CreditService credit;
    private final NotificationService notify;
    private final AuditService audit;

    public ApprovalService(JdbcTemplate jdbc, CreditService credit, NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.credit = credit;
        this.notify = notify;
        this.audit = audit;
    }

    public List<HistoryRow> history(long saleId) {
        return jdbc.query("""
                SELECT a.id, a.from_status, a.to_status, a.reason, COALESCE(u.full_name, 'System') AS acted_by_name, a.acted_at
                  FROM customer_approvals a LEFT JOIN users u ON u.id = a.acted_by
                 WHERE a.credit_transaction_id = ? ORDER BY a.acted_at, a.id
                """, new DataClassRowMapper<>(HistoryRow.class), saleId);
    }

    @Transactional
    public void approve(long saleId, String remarks, CurrentUser actor) {
        LockedSale sale = lockOwn(saleId, actor);
        requireAnswerable(sale);
        transition(sale, "CUSTOMER_APPROVED", remarks == null || remarks.isBlank() ? "Approved by customer" : remarks.trim(),
                actor.id());
        audit.log(actor, "BILL_APPROVED", "CREDIT_TRANSACTION", saleId,
                Map.of("approvalStatus", sale.approvalStatus()), Map.of("approvalStatus", "CUSTOMER_APPROVED"), remarks);
        notify.toUser(sale.cashierId(), "BILL_APPROVED", vars(sale), "CREDIT_TRANSACTION", saleId);
    }

    /** Rejection opens a dispute straight away so it lands in the manager's queue (TEST 4). */
    @Transactional
    public long reject(long saleId, String reason, CurrentUser actor) {
        LockedSale sale = lockOwn(saleId, actor);
        requireAnswerable(sale);
        transition(sale, "CUSTOMER_REJECTED", reason, actor.id());
        Map<String, Object> dispute = jdbc.queryForMap("""
                INSERT INTO disputes (credit_transaction_id, customer_id, raised_by, reason)
                VALUES (?, ?, ?, ?) RETURNING id, dispute_no
                """, saleId, sale.customerId(), actor.id(), reason);
        long disputeId = ((Number) dispute.get("id")).longValue();
        audit.log(actor, "BILL_REJECTED", "CREDIT_TRANSACTION", saleId,
                Map.of("approvalStatus", sale.approvalStatus()),
                Map.of("approvalStatus", "CUSTOMER_REJECTED", "disputeNo", dispute.get("dispute_no")), reason);

        Map<String, Object> vars = vars(sale);
        vars.put("reason", reason);
        vars.put("disputeNo", dispute.get("dispute_no"));
        notify.toUser(sale.cashierId(), "BILL_REJECTED", vars, "CREDIT_TRANSACTION", saleId);
        notify.toPermission("DISPUTE_RESOLVE", "DISPUTE_RAISED", vars, "DISPUTE", disputeId);
        return disputeId;
    }

    /**
     * Bills nobody answered within CUSTOMER_APPROVAL_TIMEOUT_HRS become NO_RESPONSE. The sale stays in the
     * ledger as before; the customer gets a reminder and can still approve or reject later.
     */
    @Transactional
    public int markNoResponse() {
        int hours = Integer.parseInt(credit.setting("CUSTOMER_APPROVAL_TIMEOUT_HRS", "48"));
        List<LockedSale> stale = jdbc.query(SELECT_LOCKED + """
                 WHERE t.approval_status = 'AWAITING_CUSTOMER_APPROVAL' AND t.created_at < now() - make_interval(hours => ?)
                 ORDER BY t.id FOR UPDATE OF t SKIP LOCKED LIMIT 500
                """, LOCKED, hours);
        for (LockedSale sale : stale) {
            transition(sale, "NO_RESPONSE", "No customer response within " + hours + " hours", null);
            audit.record(new AuditEvent(null, null, "BILL_NO_RESPONSE", "CREDIT_TRANSACTION", String.valueOf(sale.id()),
                    Map.of("approvalStatus", sale.approvalStatus()), Map.of("approvalStatus", "NO_RESPONSE"),
                    "Approval timeout " + hours + "h", null, null));
            notify.toCustomer(sale.customerId(), "BILL_APPROVAL_PENDING", vars(sale), "CREDIT_TRANSACTION", sale.id());
        }
        return stale.size();
    }

    /** Locks the sale row. Callers check the current status before moving it on. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedSale lock(long saleId) {
        return jdbc.query(SELECT_LOCKED + " WHERE t.id = ? FOR UPDATE OF t", LOCKED, saleId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
    }

    /** Moves the approval status and appends the history row. actorId null = system. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(LockedSale sale, String to, String reason, Long actorId) {
        jdbc.update("""
                UPDATE credit_transactions SET approval_status = ?, approval_updated_at = now(), updated_by = COALESCE(?, updated_by)
                 WHERE id = ?
                """, to, actorId, sale.id());
        jdbc.update("""
                INSERT INTO customer_approvals (credit_transaction_id, from_status, to_status, reason, acted_by)
                VALUES (?, ?, ?, ?, ?)
                """, sale.id(), sale.approvalStatus(), to, reason, actorId);
    }

    public static Map<String, Object> vars(LockedSale sale) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("txnNo", sale.txnNo());
        vars.put("customerName", sale.customerName());
        vars.put("vehicleNo", sale.vehicleNumber());
        vars.put("amount", java.text.NumberFormat.getCurrencyInstance(java.util.Locale.of("en", "IN")).format(sale.amount()));
        return vars;
    }

    private LockedSale lockOwn(long saleId, CurrentUser actor) {
        LockedSale sale = lock(saleId);
        // Only the customer whose account was billed may answer; 404 so other customers learn nothing.
        if (actor.customerId() == null || !Objects.equals(sale.customerId(), actor.customerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found");
        }
        return sale;
    }

    private static void requireAnswerable(LockedSale sale) {
        if ("REVERSED".equals(sale.status())) throw conflict("This sale has been reversed");
        if (!ANSWERABLE.contains(sale.approvalStatus())) {
            throw conflict("This bill has already been answered (" + sale.approvalStatus().replace('_', ' ').toLowerCase() + ")");
        }
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    private static final String SELECT_LOCKED = """
            SELECT t.id, t.txn_no, t.customer_id, c.name AS customer_name, t.amount, v.vehicle_number, t.cashier_id,
                   t.approval_status, t.status
              FROM credit_transactions t
              JOIN customers c ON c.id = t.customer_id
              JOIN vehicles v ON v.id = t.vehicle_id
            """;
    private static final DataClassRowMapper<LockedSale> LOCKED = new DataClassRowMapper<>(LockedSale.class);
}
