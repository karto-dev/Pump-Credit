package com.petrolpump.credit.payment;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.common.DuplicateWarningException;
import com.petrolpump.credit.credit.CreditService;
import com.petrolpump.credit.ledger.LedgerService;
import com.petrolpump.credit.ledger.LedgerService.EntryType;
import com.petrolpump.credit.ledger.LedgerService.Posting;
import com.petrolpump.credit.notification.NotificationService;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.storage.FileStorage;
import com.petrolpump.credit.storage.FileStorage.StoredFile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Payments have no gateway: a customer claims "I paid", a cashier records money received, and a verifier
 * (manager/admin) checks the bank statement or cash before the payment reduces outstanding. Only the
 * VERIFIED step touches the ledger, and the cashier who recorded a payment can never verify it.
 */
@Service
public class PaymentService {

    public static final Set<String> OPEN = Set.of("AWAITING_CASHIER_ENTRY", "AWAITING_VERIFICATION", "ADDITIONAL_PROOF_REQUIRED");

    public record PaymentInput(LocalDate paymentDate, BigDecimal amount, String method, String referenceNo,
                               String chequeBank, String remarks, boolean confirmDuplicate) {}

    public record PaymentRow(long id, String paymentNo, long customerId, String customerName, String customerCode,
                             BigDecimal amount, LocalDate paymentDate, String method, String referenceNo, String status,
                             boolean duplicateFlag, String duplicateOfNo, BigDecimal verifiedAmount,
                             String verifiedByName, OffsetDateTime verifiedAt, Long submissionId, String submissionNo,
                             BigDecimal claimedAmount, String submittedByName, OffsetDateTime submittedAt,
                             String submissionRemarks, Long cashierEntryId, String entryNo, BigDecimal receivedAmount,
                             Long recordedBy, String recordedByName, OffsetDateTime recordedAt, String chequeBank,
                             String entryRemarks, long proofCount, String lastAction, String lastRemarks,
                             OffsetDateTime createdAt) {}

    public record Filter(String status, Long customerId, LocalDate from, LocalDate to, String q, String method) {}

    public record HistoryRow(long id, String action, String fromStatus, String toStatus, BigDecimal verifiedAmount,
                             String evidenceChecked, String remarks, String actedByName, OffsetDateTime actedAt) {}

    public record ProofRow(long id, String originalFileName, String contentType, long sizeBytes,
                           String uploadedByName, OffsetDateTime uploadedAt) {}

    public record Proof(byte[] bytes, String contentType) {}

    public record Summary(long awaitingCashCount, BigDecimal awaitingCashAmount, long awaitingVerificationCount,
                          BigDecimal awaitingVerificationAmount, long proofRequestedCount, BigDecimal verifiedToday,
                          BigDecimal verifiedThisMonth) {}

    private static final String SELECT = """
            SELECT p.id, p.payment_no, p.customer_id, c.name AS customer_name, c.customer_code, p.amount, p.payment_date,
                   p.method, p.reference_no, p.status, p.duplicate_flag, dp.payment_no AS duplicate_of_no,
                   p.verified_amount, vu.full_name AS verified_by_name, p.verified_at,
                   s.id AS submission_id, s.submission_no, s.amount AS claimed_amount, su.full_name AS submitted_by_name,
                   s.submitted_at, s.remarks AS submission_remarks,
                   e.id AS cashier_entry_id, e.entry_no, e.amount AS received_amount, e.recorded_by,
                   eu.full_name AS recorded_by_name, e.recorded_at, e.cheque_bank, e.remarks AS entry_remarks,
                   (SELECT COUNT(*) FROM payment_proofs pp WHERE pp.payment_id = p.id) AS proof_count,
                   lv.action AS last_action, lv.remarks AS last_remarks, p.created_at
              FROM payments p
              JOIN customers c ON c.id = p.customer_id
              LEFT JOIN payments dp ON dp.id = p.duplicate_of_id
              LEFT JOIN users vu ON vu.id = p.verified_by
              LEFT JOIN payment_submissions s ON s.id = p.submission_id
              LEFT JOIN users su ON su.id = s.submitted_by
              LEFT JOIN cashier_payment_entries e ON e.id = p.cashier_entry_id
              LEFT JOIN users eu ON eu.id = e.recorded_by
              LEFT JOIN LATERAL (SELECT action, remarks FROM payment_verifications
                                  WHERE payment_id = p.id ORDER BY acted_at DESC, id DESC LIMIT 1) lv ON true
            """;
    private static final DataClassRowMapper<PaymentRow> MAPPER = new DataClassRowMapper<>(PaymentRow.class);

    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final LedgerService ledger;
    private final CreditService credit;
    private final NotificationService notify;
    private final AuditService audit;

