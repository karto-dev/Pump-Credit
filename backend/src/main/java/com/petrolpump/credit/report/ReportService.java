package com.petrolpump.credit.report;

import com.petrolpump.credit.credit.CreditService;
import com.petrolpump.credit.credit.CreditService.Ageing;
import com.petrolpump.credit.report.Report.Column;
import com.petrolpump.credit.report.Report.Item;
import com.petrolpump.credit.report.Report.Kind;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.petrolpump.credit.report.Report.col;
import static com.petrolpump.credit.report.Report.item;

/**
 * Read-only reporting. Every figure that says "outstanding" comes from the ledger; unverified claims,
 * pending approvals and disputes are shown beside it, never netted into it. Day boundaries are IST.
 */
@Service
public class ReportService {

    /** [from, to] as IST calendar days -> half-open timestamptz range. */
    private static final String IN_RANGE = "%1$s >= (?::date)::timestamp AT TIME ZONE 'Asia/Kolkata' "
            + "AND %1$s < (?::date + 1)::timestamp AT TIME ZONE 'Asia/Kolkata'";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public record StaffDashboard(BigDecimal totalOutstanding, long customersWithBalance, long customersOverLimit,
                                 BigDecimal overdueAmount, long overdueCustomers, BigDecimal salesToday, long fillsToday,
                                 BigDecimal salesThisMonth, BigDecimal verifiedThisMonth, BigDecimal verifiedToday,
                                 long pendingVerification, BigDecimal pendingVerificationAmount, long cashToCollect,
                                 BigDecimal unverifiedClaims, long heldIndents, long activeUsers, long activeExceptions) {}

    private final JdbcTemplate jdbc;
    private final CreditService credit;

    public ReportService(JdbcTemplate jdbc, CreditService credit) {
        this.jdbc = jdbc;
        this.credit = credit;
    }

    // ---------------------------------------------------------------- statement of account

