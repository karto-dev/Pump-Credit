package com.petrolpump.credit.adjustment;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.dispute.DisputeService;
import com.petrolpump.credit.ledger.LedgerService;
import com.petrolpump.credit.ledger.LedgerService.EntryType;
import com.petrolpump.credit.ledger.LedgerService.Posting;
import com.petrolpump.credit.notification.NotificationService;
import com.petrolpump.credit.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maker-checker for ledger adjustments: the person who raised an adjustment can never approve it
 * (also enforced by a CHECK constraint). Only an APPROVED adjustment reaches the ledger.
 */
@Service
public class AdjustmentService {

    public record AdjustmentRow(long id, String adjustmentNo, long customerId, String customerName, String adjType,
                                BigDecimal amount, String reason, Long saleId, String txnNo, Long disputeId,
                                String disputeNo, String status, long createdBy, String createdByName,
                                OffsetDateTime createdAt, String approvedByName, OffsetDateTime approvedAt,
                                String approvalRemarks) {}

    private static final String SELECT = """
            SELECT a.id, a.adjustment_no, a.customer_id, c.name AS customer_name, a.adj_type, a.amount, a.reason,
                   a.credit_transaction_id AS sale_id, t.txn_no, a.dispute_id, d.dispute_no, a.status, a.created_by,
                   cb.full_name AS created_by_name, a.created_at, ab.full_name AS approved_by_name, a.approved_at,
                   a.approval_remarks
              FROM adjustments a
              JOIN customers c ON c.id = a.customer_id
              JOIN users cb ON cb.id = a.created_by
              LEFT JOIN users ab ON ab.id = a.approved_by
              LEFT JOIN credit_transactions t ON t.id = a.credit_transaction_id
              LEFT JOIN disputes d ON d.id = a.dispute_id
            """;
    private static final DataClassRowMapper<AdjustmentRow> MAPPER = new DataClassRowMapper<>(AdjustmentRow.class);

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final DisputeService disputes;
    private final NotificationService notify;
    private final AuditService audit;

    public AdjustmentService(JdbcTemplate jdbc, LedgerService ledger, DisputeService disputes,
                             NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.disputes = disputes;
        this.notify = notify;
        this.audit = audit;
    }

    public List<AdjustmentRow> list(String status, Long customerId) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" AND a.status = ?"); args.add(status); }
        if (customerId != null) { sql.append(" AND a.customer_id = ?"); args.add(customerId); }
        sql.append(" ORDER BY (a.status <> 'PENDING_APPROVAL'), a.created_at DESC LIMIT 500");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public AdjustmentRow get(long id) {
        return jdbc.query(SELECT + " WHERE a.id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adjustment not found"));
    }

    /**
     * Manual correction outside a dispute: late-entered sales, interest, bank charges, a wrongly verified payment.
     * It reaches the ledger only after someone else approves it.
     */
    @Transactional
    public AdjustmentRow create(long customerId, String adjType, BigDecimal amount, String reason, Long saleId,
                                CurrentUser actor) {
        jdbc.query("SELECT id FROM customers WHERE id = ?", (rs, i) -> rs.getLong(1), customerId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer not found"));
        if (saleId != null) {
            Long owner = jdbc.query("SELECT customer_id FROM credit_transactions WHERE id = ?", (rs, i) -> rs.getLong(1), saleId)
                    .stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sale not found"));
            if (owner != customerId) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sale belongs to another customer");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO adjustments (customer_id, adj_type, amount, reason, credit_transaction_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, customerId, adjType, amount, reason, saleId, actor.id());
        AdjustmentRow row = get(id);
        audit.log(actor, "ADJUSTMENT_RAISED", "ADJUSTMENT", id, null, row, reason);
        notify.toPermission("ADJUSTMENT_APPROVE", "ADJUSTMENT_PENDING", Map.of("adjustmentNo", row.adjustmentNo(),
                "amount", ("CREDIT".equals(adjType) ? "" : "debit ") + inr(amount), "customerName", row.customerName(),
                "reason", reason), "ADJUSTMENT", id);
        return row;
    }

    private static String inr(BigDecimal v) {
        return java.text.NumberFormat.getCurrencyInstance(java.util.Locale.of("en", "IN")).format(v);
    }

    @Transactional
    public AdjustmentRow approve(long id, String remarks, CurrentUser actor) {
        AdjustmentRow adj = lockPending(id);
        if (adj.createdBy() == actor.id()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You raised this adjustment, so another approver must approve it");
        }
        jdbc.update("""
                UPDATE adjustments SET status = 'APPROVED', approved_by = ?, approved_at = now(), approval_remarks = ?
                 WHERE id = ?
                """, actor.id(), remarks, id);
        boolean credit = "CREDIT".equals(adj.adjType());
        // A ledger row has one source document: the adjustment. Its reason names the sale it corrects.
        ledger.post(new Posting(adj.customerId(), credit ? EntryType.CREDIT_ADJUSTMENT : EntryType.DEBIT_ADJUSTMENT,
                credit ? BigDecimal.ZERO : adj.amount(), credit ? adj.amount() : BigDecimal.ZERO,
                null, null, id, truncate(adj.adjustmentNo() + " " + adj.reason(), 500), actor.id()));
        if (adj.disputeId() != null) disputes.completeWithAdjustment(adj.disputeId(), id, actor);
        audit.log(actor, "ADJUSTMENT_APPROVED", "ADJUSTMENT", id, Map.of("status", "PENDING_APPROVAL"),
                Map.of("status", "APPROVED", "amount", adj.amount(), "type", adj.adjType()), remarks);
        notify.toCustomer(adj.customerId(), "ADJUSTMENT_POSTED", Map.of("adjustmentNo", adj.adjustmentNo(),
                "type", credit ? "Credit" : "Debit", "amount", inr(adj.amount()), "reason", adj.reason()), "ADJUSTMENT", id);
        return get(id);
    }

    @Transactional
    public AdjustmentRow reject(long id, String remarks, CurrentUser actor) {
        AdjustmentRow adj = lockPending(id);
        jdbc.update("""
                UPDATE adjustments SET status = 'REJECTED', approved_by = ?, approved_at = now(), approval_remarks = ?
                 WHERE id = ?
                """, adj.createdBy() == actor.id() ? null : actor.id(), remarks, id);
        if (adj.disputeId() != null) disputes.reopenAfterRejectedAdjustment(adj.disputeId());
        audit.log(actor, "ADJUSTMENT_REJECTED", "ADJUSTMENT", id, Map.of("status", "PENDING_APPROVAL"),
                Map.of("status", "REJECTED"), remarks);
        notify.toUser(adj.createdBy(), "ADJUSTMENT_REJECTED",
                Map.of("adjustmentNo", adj.adjustmentNo(), "remarks", remarks),
                adj.disputeId() != null ? "DISPUTE" : "ADJUSTMENT", adj.disputeId() != null ? adj.disputeId() : id);
        return get(id);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private AdjustmentRow lockPending(long id) {
        String status = jdbc.query("SELECT status FROM adjustments WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adjustment not found"));
        if (!"PENDING_APPROVAL".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Adjustment is already " + status.toLowerCase());
        }
        return get(id);
    }
}