    public PaymentService(JdbcTemplate jdbc, FileStorage storage, LedgerService ledger, CreditService credit,
                          NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.ledger = ledger;
        this.credit = credit;
        this.notify = notify;
        this.audit = audit;
    }

    public List<PaymentRow> list(Filter f) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (f.status() != null && !f.status().isBlank()) {
            sql.append(" AND p.status = ANY (string_to_array(?, ','))");
            args.add(f.status());
        }
        if (f.customerId() != null) { sql.append(" AND p.customer_id = ?"); args.add(f.customerId()); }
        if (f.method() != null && !f.method().isBlank()) { sql.append(" AND p.method = ?"); args.add(f.method()); }
        if (f.from() != null) { sql.append(" AND p.payment_date >= ?"); args.add(Date.valueOf(f.from())); }
        if (f.to() != null) { sql.append(" AND p.payment_date <= ?"); args.add(Date.valueOf(f.to())); }
        if (f.q() != null && !f.q().isBlank()) {
            String like = "%" + f.q().trim().toLowerCase() + "%";
            sql.append(" AND (lower(c.name) LIKE ? OR lower(p.payment_no) LIKE ? OR p.reference_norm LIKE ?)");
            args.addAll(List.of(like, like, "%" + f.q().replaceAll("\\s", "").toUpperCase() + "%"));
        }
        sql.append(" ORDER BY (p.status = ANY ('{AWAITING_CASHIER_ENTRY,AWAITING_VERIFICATION,ADDITIONAL_PROOF_REQUIRED}')) DESC,")
           .append(" p.created_at DESC LIMIT 1000");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public PaymentRow get(long id) {
        return jdbc.query(SELECT + " WHERE p.id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    public List<HistoryRow> history(long id) {
        return jdbc.query("""
                SELECT v.id, v.action, v.from_status, v.to_status, v.verified_amount, v.evidence_checked, v.remarks,
                       u.full_name AS acted_by_name, v.acted_at
                  FROM payment_verifications v JOIN users u ON u.id = v.acted_by
                 WHERE v.payment_id = ? ORDER BY v.acted_at, v.id
                """, new DataClassRowMapper<>(HistoryRow.class), id);
    }

    public List<ProofRow> proofs(long id) {
        return jdbc.query("""
                SELECT pp.id, pp.original_file_name, pp.content_type, pp.size_bytes, u.full_name AS uploaded_by_name,
                       pp.uploaded_at
                  FROM payment_proofs pp JOIN users u ON u.id = pp.uploaded_by
                 WHERE pp.payment_id = ? ORDER BY pp.uploaded_at
                """, new DataClassRowMapper<>(ProofRow.class), id);
    }

    public Proof proof(long paymentId, long proofId) {
        return jdbc.query("SELECT storage_key, content_type FROM payment_proofs WHERE id = ? AND payment_id = ?",
                        (rs, i) -> new Proof(storage.read(rs.getString(1)), rs.getString(2)), proofId, paymentId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proof not found"));
    }

    public Summary summary() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status = 'AWAITING_CASHIER_ENTRY'),
                       COALESCE(SUM(amount) FILTER (WHERE status = 'AWAITING_CASHIER_ENTRY'), 0),
                       COUNT(*) FILTER (WHERE status = 'AWAITING_VERIFICATION'),
                       COALESCE(SUM(amount) FILTER (WHERE status = 'AWAITING_VERIFICATION'), 0),
                       COUNT(*) FILTER (WHERE status = 'ADDITIONAL_PROOF_REQUIRED'),
                       COALESCE(SUM(verified_amount) FILTER (WHERE status = 'VERIFIED' AND verified_at >= current_date), 0),
                       COALESCE(SUM(verified_amount) FILTER (WHERE status = 'VERIFIED'
                                                              AND verified_at >= date_trunc('month', current_date)), 0)
                  FROM payments
                """, (rs, i) -> new Summary(rs.getLong(1), rs.getBigDecimal(2), rs.getLong(3), rs.getBigDecimal(4),
                rs.getLong(5), rs.getBigDecimal(6), rs.getBigDecimal(7)));
    }

    // ------------------------------------------------------------------ Phase 4: claims and cashier entries

    /** Customer's claim. Cash waits for a cashier to count it; bank transfers go straight to verification. */
    @Transactional
    public PaymentRow submit(PaymentInput in, MultipartFile proof, CurrentUser actor) {
        long customerId = actor.customerId();
        validate(in);
        StoredFile file = storeOptional(proof, "proofs");
        Long dupOf = in.confirmDuplicate() ? findDuplicate(customerId, in, file, null) : null;
        if (!in.confirmDuplicate()) checkDuplicates(customerId, in, file, null);

        Map<String, Object> sub = jdbc.queryForMap("""
                INSERT INTO payment_submissions (customer_id, amount, payment_date, method, reference_no, remarks, submitted_by)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id, submission_no
                """, customerId, in.amount(), Date.valueOf(in.paymentDate()), in.method(), blankToNull(in.referenceNo()),
                blankToNull(in.remarks()), actor.id());
        long subId = ((Number) sub.get("id")).longValue();
        String status = "CASH".equals(in.method()) ? "AWAITING_CASHIER_ENTRY" : "AWAITING_VERIFICATION";
        Long id = jdbc.queryForObject("""
                INSERT INTO payments (customer_id, submission_id, amount, payment_date, method, reference_no, status,
                                      duplicate_flag, duplicate_of_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, customerId, subId, in.amount(), Date.valueOf(in.paymentDate()), in.method(),
                blankToNull(in.referenceNo()), status, dupOf != null, dupOf);
        if (file != null) saveProof(file, id, subId, null, actor.id());

        PaymentRow row = get(id);
        audit.log(actor, "PAYMENT_SUBMITTED", "PAYMENT", id, null, row,
                dupOf != null ? "Submitted despite duplicate warning" : null);
        Map<String, Object> vars = vars(row);
        vars.put("submissionNo", sub.get("submission_no"));
        // Cash has to be collected at the counter first; everything else is checked against the bank.
        notify.toPermission("CASH".equals(in.method()) ? "PAYMENT_RECORD" : "PAYMENT_VERIFY", "PAYMENT_SUBMITTED",
                vars, "PAYMENT", id);
        return row;
    }

