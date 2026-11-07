package com.petrolpump.credit.customer;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.customer.CustomerDtos.CreateCustomerRequest;
import com.petrolpump.credit.customer.CustomerDtos.CustomerDetail;
import com.petrolpump.credit.customer.CustomerDtos.CustomerProfile;
import com.petrolpump.credit.customer.CustomerDtos.CustomerRow;
import com.petrolpump.credit.customer.CustomerDtos.StatusChangeRequest;
import com.petrolpump.credit.customer.CustomerDtos.UpdateCustomerRequest;
import com.petrolpump.credit.security.CurrentUser;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CustomerService {

    private static final String LIST_SQL = """
            SELECT c.id, c.customer_code, c.name, c.business_name, c.phone, c.city, c.status, c.credit_limit,
                   b.confirmed_outstanding, b.active_exception_limit, b.available_credit, b.unverified_payment_claims,
                   b.pending_approval_count, b.disputed_count,
                   (SELECT count(*) FROM vehicles v WHERE v.customer_id = c.id AND v.is_active) AS vehicle_count
              FROM customers c JOIN v_customer_balances b ON b.customer_id = c.id
             WHERE 1 = 1
            """;

    private static final String DETAIL_SQL = """
            SELECT c.id, c.customer_code, c.name, c.business_name, c.contact_person, c.phone, c.alternate_phone,
                   c.email, c.address, c.city, c.state, c.pincode, c.gstin, c.pan, c.credit_limit,
                   c.credit_period_days, c.opening_balance, c.opening_balance_date, c.status, c.version, c.created_at,
                   b.confirmed_outstanding, b.active_exception_limit, b.available_credit, b.unverified_payment_claims,
                   b.pending_approval_count, b.pending_approval_amount, b.disputed_count, b.disputed_amount
              FROM customers c JOIN v_customer_balances b ON b.customer_id = c.id
             WHERE c.id = ?
            """;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CustomerService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<CustomerRow> search(String q, String status, boolean overLimitOnly) {
        StringBuilder sql = new StringBuilder(LIST_SQL);
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            String vehicleLike = "%" + q.replaceAll("\\s", "").toUpperCase() + "%";
            sql.append("""
                     AND (lower(c.name) LIKE ? OR lower(coalesce(c.business_name, '')) LIKE ?
                          OR lower(c.customer_code) LIKE ? OR c.phone LIKE ?
                          OR EXISTS (SELECT 1 FROM vehicles v WHERE v.customer_id = c.id AND v.vehicle_number LIKE ?))
                    """);
            args.addAll(List.of(like, like, like, like, vehicleLike));
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND c.status = ?");
            args.add(status);
        }
        if (overLimitOnly) {
            sql.append(" AND b.available_credit < 0");
        }
        sql.append(" ORDER BY c.name LIMIT 500");
        return jdbc.query(sql.toString(), new DataClassRowMapper<>(CustomerRow.class), args.toArray());
    }

    public CustomerDetail get(long id) {
        return jdbc.query(DETAIL_SQL, new DataClassRowMapper<>(CustomerDetail.class), id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
    }

    @Transactional
    public CustomerDetail create(CreateCustomerRequest req, CurrentUser actor) {
        if (req.creditLimit().signum() > 0 && !actor.has("CREDIT_LIMIT_CHANGE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only users allowed to change credit limits can set a non-zero credit limit");
        }
        CustomerProfile p = normalise(req.profile());
        LocalDate obDate = req.openingBalanceDate() != null ? req.openingBalanceDate() : LocalDate.now();
        Long id = jdbc.queryForObject("""
                INSERT INTO customers (name, business_name, contact_person, phone, alternate_phone, email, address,
                                       city, state, pincode, gstin, pan, credit_period_days, credit_limit,
                                       opening_balance, opening_balance_date, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                p.name(), p.businessName(), p.contactPerson(), p.phone(), p.alternatePhone(), p.email(), p.address(),
                p.city(), p.state(), p.pincode(), p.gstin(), p.pan(), p.creditPeriodDays(), req.creditLimit(),
                req.openingBalance(), Date.valueOf(obDate), actor.id(), actor.id());

        if (req.creditLimit().signum() > 0) {
            jdbc.update("""
                    INSERT INTO credit_limit_changes (customer_id, old_limit, new_limit, reason, changed_by)
                    VALUES (?, 0, ?, 'Initial credit limit at customer creation', ?)
                    """, id, req.creditLimit(), actor.id());
        }
        BigDecimal ob = req.openingBalance();
        if (ob.signum() != 0) {
            // Opening balance is the first ledger line; positive = customer owes us, negative = advance held.
            jdbc.update("""
                    INSERT INTO ledger_entries (customer_id, entry_at, entry_type, debit, credit, running_balance,
                                                narration, created_by)
                    VALUES (?, ?::date, 'OPENING_BALANCE', ?, ?, ?, ?, ?)
                    """, id, Date.valueOf(obDate), ob.max(BigDecimal.ZERO), ob.negate().max(BigDecimal.ZERO), ob,
                    "Opening balance as on " + obDate, actor.id());
        }

        CustomerDetail created = get(id);
        audit.log(actor, "CUSTOMER_CREATED", "CUSTOMER", id, null, created, null);
        return created;
    }

    @Transactional
    public CustomerDetail update(long id, UpdateCustomerRequest req, CurrentUser actor) {
        CustomerDetail before = get(id);
        CustomerProfile p = normalise(req.profile());
        int rows = jdbc.update("""
                UPDATE customers SET name = ?, business_name = ?, contact_person = ?, phone = ?, alternate_phone = ?,
                       email = ?, address = ?, city = ?, state = ?, pincode = ?, gstin = ?, pan = ?,
                       credit_period_days = ?, updated_by = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """,
                p.name(), p.businessName(), p.contactPerson(), p.phone(), p.alternatePhone(), p.email(), p.address(),
                p.city(), p.state(), p.pincode(), p.gstin(), p.pan(), p.creditPeriodDays(), actor.id(),
                id, req.version());
        if (rows == 0) throw new OptimisticLockingFailureException("customer " + id);
        CustomerDetail after = get(id);
        audit.log(actor, "CUSTOMER_UPDATED", "CUSTOMER", id, profileOf(before), profileOf(after), null);
        return after;
    }

    @Transactional
    public CustomerDetail changeStatus(long id, StatusChangeRequest req, CurrentUser actor) {
        CustomerDetail before = get(id);
        if (before.status().equals(req.status())) return before;
        jdbc.update("UPDATE customers SET status = ?, updated_by = ?, version = version + 1 WHERE id = ?",
                req.status(), actor.id(), id);
        audit.log(actor, "CUSTOMER_STATUS_CHANGED", "CUSTOMER", id,
                Map.of("status", before.status()), Map.of("status", req.status()), req.reason());
        return get(id);
    }

    private static CustomerProfile normalise(CustomerProfile p) {
        return new CustomerProfile(p.name().trim(), blankToNull(p.businessName()), blankToNull(p.contactPerson()),
                p.phone(), blankToNull(p.alternatePhone()), blankToNull(p.email()), blankToNull(p.address()),
                blankToNull(p.city()), blankToNull(p.state()), blankToNull(p.pincode()),
                upperOrNull(p.gstin()), upperOrNull(p.pan()), p.creditPeriodDays());
    }

    private static Map<String, Object> profileOf(CustomerDetail d) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", d.name());
        m.put("businessName", d.businessName());
        m.put("contactPerson", d.contactPerson());
        m.put("phone", d.phone());
        m.put("alternatePhone", d.alternatePhone());
        m.put("email", d.email());
        m.put("address", d.address());
        m.put("city", d.city());
        m.put("state", d.state());
        m.put("pincode", d.pincode());
        m.put("gstin", d.gstin());
        m.put("pan", d.pan());
        m.put("creditPeriodDays", d.creditPeriodDays());
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upperOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }
}
