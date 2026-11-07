package com.petrolpump.credit.dispute;

import com.petrolpump.credit.approval.ApprovalService;
import com.petrolpump.credit.approval.ApprovalService.LockedSale;
import com.petrolpump.credit.audit.AuditService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Disputes raised when a customer rejects a bill.
 * <ul>
 *   <li>BILL_UPHELD resolves at once; the sale stays as billed.</li>
 *   <li>ADJUSTED / REVERSED raise a credit adjustment that a second person must approve. The dispute
 *       resolves only when that adjustment is approved and posted to the ledger.</li>
 * </ul>
 */
@Service
public class DisputeService {

    public record DisputeRow(long id, String disputeNo, String status, String reason, OffsetDateTime raisedAt,
                             String raisedByName, Long assignedTo, String assignedToName, String resolutionType,
                             String resolutionNotes, Long adjustmentId, String adjustmentNo, String adjustmentStatus,
                             BigDecimal adjustmentAmount, String resolvedByName, OffsetDateTime resolvedAt,
                             long saleId, String txnNo, long customerId, String customerName, String vehicleNumber,
                             String fuelType, BigDecimal quantity, BigDecimal ratePerLitre, BigDecimal amount,
                             OffsetDateTime suppliedAt, String cashierName, String approvalStatus, String saleStatus) {}

    public record Summary(long awaitingCount, BigDecimal awaitingAmount, long noResponseCount, BigDecimal noResponseAmount,
                          long openDisputes, long underInvestigation, BigDecimal disputedAmount, long pendingAdjustments) {}

    public enum Resolution { BILL_UPHELD, ADJUSTED, REVERSED }

    private static final String SELECT = """
            SELECT d.id, d.dispute_no, d.status, d.reason, d.raised_at, rb.full_name AS raised_by_name,
                   d.assigned_to, au.full_name AS assigned_to_name, d.resolution_type, d.resolution_notes,
                   d.adjustment_id, a.adjustment_no, a.status AS adjustment_status, a.amount AS adjustment_amount,
                   sb.full_name AS resolved_by_name, d.resolved_at,
                   t.id AS sale_id, t.txn_no, t.customer_id, c.name AS customer_name, v.vehicle_number, t.fuel_type,
                   t.quantity, t.rate_per_litre, t.amount, t.supplied_at, cu.full_name AS cashier_name,
                   t.approval_status, t.status AS sale_status
              FROM disputes d
              JOIN credit_transactions t ON t.id = d.credit_transaction_id
              JOIN customers c ON c.id = t.customer_id
              JOIN vehicles v ON v.id = t.vehicle_id
              JOIN users rb ON rb.id = d.raised_by
              JOIN users cu ON cu.id = t.cashier_id
              LEFT JOIN users au ON au.id = d.assigned_to
              LEFT JOIN users sb ON sb.id = d.resolved_by
              LEFT JOIN adjustments a ON a.id = d.adjustment_id
            """;
    private static final DataClassRowMapper<DisputeRow> MAPPER = new DataClassRowMapper<>(DisputeRow.class);

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final NotificationService notify;
    private final AuditService audit;

    public DisputeService(JdbcTemplate jdbc, ApprovalService approvals, NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.notify = notify;
        this.audit = audit;
    }