    /**
     * Cashier records money received. Linking to the customer's open claim completes that claim; otherwise a
     * new payment is created. Either way it goes to a verifier who is not this cashier.
     */
    @Transactional
    public PaymentRow record(long customerId, Long linkPaymentId, PaymentInput in, MultipartFile proof, CurrentUser actor) {
        validate(in);
        jdbc.query("SELECT id FROM customers WHERE id = ?", (rs, i) -> rs.getLong(1), customerId).stream().findFirst()
                .orElseThrow(() -> bad("Customer not found"));
        if (!"CASH".equals(in.method()) && (proof == null || proof.isEmpty()) && linkPaymentId == null) {
            // Without a customer claim there is nothing else to check the transfer against.
            throw bad("Attach a screenshot or slip for " + in.method() + " payments");
        }
        StoredFile file = storeOptional(proof, "proofs");

        Long id;
        String linkedFrom = null;
        if (linkPaymentId != null) {
            Map<String, Object> p = lock(linkPaymentId);
            if (((Number) p.get("customer_id")).longValue() != customerId) throw bad("That claim belongs to another customer");
            if (p.get("cashier_entry_id") != null) throw conflict("This claim already has a cashier entry");
            linkedFrom = (String) p.get("status");
            if (!OPEN.contains(linkedFrom)) throw conflict("Payment is " + label(linkedFrom) + " and cannot be recorded against");
            id = linkPaymentId;
        } else {
            if (!in.confirmDuplicate()) checkDuplicates(customerId, in, file, null);
            id = null;
        }
        Long dupOf = linkPaymentId == null && in.confirmDuplicate() ? findDuplicate(customerId, in, file, null) : null;

        Map<String, Object> entry = jdbc.queryForMap("""
                INSERT INTO cashier_payment_entries (customer_id, amount, payment_date, method, reference_no, cheque_bank,
                                                     remarks, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id, entry_no
                """, customerId, in.amount(), Date.valueOf(in.paymentDate()), in.method(), blankToNull(in.referenceNo()),
                blankToNull(in.chequeBank()), blankToNull(in.remarks()), actor.id());
        long entryId = ((Number) entry.get("id")).longValue();

        if (id == null) {
            id = jdbc.queryForObject("""
                    INSERT INTO payments (customer_id, cashier_entry_id, amount, payment_date, method, reference_no, status,
                                          duplicate_flag, duplicate_of_id)
                    VALUES (?, ?, ?, ?, ?, ?, 'AWAITING_VERIFICATION', ?, ?) RETURNING id
                    """, Long.class, customerId, entryId, in.amount(), Date.valueOf(in.paymentDate()), in.method(),
                    blankToNull(in.referenceNo()), dupOf != null, dupOf);
        } else {
            // The money the cashier actually received is what gets verified; the claim stays visible beside it.
            jdbc.update("""
                    UPDATE payments SET cashier_entry_id = ?, amount = ?, payment_date = ?, method = ?, reference_no = ?,
                                        status = 'AWAITING_VERIFICATION', version = version + 1
                     WHERE id = ?
                    """, entryId, in.amount(), Date.valueOf(in.paymentDate()), in.method(), blankToNull(in.referenceNo()), id);
            if (!"AWAITING_VERIFICATION".equals(linkedFrom)) {
                history(id, "REOPENED", linkedFrom, "AWAITING_VERIFICATION", null, null,
                        "Cashier entry " + entry.get("entry_no") + " recorded", actor.id());
            }
        }
        if (file != null) saveProof(file, id, null, entryId, actor.id());

        PaymentRow row = get(id);
        audit.log(actor, "PAYMENT_RECORDED", "PAYMENT", id, null, row,
                dupOf != null ? "Recorded despite duplicate warning" : null);
        Map<String, Object> vars = vars(row);
        vars.put("entryNo", entry.get("entry_no"));
        notify.toCustomer(customerId, "CASHIER_PAYMENT_RECORDED", vars, "PAYMENT", id);
        notify.toPermission("PAYMENT_VERIFY", "PAYMENT_AWAITING_VERIFY", vars, "PAYMENT", id);
        return row;
    }

