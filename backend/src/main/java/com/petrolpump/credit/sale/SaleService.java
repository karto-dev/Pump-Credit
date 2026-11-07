package com.petrolpump.credit.sale;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.common.DuplicateWarningException;
import com.petrolpump.credit.credit.CreditService;
import com.petrolpump.credit.credit.CreditService.CreditPosition;
import com.petrolpump.credit.indent.IndentService;
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
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SaleService {

    public record SaleRequest(long customerId, Long indentId, long vehicleId, String fuelType, BigDecimal quantity,
                              BigDecimal ratePerLitre, OffsetDateTime suppliedAt, String physicalBillNo, String remarks,
                              boolean confirmDuplicate) {}

    public record SaleRow(long id, String txnNo, long customerId, String customerName, String customerCode,
                          long vehicleId, String vehicleNumber, String fuelType, BigDecimal quantity,
                          BigDecimal ratePerLitre, BigDecimal amount, OffsetDateTime suppliedAt, String physicalBillNo,
                          String remarks, Long indentId, String indentNo, long cashierId, String cashierName,
                          String approvalStatus, OffsetDateTime approvalUpdatedAt, String status, boolean hasBill,
                          Long disputeId, String disputeNo, String disputeStatus, OffsetDateTime createdAt) {}

    public record Filter(LocalDate from, LocalDate to, Long customerId, String q, String fuelType, Long cashierId,
                         String approvalStatus) {}

    public record Bill(byte[] bytes, String contentType) {}

    private static final String SELECT = """
            SELECT t.id, t.txn_no, t.customer_id, c.name AS customer_name, c.customer_code, t.vehicle_id,
                   v.vehicle_number, t.fuel_type, t.quantity, t.rate_per_litre, t.amount, t.supplied_at,
                   t.physical_bill_no, t.remarks, t.indent_id, i.indent_no, t.cashier_id, u.full_name AS cashier_name,
                   t.approval_status, t.approval_updated_at, t.status,
                   EXISTS (SELECT 1 FROM bill_attachments b WHERE b.credit_transaction_id = t.id) AS has_bill,
                   d.id AS dispute_id, d.dispute_no, d.status AS dispute_status, t.created_at
              FROM credit_transactions t
              JOIN customers c ON c.id = t.customer_id
              JOIN vehicles v ON v.id = t.vehicle_id
              LEFT JOIN LATERAL (SELECT id, dispute_no, status FROM disputes
                                  WHERE credit_transaction_id = t.id ORDER BY raised_at DESC LIMIT 1) d ON true
              JOIN users u ON u.id = t.cashier_id
              LEFT JOIN indents i ON i.id = t.indent_id
            """;
    private static final DataClassRowMapper<SaleRow> MAPPER = new DataClassRowMapper<>(SaleRow.class);

    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final LedgerService ledger;
    private final IndentService indents;
    private final CreditService credit;
    private final NotificationService notify;
    private final AuditService audit;

    public SaleService(JdbcTemplate jdbc, FileStorage storage, LedgerService ledger, IndentService indents,
                       CreditService credit, NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.ledger = ledger;
        this.indents = indents;
        this.credit = credit;
        this.notify = notify;
        this.audit = audit;
    }

    public List<SaleRow> list(Filter f) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (f.from() != null) { sql.append(" AND t.supplied_at >= ?::date"); args.add(f.from().toString()); }
        if (f.to() != null) { sql.append(" AND t.supplied_at < ?::date + 1"); args.add(f.to().toString()); }
        if (f.customerId() != null) { sql.append(" AND t.customer_id = ?"); args.add(f.customerId()); }
        if (f.fuelType() != null && !f.fuelType().isBlank()) { sql.append(" AND t.fuel_type = ?"); args.add(f.fuelType()); }
        if (f.cashierId() != null) { sql.append(" AND t.cashier_id = ?"); args.add(f.cashierId()); }
        if (f.approvalStatus() != null && !f.approvalStatus().isBlank()) {
            sql.append(" AND t.approval_status = ?");
            args.add(f.approvalStatus());
        }
        if (f.q() != null && !f.q().isBlank()) {
            String like = "%" + f.q().trim().toLowerCase() + "%";
            sql.append(" AND (lower(c.name) LIKE ? OR lower(t.txn_no) LIKE ? OR v.vehicle_number LIKE ? OR lower(coalesce(t.physical_bill_no, '')) LIKE ?)");
            args.addAll(List.of(like, like, "%" + f.q().replaceAll("[\\s-]", "").toUpperCase() + "%", like));
        }
        sql.append(" ORDER BY t.supplied_at DESC LIMIT 1000");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public SaleRow get(long id) {
        return jdbc.query(SELECT + " WHERE t.id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
    }

    public Bill bill(long saleId) {
        return jdbc.query("""
                        SELECT storage_key, content_type FROM bill_attachments WHERE credit_transaction_id = ?
                         ORDER BY uploaded_at DESC LIMIT 1
                        """, (rs, i) -> new Bill(storage.read(rs.getString(1)), rs.getString(2)), saleId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No bill photo for this sale"));
    }

    /**
     * Records fuel already supplied: the sale, its bill photo, the approval trail and the ledger debit all
     * commit together. Customer approval is tracked separately and never holds the ledger entry back.
     */
    @Transactional
    public SaleRow create(SaleRequest req, MultipartFile billPhoto, CurrentUser actor) {
        StoredFile file = storage.store("bills", billPhoto);
        // If anything below fails the transaction rolls back - remove the file so nothing is orphaned.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.discard(file.storageKey());
            }
        });

        String customerStatus = jdbc.query("SELECT status FROM customers WHERE id = ? FOR UPDATE",
                (rs, i) -> rs.getString(1), req.customerId()).stream().findFirst()
                .orElseThrow(() -> bad("Customer not found"));
        if ("CLOSED".equals(customerStatus)) throw conflict("This credit account is closed");

        String vehicleNo = jdbc.query("SELECT vehicle_number FROM vehicles WHERE id = ? AND customer_id = ? AND is_active",
                        (rs, i) -> rs.getString(1), req.vehicleId(), req.customerId()).stream().findFirst()
                .orElseThrow(() -> bad("Vehicle is not an active vehicle of this customer"));

        BigDecimal amount = req.quantity().multiply(req.ratePerLitre()).setScale(2, RoundingMode.HALF_UP);
        OffsetDateTime suppliedAt = req.suppliedAt() != null ? req.suppliedAt() : OffsetDateTime.now();
        if (suppliedAt.isAfter(OffsetDateTime.now().plusMinutes(5))) throw bad("Supply time cannot be in the future");
        if (suppliedAt.isBefore(OffsetDateTime.now().minusDays(7))) {
            throw bad("Sales older than 7 days must be entered as an adjustment by the manager");
        }

        if (req.indentId() != null) {
            Map<String, Object> ind = jdbc.queryForList("""
                    SELECT customer_id, vehicle_id, fuel_type, status FROM indents WHERE id = ? FOR UPDATE
                    """, req.indentId()).stream().findFirst().orElseThrow(() -> bad("Indent not found"));
            if (!Objects.equals(((Number) ind.get("customer_id")).longValue(), req.customerId())) {
                throw bad("Indent belongs to a different customer");
            }
            if (!List.of("PENDING", "ACCEPTED").contains((String) ind.get("status"))) {
                throw conflict("Indent is " + ind.get("status") + " and cannot be filled");
            }
            if (((Number) ind.get("vehicle_id")).longValue() != req.vehicleId()) throw bad("Vehicle does not match the indent");
            if (!req.fuelType().equals(ind.get("fuel_type"))) throw bad("Fuel type does not match the indent");
        } else {
            // No indent means no limit check happened yet - do it now, before fuel goes out.
            if (!"ACTIVE".equals(customerStatus)) throw conflict("This credit account is " + customerStatus.toLowerCase());
            CreditPosition pos = credit.position(req.customerId());
            if (pos.headroom().subtract(amount).signum() < 0) {
                throw conflict("Amount exceeds available credit (" + inr(pos.headroom().max(BigDecimal.ZERO))
                        + "). Ask the customer to raise an indent so the admin can authorise it.");
            }
        }

        if (!req.confirmDuplicate()) checkDuplicates(req, amount, suppliedAt, file.sha256());

        Long id = jdbc.queryForObject("""
                INSERT INTO credit_transactions (indent_id, customer_id, vehicle_id, fuel_type, quantity, rate_per_litre,
                                                 amount, supplied_at, physical_bill_no, remarks, cashier_id,
                                                 approval_updated_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?) RETURNING id
                """, Long.class, req.indentId(), req.customerId(), req.vehicleId(), req.fuelType(), req.quantity(),
                req.ratePerLitre(), amount, Timestamp.from(suppliedAt.toInstant()), blankToNull(req.physicalBillNo()),
                blankToNull(req.remarks()), actor.id(), actor.id(), actor.id());
        jdbc.update("""
                INSERT INTO bill_attachments (credit_transaction_id, storage_key, original_file_name, content_type,
                                              size_bytes, sha256, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, file.storageKey(), file.originalName(), file.contentType(), file.sizeBytes(), file.sha256(), actor.id());
        jdbc.update("""
                INSERT INTO customer_approvals (credit_transaction_id, from_status, to_status, reason, acted_by)
                VALUES (?, NULL, 'AWAITING_CUSTOMER_APPROVAL', 'Bill uploaded by cashier', ?)
                """, id, actor.id());
        if (req.indentId() != null) indents.markCompleted(req.indentId(), actor.id());

        SaleRow sale = get(id);
        BigDecimal balance = ledger.post(new Posting(req.customerId(), EntryType.CREDIT_SALE, amount, BigDecimal.ZERO,
                id, null, null, "%s %s L %s @ %s - %s".formatted(sale.txnNo(), req.quantity().stripTrailingZeros().toPlainString(),
                req.fuelType(), req.ratePerLitre(), vehicleNo), actor.id()));
        audit.log(actor, "CREDIT_SALE_CREATED", "CREDIT_TRANSACTION", id, null, sale,
                req.confirmDuplicate() ? "Saved despite duplicate warning" : null);

        Map<String, Object> vars = new HashMap<>();
        vars.put("txnNo", sale.txnNo());
        vars.put("quantity", req.quantity().stripTrailingZeros().toPlainString());
        vars.put("fuelType", req.fuelType());
        vars.put("vehicleNo", vehicleNo);
        vars.put("amount", inr(amount));
        notify.toCustomer(req.customerId(), "FUEL_SUPPLIED", vars, "CREDIT_TRANSACTION", id);
        notify.toCustomer(req.customerId(), "BILL_UPLOADED", vars, "CREDIT_TRANSACTION", id);
        warnOnUtilisation(req.customerId(), balance);
        return sale;
    }

    private void checkDuplicates(SaleRequest req, BigDecimal amount, OffsetDateTime suppliedAt, String sha) {
        int window = Integer.parseInt(credit.setting("DUPLICATE_SALE_WINDOW_MIN", "15"));
        jdbc.query("""
                SELECT txn_no FROM credit_transactions
                 WHERE customer_id = ? AND vehicle_id = ? AND amount = ? AND status = 'POSTED'
                   AND supplied_at BETWEEN ?::timestamptz - make_interval(mins => ?) AND ?::timestamptz + make_interval(mins => ?)
                 LIMIT 1
                """, (rs, i) -> rs.getString(1), req.customerId(), req.vehicleId(), amount,
                suppliedAt.toString(), window, suppliedAt.toString(), window).stream().findFirst()
                .ifPresent(t -> { throw new DuplicateWarningException(
                        "A sale of the same amount for this vehicle was entered within " + window + " minutes (" + t + ").", t); });
        jdbc.query("""
                SELECT t.txn_no FROM bill_attachments b JOIN credit_transactions t ON t.id = b.credit_transaction_id
                 WHERE b.sha256 = ? LIMIT 1
                """, (rs, i) -> rs.getString(1), sha).stream().findFirst()
                .ifPresent(t -> { throw new DuplicateWarningException("This exact bill photo is already attached to " + t + ".", t); });
    }

    private void warnOnUtilisation(long customerId, BigDecimal balance) {
        CreditPosition pos = credit.position(customerId);
        if (pos.totalLimit().signum() <= 0) return;
        int pct = balance.multiply(BigDecimal.valueOf(100)).divide(pos.totalLimit(), 0, RoundingMode.HALF_UP).intValue();
        if (pct >= Integer.parseInt(credit.setting("CREDIT_LIMIT_WARN_PERCENT", "80"))) {
            notify.toCustomer(customerId, "CREDIT_LIMIT_APPROACHING",
                    Map.of("percent", pct, "available", inr(pos.totalLimit().subtract(balance))), "CUSTOMER", customerId);
        }
    }

    private static String inr(BigDecimal v) {
        return java.text.NumberFormat.getCurrencyInstance(java.util.Locale.of("en", "IN")).format(v);
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
