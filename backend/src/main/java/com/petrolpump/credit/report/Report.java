package com.petrolpump.credit.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Every report and statement has the same shape, so the UI renders them with one table component and
 * {@link ExportService} turns any of them into Excel or PDF.
 */
public record Report(String title, List<String> subtitle, List<Item> summary, List<Column> columns,
                     List<List<Object>> rows, List<Object> totals) {

    public enum Kind { TEXT, MONEY, QTY, INT, PERCENT, DATE, DATETIME }

    public record Column(String key, String header, Kind kind) {}

    public record Item(String label, Object value, Kind kind) {}

    public static Column col(String key, String header, Kind kind) {
        return new Column(key, header, kind);
    }

    public static Item item(String label, Object value, Kind kind) {
        return new Item(label, value, kind);
    }

    /** Sums every MONEY / QTY / INT column; the first column carries the "Total" label. */
    public static List<Object> totalsOf(List<Column> columns, List<List<Object>> rows) {
        List<Object> totals = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            Kind k = columns.get(c).kind();
            if (k == Kind.MONEY || k == Kind.QTY || k == Kind.INT) {
                java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                for (List<Object> r : rows) {
                    if (r.get(c) instanceof Number n) sum = sum.add(new java.math.BigDecimal(n.toString()));
                }
                totals.add(sum);
            } else {
                totals.add(c == 0 ? "Total" : null);
            }
        }
        return totals;
    }
}