    /** More proof from the customer or the recording cashier. Answers a proof request by reopening the payment. */
    @Transactional
    public PaymentRow addProof(long id, MultipartFile proof, String remarks, CurrentUser actor) {
        Map<String, Object> p = lock(id);
        String status = (String) p.get("status");
        if (!OPEN.contains(status)) throw conflict("Payment is " + label(status) + "; proof can no longer be added");
        if (proof == null || proof.isEmpty()) throw bad("Choose a file to upload");
        StoredFile file = storeOptional(proof, "proofs");
        saveProof(file, id, null, null, actor.id());
        if ("ADDITIONAL_PROOF_REQUIRED".equals(status)) {
            String next = p.get("cashier_entry_id") == null && "CASH".equals(p.get("method"))
                    ? "AWAITING_CASHIER_ENTRY" : "AWAITING_VERIFICATION";
            jdbc.update("UPDATE payments SET status = ?, version = version + 1 WHERE id = ?", next, id);
            history(id, "REOPENED", status, next, null, null,
                    remarks == null || remarks.isBlank() ? "Additional proof uploaded" : remarks.trim(), actor.id());
        }
        PaymentRow row = get(id);
        audit.log(actor, "PAYMENT_PROOF_ADDED", "PAYMENT", id, null, Map.of("file", file.storageKey()), remarks);
        notify.toPermission("PAYMENT_VERIFY", "PAYMENT_PROOF_ADDED", vars(row), "PAYMENT", id);
        return row;
    }