    public List<DisputeRow> list(String statusCsv, Long customerId, String q) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (statusCsv != null && !statusCsv.isBlank()) {
            sql.append(" AND d.status = ANY(string_to_array(?, ','))");
            args.add(statusCsv);
        }
        if (customerId != null) { sql.append(" AND d.customer_id = ?"); args.add(customerId); }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            sql.append(" AND (lower(c.name) LIKE ? OR lower(t.txn_no) LIKE ? OR lower(d.dispute_no) LIKE ? OR v.vehicle_number LIKE ?)");
            args.addAll(List.of(like, like, like, "%" + q.replaceAll("[\\s-]", "").toUpperCase() + "%"));
        }
        sql.append(" ORDER BY (d.status = 'RESOLVED'), d.raised_at DESC LIMIT 500");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public DisputeRow get(long id) {
        return jdbc.query(SELECT + " WHERE d.id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispute not found"));
    }

    public Summary summary() {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM credit_transactions WHERE approval_status = 'AWAITING_CUSTOMER_APPROVAL' AND status = 'POSTED'),
                       (SELECT COALESCE(SUM(amount), 0) FROM credit_transactions WHERE approval_status = 'AWAITING_CUSTOMER_APPROVAL' AND status = 'POSTED'),
                       (SELECT COUNT(*) FROM credit_transactions WHERE approval_status = 'NO_RESPONSE' AND status = 'POSTED'),
                       (SELECT COALESCE(SUM(amount), 0) FROM credit_transactions WHERE approval_status = 'NO_RESPONSE' AND status = 'POSTED'),
                       (SELECT COUNT(*) FROM disputes WHERE status = 'OPEN'),
                       (SELECT COUNT(*) FROM disputes WHERE status = 'UNDER_INVESTIGATION'),
                       (SELECT COALESCE(SUM(t.amount), 0) FROM disputes d JOIN credit_transactions t ON t.id = d.credit_transaction_id
                         WHERE d.status <> 'RESOLVED'),
                       (SELECT COUNT(*) FROM adjustments WHERE status = 'PENDING_APPROVAL')
                """, (rs, i) -> new Summary(rs.getLong(1), rs.getBigDecimal(2), rs.getLong(3), rs.getBigDecimal(4),
                rs.getLong(5), rs.getLong(6), rs.getBigDecimal(7), rs.getLong(8)));
    }

    /** A manager takes the dispute. The sale's approval status moves to UNDER_DISPUTE. */
    @Transactional
    public DisputeRow investigate(long id, String notes, CurrentUser actor) {
        Map<String, Object> d = lockDispute(id);
        if (!"OPEN".equals(d.get("status"))) throw conflict("Dispute is already " + label(d.get("status")));
        jdbc.update("UPDATE disputes SET status = 'UNDER_INVESTIGATION', assigned_to = ? WHERE id = ?", actor.id(), id);
        LockedSale sale = approvals.lock(((Number) d.get("credit_transaction_id")).longValue());
        if (!"UNDER_DISPUTE".equals(sale.approvalStatus())) {
            approvals.transition(sale, "UNDER_DISPUTE", "Dispute " + d.get("dispute_no") + " taken up by " + actor.fullName()
                    + (notes == null || notes.isBlank() ? "" : ": " + notes.trim()), actor.id());
        }
        audit.log(actor, "DISPUTE_INVESTIGATING", "DISPUTE", id, Map.of("status", "OPEN"),
                Map.of("status", "UNDER_INVESTIGATION", "assignedTo", actor.username()), notes);
        Map<String, Object> vars = ApprovalService.vars(sale);
        vars.put("disputeNo", d.get("dispute_no"));
        vars.put("assignedTo", actor.fullName());
        notify.toCustomer(sale.customerId(), "DISPUTE_INVESTIGATING", vars, "CREDIT_TRANSACTION", sale.id());
        return get(id);
    }

    /**
     * Records the outcome. BILL_UPHELD closes the dispute now. ADJUSTED and REVERSED create a pending
     * credit adjustment; {@link #completeWithAdjustment} closes the dispute once it is approved.
     */
    @Transactional
    public DisputeRow resolve(long id, Resolution resolution, BigDecimal adjustAmount, String notes, CurrentUser actor) {
        Map<String, Object> d = lockDispute(id);
        if ("RESOLVED".equals(d.get("status"))) throw conflict("Dispute is already resolved");
        if (d.get("adjustment_id") != null) {
            throw conflict("An adjustment for this dispute is waiting for approval. Approve or reject it first.");
        }
        LockedSale sale = approvals.lock(((Number) d.get("credit_transaction_id")).longValue());
        if ("REVERSED".equals(sale.status())) throw conflict("This sale is already reversed");

        if (resolution == Resolution.BILL_UPHELD) {
            jdbc.update("""
                    UPDATE disputes SET status = 'RESOLVED', resolution_type = 'BILL_UPHELD', resolution_notes = ?,
                                        assigned_to = COALESCE(assigned_to, ?), resolved_by = ?, resolved_at = now()
                     WHERE id = ?
                    """, notes, actor.id(), actor.id(), id);
            approvals.transition(sale, "RESOLVED", "Bill upheld: " + notes, actor.id());
            audit.log(actor, "DISPUTE_RESOLVED", "DISPUTE", id, Map.of("status", d.get("status")),
                    Map.of("status", "RESOLVED", "resolution", "BILL_UPHELD"), notes);
            notifyResolved(sale, (String) d.get("dispute_no"), "Bill upheld. " + notes);
            return get(id);
        }

        BigDecimal amount = resolution == Resolution.REVERSED ? sale.amount() : adjustAmount;
        if (amount == null || amount.signum() <= 0) throw bad("Enter the amount to credit back to the customer");
        if (amount.compareTo(sale.amount()) >= 0 && resolution == Resolution.ADJUSTED) {
            throw bad("An adjustment must be less than the bill amount. Use Reverse to cancel the whole bill.");
        }
        Map<String, Object> adj = jdbc.queryForMap("""
                INSERT INTO adjustments (customer_id, adj_type, amount, reason, credit_transaction_id, dispute_id, created_by)
                VALUES (?, 'CREDIT', ?, ?, ?, ?, ?) RETURNING id, adjustment_no
                """, sale.customerId(), amount,
                "%s %s for dispute %s: %s".formatted(resolution == Resolution.REVERSED ? "Reversal of" : "Correction to",
                        sale.txnNo(), d.get("dispute_no"), notes),
                sale.id(), id, actor.id());
        long adjId = ((Number) adj.get("id")).longValue();
        jdbc.update("""
                UPDATE disputes SET status = 'UNDER_INVESTIGATION', assigned_to = COALESCE(assigned_to, ?),
                                    resolution_type = ?, resolution_notes = ?, adjustment_id = ?
                 WHERE id = ?
                """, actor.id(), resolution.name(), notes, adjId, id);
        if (!"UNDER_DISPUTE".equals(sale.approvalStatus())) {
            approvals.transition(sale, "UNDER_DISPUTE", "Adjustment " + adj.get("adjustment_no") + " proposed", actor.id());
        }
        audit.log(actor, "DISPUTE_ADJUSTMENT_PROPOSED", "DISPUTE", id, Map.of("status", d.get("status")),
                Map.of("resolution", resolution.name(), "adjustmentNo", adj.get("adjustment_no"), "amount", amount), notes);
        Map<String, Object> vars = ApprovalService.vars(sale);
        vars.put("adjustmentNo", adj.get("adjustment_no"));
        vars.put("amount", inr(amount));
        vars.put("reason", notes);
        notify.toPermission("ADJUSTMENT_APPROVE", "ADJUSTMENT_PENDING", vars, "ADJUSTMENT", adjId);
        return get(id);
    }

    /** Called when the dispute's adjustment is approved and posted. Runs inside the approval transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeWithAdjustment(long disputeId, long adjustmentId, CurrentUser approver) {
        Map<String, Object> d = lockDispute(disputeId);
        LockedSale sale = approvals.lock(((Number) d.get("credit_transaction_id")).longValue());
        // The investigator who proposed the outcome is recorded as resolver; the adjustment records the approver.
        jdbc.update("""
                UPDATE disputes SET status = 'RESOLVED', resolved_by = COALESCE(assigned_to, ?), resolved_at = now()
                 WHERE id = ?
                """, approver.id(), disputeId);
        if ("REVERSED".equals(d.get("resolution_type"))) {
            jdbc.update("UPDATE credit_transactions SET status = 'REVERSED', reversed_by_adjustment_id = ?, updated_by = ? WHERE id = ?",
                    adjustmentId, approver.id(), sale.id());
        }
        String label = "REVERSED".equals(d.get("resolution_type")) ? "Bill reversed" : "Bill adjusted";
        approvals.transition(sale, "RESOLVED", label + ": " + d.get("resolution_notes"), approver.id());
        audit.log(approver, "DISPUTE_RESOLVED", "DISPUTE", disputeId, Map.of("status", d.get("status")),
                Map.of("status", "RESOLVED", "resolution", d.get("resolution_type"), "adjustmentId", adjustmentId), null);
        notifyResolved(sale, (String) d.get("dispute_no"), label + ". " + d.get("resolution_notes"));
    }

    /** The adjustment was turned down: the dispute goes back to the investigator with no outcome. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reopenAfterRejectedAdjustment(long disputeId) {
        lockDispute(disputeId);
        jdbc.update("""
                UPDATE disputes SET adjustment_id = NULL, resolution_type = NULL, resolution_notes = NULL WHERE id = ?
                """, disputeId);
    }

    private void notifyResolved(LockedSale sale, String disputeNo, String resolution) {
        Map<String, Object> vars = new HashMap<>(ApprovalService.vars(sale));
        vars.put("disputeNo", disputeNo);
        vars.put("resolution", resolution);
        notify.toCustomer(sale.customerId(), "DISPUTE_RESOLVED", vars, "CREDIT_TRANSACTION", sale.id());
        notify.toUser(sale.cashierId(), "DISPUTE_RESOLVED", vars, "CREDIT_TRANSACTION", sale.id());
    }

    private Map<String, Object> lockDispute(long id) {
        return jdbc.queryForList("SELECT * FROM disputes WHERE id = ? FOR UPDATE", id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispute not found"));
    }

    private static String label(Object status) {
        return String.valueOf(status).replace('_', ' ').toLowerCase();
    }

    private static String inr(BigDecimal v) {
        return java.text.NumberFormat.getCurrencyInstance(java.util.Locale.of("en", "IN")).format(v);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