    public Report statement(long customerId, LocalDate from, LocalDate to) {
        Map<String, Object> c = jdbc.queryForList("""
                SELECT customer_code, name, business_name, phone, address, city, gstin, credit_limit, credit_period_days
                  FROM customers WHERE id = ?
                """, customerId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        BigDecimal opening = jdbc.queryForObject("""
                SELECT COALESCE(SUM(debit) - SUM(credit), 0) FROM ledger_entries
                 WHERE customer_id = ? AND entry_at < (?::date)::timestamp AT TIME ZONE 'Asia/Kolkata'
                """, BigDecimal.class, customerId, from.toString());

        List<Column> cols = List.of(col("date", "Date", Kind.DATETIME), col("type", "Type", Kind.TEXT),
                col("ref", "Ref", Kind.TEXT), col("particulars", "Particulars", Kind.TEXT),
                col("debit", "Debit", Kind.MONEY), col("credit", "Credit", Kind.MONEY), col("balance", "Balance", Kind.MONEY));
        List<List<Object>> rows = jdbc.query("""
                SELECT l.entry_at, l.entry_type, COALESCE(t.txn_no, p.payment_no, a.adjustment_no) AS ref, l.narration,
                       l.debit, l.credit,
                       ? + SUM(l.debit - l.credit) OVER (ORDER BY l.entry_at, l.id) AS balance
                  FROM ledger_entries l
                  LEFT JOIN credit_transactions t ON t.id = l.credit_transaction_id
                  LEFT JOIN payments p ON p.id = l.payment_id
                  LEFT JOIN adjustments a ON a.id = l.adjustment_id
                 WHERE l.customer_id = ? AND %s
                 ORDER BY l.entry_at, l.id
                """.formatted(IN_RANGE.formatted("l.entry_at")),
                (rs, i) -> Arrays.<Object>asList(rs.getObject(1, OffsetDateTime.class), typeLabel(rs.getString(2)),
                        rs.getString(3), particulars(rs.getString(3), rs.getString(4)), zeroToNull(rs.getBigDecimal(5)), zeroToNull(rs.getBigDecimal(6)),
                        rs.getBigDecimal(7)),
                opening, customerId, from.toString(), to.toString());

        BigDecimal debits = sum(rows, 4);
        BigDecimal credits = sum(rows, 5);
        BigDecimal closing = opening.add(debits).subtract(credits);
        BigDecimal unverified = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM payments
                 WHERE customer_id = ? AND status IN ('AWAITING_CASHIER_ENTRY','AWAITING_VERIFICATION','ADDITIONAL_PROOF_REQUIRED')
                """, BigDecimal.class, customerId);
        Ageing age = credit.ageing(customerId).getFirst();

        List<Item> summary = new ArrayList<>(List.of(
                item("Opening balance (" + DAY.format(from) + ")", opening, Kind.MONEY),
                item("Fuel and debits", debits, Kind.MONEY),
                item("Verified payments and credits", credits, Kind.MONEY),
                item("Closing balance (" + DAY.format(to) + ")", closing, Kind.MONEY),
                item("Outstanding today", age.outstanding(), Kind.MONEY),
                item("Overdue (past " + c.get("credit_period_days") + " days)", age.overdue(), Kind.MONEY),
                item("Credit limit", c.get("credit_limit"), Kind.MONEY)));
        if (unverified.signum() > 0) summary.add(item("Payments under verification (not deducted)", unverified, Kind.MONEY));

        List<String> sub = new ArrayList<>();
        sub.add("%s (%s)%s".formatted(c.get("name"), c.get("customer_code"),
                c.get("business_name") == null ? "" : " - " + c.get("business_name")));
        String addr = join(c.get("address"), c.get("city"), c.get("phone") == null ? null : "Ph " + c.get("phone"),
                c.get("gstin") == null ? null : "GSTIN " + c.get("gstin"));
        if (!addr.isBlank()) sub.add(addr);
        sub.add("Period " + DAY.format(from) + " to " + DAY.format(to));
        List<Object> totals = Arrays.asList("Total", null, null, null, debits, credits, closing);
        return new Report(pumpName() + " - Statement of account", sub, summary, cols, rows, totals);
    }

    // ---------------------------------------------------------------- outstanding and ageing

    public Report outstanding(boolean onlyWithBalance, boolean overdueOnly, String status) {
        Map<Long, Ageing> ageing = credit.ageing(null).stream().collect(Collectors.toMap(Ageing::customerId, Function.identity()));
        List<Column> cols = List.of(col("code", "Code", Kind.TEXT), col("customer", "Customer", Kind.TEXT),
                col("phone", "Phone", Kind.TEXT), col("status", "Status", Kind.TEXT),
                col("limit", "Limit", Kind.MONEY), col("outstanding", "Outstanding", Kind.MONEY),
                col("available", "Available", Kind.MONEY), col("util", "Used", Kind.PERCENT),
                col("d30", "0-30 days", Kind.MONEY), col("d60", "31-60", Kind.MONEY), col("d90", "61-90", Kind.MONEY),
                col("d90p", "90+", Kind.MONEY), col("overdue", "Overdue", Kind.MONEY),
                col("oldest", "Oldest unpaid", Kind.DATE), col("lastPayment", "Last payment", Kind.DATE),
                col("unverified", "Unverified claims", Kind.MONEY));
        List<List<Object>> rows = new ArrayList<>();
        jdbc.query("""
                SELECT c.id, c.customer_code, c.name, c.phone, c.status, c.credit_limit + b.active_exception_limit AS total_limit,
                       b.available_credit, b.unverified_payment_claims,
                       (SELECT MAX(p.payment_date) FROM payments p WHERE p.customer_id = c.id AND p.status = 'VERIFIED') AS last_payment
                  FROM customers c JOIN v_customer_balances b ON b.customer_id = c.id
                 WHERE (?::text IS NULL OR c.status = ?)
                 ORDER BY c.name
                """, (ResultSet rs) -> {
                    Ageing a = ageing.get(rs.getLong(1));
                    if (onlyWithBalance && a.outstanding().signum() == 0) return;
                    if (overdueOnly && a.overdue().signum() <= 0) return;
                    BigDecimal limit = rs.getBigDecimal(6);
                    Integer util = limit.signum() > 0
                            ? a.outstanding().multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.HALF_UP).intValue() : null;
                    rows.add(Arrays.asList(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), limit,
                            a.outstanding(), rs.getBigDecimal(7), util, a.upTo30(), a.days31to60(), a.days61to90(),
                            a.over90(), a.overdue(), a.oldestUnpaidAt(), rs.getObject(9, LocalDate.class), rs.getBigDecimal(8)));
                }, status, status);
        rows.sort((x, y) -> ((BigDecimal) y.get(5)).compareTo((BigDecimal) x.get(5)));
        List<Object> totals = Report.totalsOf(cols, rows);
        BigDecimal total = (BigDecimal) totals.get(5);
        BigDecimal overdue = (BigDecimal) totals.get(12);
        List<Item> summary = List.of(
                item("Customers", rows.size(), Kind.INT),
                item("Total outstanding", total, Kind.MONEY),
                item("Overdue", overdue, Kind.MONEY),
                item("Over credit limit", rows.stream().filter(r -> ((BigDecimal) r.get(6)).signum() < 0).count(), Kind.INT));
        return new Report(pumpName() + " - Outstanding and ageing",
                List.of("As on " + DAY.format(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))
                        + ". Ageing assumes payments clear the oldest dues first."),
                summary, cols, rows, totals);
    }

    // ---------------------------------------------------------------- sales

    public Report sales(LocalDate from, LocalDate to, String groupBy, Long customerId) {
        String range = IN_RANGE.formatted("t.supplied_at");
        String period = "Period " + DAY.format(from) + " to " + DAY.format(to) + ". Reversed sales excluded.";
        List<Object> args = new ArrayList<>(List.of(from.toString(), to.toString()));
        String customer = "";
        if (customerId != null) { customer = " AND t.customer_id = ?"; args.add(customerId); }

        if ("detail".equals(groupBy)) {
            List<Column> cols = List.of(col("date", "Supplied", Kind.DATETIME), col("txn", "Txn", Kind.TEXT),
                    col("customer", "Customer", Kind.TEXT), col("vehicle", "Vehicle", Kind.TEXT), col("fuel", "Fuel", Kind.TEXT),
                    col("qty", "Litres", Kind.QTY), col("rate", "Rate", Kind.MONEY), col("amount", "Amount", Kind.MONEY),
                    col("cashier", "Cashier", Kind.TEXT), col("approval", "Approval", Kind.TEXT));
            List<List<Object>> rows = jdbc.query("""
                    SELECT t.supplied_at, t.txn_no, c.name, v.vehicle_number, t.fuel_type, t.quantity, t.rate_per_litre,
                           t.amount, u.full_name, t.approval_status
                      FROM credit_transactions t JOIN customers c ON c.id = t.customer_id
                      JOIN vehicles v ON v.id = t.vehicle_id JOIN users u ON u.id = t.cashier_id
                     WHERE t.status = 'POSTED' AND %s%s ORDER BY t.supplied_at
                    """.formatted(range, customer), (rs, i) -> Arrays.<Object>asList(rs.getObject(1, OffsetDateTime.class),
                    rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6),
                    rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9), typeLabel(rs.getString(10))), args.toArray());
            List<Object> totals = Report.totalsOf(cols, rows);
            totals.set(6, null);
            return new Report(pumpName() + " - Credit sales register", List.of(period), salesSummary(rows, 5, 7, 4),
                    cols, rows, totals);
        }

        String key = switch (groupBy == null ? "day" : groupBy) {
            case "customer" -> "c.name";
            case "vehicle" -> "v.vehicle_number || ' (' || c.name || ')'";
            case "cashier" -> "u.full_name";
            case "fuel" -> "t.fuel_type";
            default -> "to_char(t.supplied_at AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD')";
        };
        String keyHeader = switch (groupBy == null ? "day" : groupBy) {
            case "customer" -> "Customer";
            case "vehicle" -> "Vehicle";
            case "cashier" -> "Cashier";
            case "fuel" -> "Fuel";
            default -> "Date";
        };
        boolean byDay = keyHeader.equals("Date");
        List<Column> cols = List.of(col(byDay ? "date" : "customer", keyHeader, byDay ? Kind.DATE : Kind.TEXT),
                col("fills", "Fills", Kind.INT), col("diesel", "Diesel L", Kind.QTY), col("petrol", "Petrol L", Kind.QTY),
                col("litres", "Total L", Kind.QTY), col("amount", "Amount", Kind.MONEY));
        List<List<Object>> rows = jdbc.query("""
                SELECT %s AS k, COUNT(*),
                       COALESCE(SUM(t.quantity) FILTER (WHERE t.fuel_type = 'DIESEL'), 0),
                       COALESCE(SUM(t.quantity) FILTER (WHERE t.fuel_type = 'PETROL'), 0),
                       SUM(t.quantity), SUM(t.amount)
                  FROM credit_transactions t JOIN customers c ON c.id = t.customer_id
                  JOIN vehicles v ON v.id = t.vehicle_id JOIN users u ON u.id = t.cashier_id
                 WHERE t.status = 'POSTED' AND %s%s
                 GROUP BY 1 ORDER BY %s
                """.formatted(key, range, customer, byDay ? "1" : "6 DESC"),
                (rs, i) -> Arrays.<Object>asList(byDay ? LocalDate.parse(rs.getString(1)) : rs.getString(1), rs.getLong(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)), args.toArray());
        List<Object> totals = Report.totalsOf(cols, rows);
        List<Item> summary = List.of(item("Fills", totals.get(1), Kind.INT), item("Diesel", totals.get(2), Kind.QTY),
                item("Petrol", totals.get(3), Kind.QTY), item("Amount", totals.get(5), Kind.MONEY));
        return new Report(pumpName() + " - Credit sales by " + keyHeader.toLowerCase(), List.of(period), summary, cols, rows, totals);
    }

    private static List<Item> salesSummary(List<List<Object>> rows, int qtyCol, int amountCol, int fuelCol) {
        BigDecimal diesel = BigDecimal.ZERO, petrol = BigDecimal.ZERO, amount = BigDecimal.ZERO;
        for (List<Object> r : rows) {
            if ("DIESEL".equals(r.get(fuelCol))) diesel = diesel.add((BigDecimal) r.get(qtyCol));
            else petrol = petrol.add((BigDecimal) r.get(qtyCol));
            amount = amount.add((BigDecimal) r.get(amountCol));
        }
        return List.of(item("Fills", rows.size(), Kind.INT), item("Diesel", diesel, Kind.QTY),
                item("Petrol", petrol, Kind.QTY), item("Amount", amount, Kind.MONEY));
    }

    // ---------------------------------------------------------------- collections (verified recoveries)

    public Report collections(LocalDate from, LocalDate to, Long customerId) {
        List<Object> args = new ArrayList<>(List.of(from.toString(), to.toString()));
        String customer = "";
        if (customerId != null) { customer = " AND p.customer_id = ?"; args.add(customerId); }
        List<Column> cols = List.of(col("verifiedAt", "Verified", Kind.DATETIME), col("payment", "Payment", Kind.TEXT),
                col("customer", "Customer", Kind.TEXT), col("method", "Method", Kind.TEXT), col("ref", "Reference", Kind.TEXT),
                col("paidOn", "Paid on", Kind.DATE), col("amount", "Amount", Kind.MONEY),
                col("verified", "Verified amount", Kind.MONEY), col("via", "Entered via", Kind.TEXT),
                col("verifiedBy", "Verified by", Kind.TEXT));
        List<List<Object>> rows = jdbc.query("""
                SELECT p.verified_at, p.payment_no, c.name, p.method, p.reference_no, p.payment_date, p.amount,
                       p.verified_amount,
                       CASE WHEN p.submission_id IS NOT NULL AND p.cashier_entry_id IS NOT NULL THEN 'Claim + cashier'
                            WHEN p.submission_id IS NOT NULL THEN 'Customer claim' ELSE 'Cashier' END,
                       u.full_name
                  FROM payments p JOIN customers c ON c.id = p.customer_id JOIN users u ON u.id = p.verified_by
                 WHERE p.status = 'VERIFIED' AND %s%s ORDER BY p.verified_at
                """.formatted(IN_RANGE.formatted("p.verified_at"), customer),
                (rs, i) -> Arrays.<Object>asList(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, LocalDate.class), rs.getBigDecimal(7),
                        rs.getBigDecimal(8), rs.getString(9), rs.getString(10)), args.toArray());
        Map<String, BigDecimal> byMethod = new java.util.TreeMap<>();
        for (List<Object> r : rows) byMethod.merge((String) r.get(3), (BigDecimal) r.get(7), BigDecimal::add);
        List<Item> summary = new ArrayList<>();
        summary.add(item("Payments", rows.size(), Kind.INT));
        summary.add(item("Total recovered", sum(rows, 7), Kind.MONEY));
        byMethod.forEach((m, v) -> summary.add(item(m, v, Kind.MONEY)));
        return new Report(pumpName() + " - Verified recoveries",
                List.of("Verified between " + DAY.format(from) + " and " + DAY.format(to)), summary, cols, rows,
                Report.totalsOf(cols, rows));
    }

    // ---------------------------------------------------------------- cashier day book

    public Report cashiers(LocalDate from, LocalDate to) {
        List<Column> cols = List.of(col("date", "Date", Kind.DATE), col("cashier", "Cashier", Kind.TEXT),
                col("fills", "Fills", Kind.INT), col("litres", "Litres", Kind.QTY), col("sales", "Credit sales", Kind.MONEY),
                col("entries", "Payments recorded", Kind.INT), col("received", "Amount received", Kind.MONEY),
                col("cash", "of which cash", Kind.MONEY));
        List<List<Object>> rows = jdbc.query("""
                WITH s AS (
                    SELECT (t.supplied_at AT TIME ZONE 'Asia/Kolkata')::date AS day, t.cashier_id AS uid,
                           COUNT(*) AS fills, SUM(t.quantity) AS litres, SUM(t.amount) AS amount
                      FROM credit_transactions t WHERE t.status = 'POSTED' AND %s GROUP BY 1, 2
                ), e AS (
                    SELECT (e.recorded_at AT TIME ZONE 'Asia/Kolkata')::date AS day, e.recorded_by AS uid,
                           COUNT(*) AS entries, SUM(e.amount) AS received,
                           COALESCE(SUM(e.amount) FILTER (WHERE e.method = 'CASH'), 0) AS cash
                      FROM cashier_payment_entries e WHERE %s GROUP BY 1, 2
                )
                SELECT COALESCE(s.day, e.day), u.full_name, COALESCE(s.fills, 0), COALESCE(s.litres, 0),
                       COALESCE(s.amount, 0), COALESCE(e.entries, 0), COALESCE(e.received, 0), COALESCE(e.cash, 0)
                  FROM s FULL JOIN e ON e.day = s.day AND e.uid = s.uid
                  JOIN users u ON u.id = COALESCE(s.uid, e.uid)
                 ORDER BY 1, 2
                """.formatted(IN_RANGE.formatted("t.supplied_at"), IN_RANGE.formatted("e.recorded_at")),
                (rs, i) -> Arrays.<Object>asList(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getLong(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getLong(6), rs.getBigDecimal(7), rs.getBigDecimal(8)),
                from.toString(), to.toString(), from.toString(), to.toString());
        List<Object> totals = Report.totalsOf(cols, rows);
        List<Item> summary = List.of(item("Credit sales", totals.get(4), Kind.MONEY),
                item("Payments received", totals.get(6), Kind.MONEY), item("Cash to hand over", totals.get(7), Kind.MONEY));
        return new Report(pumpName() + " - Cashier day book",
                List.of("Period " + DAY.format(from) + " to " + DAY.format(to)
                        + ". Received amounts are as recorded by the cashier, before verification."),
                summary, cols, rows, totals);
    }

    // ---------------------------------------------------------------- staff dashboard

    public StaffDashboard staffDashboard() {
        List<Ageing> ageing = credit.ageing(null);
        BigDecimal overdue = ageing.stream().map(Ageing::overdue).reduce(BigDecimal.ZERO, BigDecimal::add);
        long overdueCustomers = ageing.stream().filter(a -> a.overdue().signum() > 0).count();
        return jdbc.queryForObject("""
                SELECT (SELECT COALESCE(SUM(confirmed_outstanding) FILTER (WHERE confirmed_outstanding > 0), 0) FROM v_customer_balances),
                       (SELECT COUNT(*) FROM v_customer_balances WHERE confirmed_outstanding > 0),
                       (SELECT COUNT(*) FROM v_customer_balances WHERE available_credit < 0),
                       (SELECT COALESCE(SUM(amount), 0) FROM credit_transactions
                         WHERE status = 'POSTED' AND supplied_at >= (now() AT TIME ZONE 'Asia/Kolkata')::date::timestamp AT TIME ZONE 'Asia/Kolkata'),
                       (SELECT COUNT(*) FROM credit_transactions
                         WHERE status = 'POSTED' AND supplied_at >= (now() AT TIME ZONE 'Asia/Kolkata')::date::timestamp AT TIME ZONE 'Asia/Kolkata'),
                       (SELECT COALESCE(SUM(amount), 0) FROM credit_transactions
                         WHERE status = 'POSTED' AND supplied_at >= date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'),
                       (SELECT COALESCE(SUM(verified_amount), 0) FROM payments
                         WHERE status = 'VERIFIED' AND verified_at >= date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'),
                       (SELECT COALESCE(SUM(verified_amount), 0) FROM payments
                         WHERE status = 'VERIFIED' AND verified_at >= (now() AT TIME ZONE 'Asia/Kolkata')::date::timestamp AT TIME ZONE 'Asia/Kolkata'),
                       (SELECT COUNT(*) FROM payments WHERE status = 'AWAITING_VERIFICATION'),
                       (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status = 'AWAITING_VERIFICATION'),
                       (SELECT COUNT(*) FROM payments WHERE status = 'AWAITING_CASHIER_ENTRY'),
                       (SELECT COALESCE(SUM(unverified_payment_claims), 0) FROM v_customer_balances),
                       (SELECT COUNT(*) FROM indents WHERE status = 'ON_HOLD_CREDIT_LIMIT'),
                       (SELECT COUNT(*) FROM users WHERE is_active),
                       (SELECT COUNT(*) FROM credit_limit_exceptions WHERE status = 'ACTIVE' AND now() BETWEEN valid_from AND valid_until)
                """, (rs, i) -> new StaffDashboard(rs.getBigDecimal(1), rs.getLong(2), rs.getLong(3), overdue, overdueCustomers,
                rs.getBigDecimal(4), rs.getLong(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                rs.getLong(9), rs.getBigDecimal(10), rs.getLong(11), rs.getBigDecimal(12), rs.getLong(13), rs.getLong(14),
                rs.getLong(15)));
    }

    // ---------------------------------------------------------------- helpers

    private String pumpName() {
        return credit.setting("PUMP_NAME", "Petrol Pump");
    }

    private static String typeLabel(String code) {
        return switch (code) {
            case "OPENING_BALANCE" -> "Opening";
            case "CREDIT_SALE" -> "Fuel";
            case "PAYMENT" -> "Payment";
            case "DEBIT_ADJUSTMENT" -> "Debit adj.";
            case "CREDIT_ADJUSTMENT" -> "Credit adj.";
            default -> code.charAt(0) + code.substring(1).toLowerCase().replace('_', ' ');
        };
    }

    private static BigDecimal sum(List<List<Object>> rows, int col) {
        return rows.stream().map(r -> r.get(col)).filter(BigDecimal.class::isInstance).map(BigDecimal.class::cast)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Ledger narrations start with the document number, which the Ref column already shows. */
    private static String particulars(String ref, String narration) {
        return ref != null && narration.startsWith(ref + " ") ? narration.substring(ref.length() + 1) : narration;
    }

    private static BigDecimal zeroToNull(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : v;
    }

    private static String join(Object... parts) {
        return Arrays.stream(parts).filter(p -> p != null && !p.toString().isBlank()).map(Object::toString)
                .collect(Collectors.joining(", "));
    }
}