    /** A customer can withdraw their own claim until a cashier has taken the money. */
    @Transactional
    public PaymentRow cancel(long id, String reason, CurrentUser actor) {
        Map<String, Object> p = lock(id);
        String status = (String) p.get("status");
        if (!OPEN.contains(status)) throw conflict("Payment is " + label(status) + " and cannot be withdrawn");
        if (p.get("cashier_entry_id") != null) {
            throw conflict("The cashier has already recorded this payment. Ask the pump office to reject it instead.");
        }
        jdbc.update("UPDATE payments SET status = 'CANCELLED', version = version + 1 WHERE id = ?", id);
        jdbc.update("UPDATE payment_submissions SET cancelled_by = ?, cancelled_at = now(), cancel_reason = ? WHERE id = ?",
                actor.id(), reason, p.get("submission_id"));
        history(id, "CANCELLED", status, "CANCELLED", null, null, reason, actor.id());
        PaymentRow row = get(id);
        audit.log(actor, "PAYMENT_CANCELLED", "PAYMENT", id, Map.of("status", status), Map.of("status", "CANCELLED"), reason);
        Map<String, Object> vars = vars(row);
        vars.put("reason", reason);
        notify.toPermission("PAYMENT_VERIFY", "PAYMENT_CANCELLED", vars, "PAYMENT", id);
        return row;
    }

    // ------------------------------------------------------------------ Phase 5: verification and ledger

    /**
     * Confirms the money against the bank statement or the cash drawer and posts it to the ledger in the same
     * transaction. verifiedAmount may be less than claimed (short credit) but never more.
     */
    @Transactional
    public PaymentRow verify(long id, BigDecimal verifiedAmount, String evidence, String remarks, CurrentUser actor) {
        Map<String, Object> p = lock(id);
        String status = (String) p.get("status");
        if (!Set.of("AWAITING_VERIFICATION", "ADDITIONAL_PROOF_REQUIRED").contains(status)) {
            throw conflict("Payment is " + label(status) + " and cannot be verified");
        }
        PaymentRow row = get(id);
        if (row.recordedBy() != null && row.recordedBy() == actor.id()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You recorded this payment, so someone else must verify it");
        }
        if ("CASH".equals(row.method()) && row.cashierEntryId() == null) {
            throw conflict("Cash must be received and recorded by a cashier before it can be verified");
        }
        BigDecimal amount = verifiedAmount == null ? row.amount() : verifiedAmount;
        if (amount.signum() <= 0) throw bad("Verified amount must be more than zero");
        if (amount.compareTo(row.amount()) > 0) {
            throw bad("Verified amount cannot be more than the payment amount (" + inr(row.amount()) + ")");
        }
        if (row.referenceNo() != null) {
            jdbc.query("""
                    SELECT payment_no FROM payments
                     WHERE status = 'VERIFIED' AND method = ? AND reference_norm = upper(regexp_replace(?, '\\s', '', 'g'))
                    """, (rs, i) -> rs.getString(1), row.method(), row.referenceNo()).stream().findFirst()
                    .ifPresent(n -> { throw conflict("Reference " + row.referenceNo() + " was already verified on " + n
                            + ". Mark this one as a duplicate instead."); });
        }

        jdbc.update("""
                UPDATE payments SET status = 'VERIFIED', verified_amount = ?, verified_by = ?, verified_at = now(),
                                    version = version + 1
                 WHERE id = ?
                """, amount, actor.id(), id);
        history(id, "VERIFIED", status, "VERIFIED", amount, evidence, remarks, actor.id());
        BigDecimal balance = ledger.post(new Posting(row.customerId(), EntryType.PAYMENT, BigDecimal.ZERO, amount,
                null, id, null, truncate("%s %s%s".formatted(row.paymentNo(), row.method(),
                        row.referenceNo() == null ? "" : " ref " + row.referenceNo()), 500), actor.id()));
        allocate(id, row.customerId(), amount, actor.id());

        PaymentRow done = get(id);
        audit.log(actor, "PAYMENT_VERIFIED", "PAYMENT", id, Map.of("status", status),
                Map.of("status", "VERIFIED", "verifiedAmount", amount, "evidence", evidence == null ? "" : evidence), remarks);
        Map<String, Object> vars = vars(done);
        vars.put("amount", inr(amount));
        vars.put("outstanding", inr(balance));
        notify.toCustomer(row.customerId(), "PAYMENT_VERIFIED", vars, "PAYMENT", id);
        return done;
    }

    @Transactional
    public PaymentRow reject(long id, String remarks, CurrentUser actor) {
        return close(id, "REJECTED", "REJECTED", null, remarks, actor);
    }

    @Transactional
    public PaymentRow markDuplicate(long id, String duplicateOfNo, String remarks, CurrentUser actor) {
        Long original = jdbc.query("SELECT id FROM payments WHERE upper(payment_no) = upper(?)",
                        (rs, i) -> rs.getLong(1), duplicateOfNo.trim()).stream().findFirst()
                .orElseThrow(() -> bad("Payment " + duplicateOfNo + " not found"));
        if (original == id) throw bad("A payment cannot be a duplicate of itself");
        return close(id, "DUPLICATE_PAYMENT", "MARKED_DUPLICATE", original, remarks, actor);
    }

    @Transactional
    public PaymentRow requestProof(long id, String remarks, CurrentUser actor) {
        Map<String, Object> p = lock(id);
        String status = (String) p.get("status");
        if (!Set.of("AWAITING_CASHIER_ENTRY", "AWAITING_VERIFICATION").contains(status)) {
            throw conflict("Payment is " + label(status));
        }
        jdbc.update("UPDATE payments SET status = 'ADDITIONAL_PROOF_REQUIRED', version = version + 1 WHERE id = ?", id);
        history(id, "ADDITIONAL_PROOF_REQUIRED", status, "ADDITIONAL_PROOF_REQUIRED", null, null, remarks, actor.id());
        PaymentRow row = get(id);
        audit.log(actor, "PAYMENT_PROOF_REQUESTED", "PAYMENT", id, Map.of("status", status),
                Map.of("status", "ADDITIONAL_PROOF_REQUIRED"), remarks);
        Map<String, Object> vars = vars(row);
        vars.put("remarks", remarks);
        if (row.submissionId() != null) notify.toCustomer(row.customerId(), "PAYMENT_PROOF_REQUESTED", vars, "PAYMENT", id);
        if (row.recordedBy() != null) notify.toUser(row.recordedBy(), "PAYMENT_PROOF_REQUESTED", vars, "PAYMENT", id);
        return row;
    }

    private PaymentRow close(long id, String to, String action, Long duplicateOf, String remarks, CurrentUser actor) {
        Map<String, Object> p = lock(id);
        String status = (String) p.get("status");
        if (!OPEN.contains(status)) throw conflict("Payment is already " + label(status));
        jdbc.update("""
                UPDATE payments SET status = ?, duplicate_flag = duplicate_flag OR ?, duplicate_of_id = COALESCE(?, duplicate_of_id),
                                    version = version + 1
                 WHERE id = ?
                """, to, duplicateOf != null, duplicateOf, id);
        history(id, action, status, to, null, null, remarks, actor.id());
        PaymentRow row = get(id);
        audit.log(actor, "PAYMENT_" + action, "PAYMENT", id, Map.of("status", status), Map.of("status", to), remarks);
        Map<String, Object> vars = vars(row);
        vars.put("reason", duplicateOf != null ? "Duplicate of " + row.duplicateOfNo() + ". " + remarks : remarks);
        notify.toCustomer(row.customerId(), "PAYMENT_REJECTED", vars, "PAYMENT", id);
        if (row.recordedBy() != null) notify.toUser(row.recordedBy(), "PAYMENT_REJECTED", vars, "PAYMENT", id);
        return row;
    }

    /**
     * Applies verified money to the oldest unpaid sales first (FIFO); anything left is held as an advance.
     * Allocations are for traceability - outstanding always comes from the ledger.
     */
    private void allocate(long paymentId, long customerId, BigDecimal amount, long actorId) {
        BigDecimal left = amount;
        List<Map<String, Object>> dues = jdbc.queryForList("""
                SELECT t.id, t.amount
                       - COALESCE((SELECT SUM(a.amount) FROM payment_allocations a WHERE a.credit_transaction_id = t.id), 0)
                       - COALESCE((SELECT SUM(j.amount) FROM adjustments j
                                    WHERE j.credit_transaction_id = t.id AND j.status = 'APPROVED' AND j.adj_type = 'CREDIT'), 0) AS due
                  FROM credit_transactions t
                 WHERE t.customer_id = ? AND t.status = 'POSTED'
                 ORDER BY t.supplied_at, t.id
                """, customerId);
        for (Map<String, Object> d : dues) {
            if (left.signum() <= 0) break;
            BigDecimal due = (BigDecimal) d.get("due");
            if (due.signum() <= 0) continue;
            BigDecimal take = due.min(left);
            jdbc.update("INSERT INTO payment_allocations (payment_id, credit_transaction_id, amount, allocated_by) VALUES (?, ?, ?, ?)",
                    paymentId, d.get("id"), take, actorId);
            left = left.subtract(take);
        }
        if (left.signum() > 0) {
            jdbc.update("INSERT INTO payment_allocations (payment_id, credit_transaction_id, amount, allocated_by) VALUES (?, NULL, ?, ?)",
                    paymentId, left, actorId);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void validate(PaymentInput in) {
        if (!"CASH".equals(in.method()) && (in.referenceNo() == null || in.referenceNo().isBlank())) {
            throw bad(("CHEQUE".equals(in.method()) ? "Cheque number" : "UTR / reference number") + " is required for " + in.method());
        }
        if (in.paymentDate().isAfter(LocalDate.now())) throw bad("Payment date cannot be in the future");
        int maxDays = Integer.parseInt(credit.setting("PAYMENT_MAX_BACKDATE_DAYS", "90"));
        if (in.paymentDate().isBefore(LocalDate.now().minusDays(maxDays))) {
            throw bad("Payments older than " + maxDays + " days must be entered as an adjustment by the manager");
        }
    }

    private void checkDuplicates(long customerId, PaymentInput in, StoredFile file, Long exceptId) {
        Map<String, Object> dup = duplicate(customerId, in, file, exceptId);
        if (dup != null) {
            if ("VERIFIED".equals(dup.get("status")) && "REFERENCE".equals(dup.get("kind"))) {
                throw conflict("Reference " + in.referenceNo() + " is already verified on " + dup.get("payment_no") + ".");
            }
            throw new DuplicateWarningException((String) dup.get("message"), (String) dup.get("payment_no"));
        }
    }

    private Long findDuplicate(long customerId, PaymentInput in, StoredFile file, Long exceptId) {
        Map<String, Object> dup = duplicate(customerId, in, file, exceptId);
        if (dup != null && "VERIFIED".equals(dup.get("status")) && "REFERENCE".equals(dup.get("kind"))) {
            throw conflict("Reference " + in.referenceNo() + " is already verified on " + dup.get("payment_no") + ".");
        }
        return dup == null ? null : ((Number) dup.get("id")).longValue();
    }

    /** Same reference, same screenshot, or same cash amount on the same day for the same customer. */
    private Map<String, Object> duplicate(long customerId, PaymentInput in, StoredFile file, Long exceptId) {
        long except = exceptId == null ? -1 : exceptId;
        if (in.referenceNo() != null && !in.referenceNo().isBlank()) {
            List<Map<String, Object>> r = jdbc.queryForList("""
                    SELECT id, payment_no, status FROM payments
                     WHERE method = ? AND reference_norm = upper(regexp_replace(?, '\\s', '', 'g')) AND id <> ?
                       AND status NOT IN ('REJECTED','CANCELLED','DUPLICATE_PAYMENT')
                     ORDER BY (status = 'VERIFIED') DESC LIMIT 1
                    """, in.method(), in.referenceNo(), except);
            if (!r.isEmpty()) {
                Map<String, Object> m = new HashMap<>(r.get(0));
                m.put("kind", "REFERENCE");
                m.put("message", "Reference " + in.referenceNo() + " is already on payment " + m.get("payment_no") + ".");
                return m;
            }
        }
        if (file != null) {
            List<Map<String, Object>> r = jdbc.queryForList("""
                    SELECT p.id, p.payment_no, p.status FROM payment_proofs pp JOIN payments p ON p.id = pp.payment_id
                     WHERE pp.sha256 = ? AND p.id <> ? LIMIT 1
                    """, file.sha256(), except);
            if (!r.isEmpty()) {
                Map<String, Object> m = new HashMap<>(r.get(0));
                m.put("kind", "PROOF");
                m.put("message", "This exact screenshot is already attached to " + m.get("payment_no") + ".");
                return m;
            }
        }
        if ("CASH".equals(in.method())) {
            List<Map<String, Object>> r = jdbc.queryForList("""
                    SELECT id, payment_no, status FROM payments
                     WHERE customer_id = ? AND method = 'CASH' AND amount = ? AND payment_date = ? AND id <> ?
                       AND status NOT IN ('REJECTED','CANCELLED','DUPLICATE_PAYMENT') LIMIT 1
                    """, customerId, in.amount(), Date.valueOf(in.paymentDate()), except);
            if (!r.isEmpty()) {
                Map<String, Object> m = new HashMap<>(r.get(0));
                m.put("kind", "CASH");
                m.put("message", "A cash payment of the same amount on the same day already exists (" + m.get("payment_no")
                        + "). If the customer has an open claim, record against it instead.");
                return m;
            }
        }
        return null;
    }

    private StoredFile storeOptional(MultipartFile f, String folder) {
        if (f == null || f.isEmpty()) return null;
        StoredFile file = storage.store(folder, f);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.discard(file.storageKey());
            }
        });
        return file;
    }

    private void saveProof(StoredFile f, long paymentId, Long submissionId, Long entryId, long actorId) {
        jdbc.update("""
                INSERT INTO payment_proofs (payment_id, submission_id, cashier_entry_id, storage_key, original_file_name,
                                            content_type, size_bytes, sha256, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, paymentId, submissionId, entryId, f.storageKey(), f.originalName(), f.contentType(), f.sizeBytes(),
                f.sha256(), actorId);
    }

    private void history(long id, String action, String from, String to, BigDecimal amount, String evidence,
                         String remarks, long actorId) {
        jdbc.update("""
                INSERT INTO payment_verifications (payment_id, action, from_status, to_status, verified_amount,
                                                   evidence_checked, remarks, acted_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, action, from, to, amount, blankToNull(evidence), remarks, actorId);
    }

    private Map<String, Object> lock(long id) {
        return jdbc.queryForList("""
                SELECT id, customer_id, status, method, submission_id, cashier_entry_id FROM payments WHERE id = ? FOR UPDATE
                """, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    private static Map<String, Object> vars(PaymentRow r) {
        Map<String, Object> v = new HashMap<>();
        v.put("paymentNo", r.paymentNo());
        v.put("customerName", r.customerName());
        v.put("amount", inr(r.amount()));
        v.put("method", r.method());
        return v;
    }

    private static String label(String status) {
        return status.toLowerCase().replace('_', ' ');
    }

    static String inr(BigDecimal v) {
        return java.text.NumberFormat.getCurrencyInstance(java.util.Locale.of("en", "IN")).format(v);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
