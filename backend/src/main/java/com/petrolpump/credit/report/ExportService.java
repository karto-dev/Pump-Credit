package com.petrolpump.credit.report;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.petrolpump.credit.report.Report.Column;
import com.petrolpump.credit.report.Report.Item;
import com.petrolpump.credit.report.Report.Kind;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Report -> .xlsx (Apache POI) or .pdf (OpenPDF). Dates DD-MM-YYYY and Indian digit grouping throughout. */
@Service
public class ExportService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    /** Excel has no locale-aware grouping, so lakh/crore grouping is spelled out with conditional sections. */
    private static final String XLSX_MONEY = "[>=10000000]##\\,##\\,##\\,##0.00;[>=100000]##\\,##\\,##0.00;##,##0.00";

    public byte[] xlsx(Report r) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheetName(r.title()));
            DataFormat df = wb.createDataFormat();
            var bold = wb.createFont();
            bold.setBold(true);
            var titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            CellStyle title = wb.createCellStyle();
            title.setFont(titleFont);
            CellStyle boldStyle = wb.createCellStyle();
            boldStyle.setFont(bold);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            Map<Kind, CellStyle> styles = new EnumMap<>(Kind.class);
            Map<Kind, CellStyle> totalStyles = new EnumMap<>(Kind.class);
            for (Kind k : Kind.values()) {
                for (Map<Kind, CellStyle> target : List.of(styles, totalStyles)) {
                    CellStyle s = wb.createCellStyle();
                    switch (k) {
                        case MONEY -> s.setDataFormat(df.getFormat(XLSX_MONEY));
                        case QTY -> s.setDataFormat(df.getFormat("#,##0.000"));
                        case INT -> s.setDataFormat(df.getFormat("0"));
                        case PERCENT -> s.setDataFormat(df.getFormat("0\"%\""));
                        case DATE -> s.setDataFormat(df.getFormat("dd-mm-yyyy"));
                        case DATETIME -> s.setDataFormat(df.getFormat("dd-mm-yyyy hh:mm"));
                        default -> { }
                    }
                    if (target == totalStyles) {
                        s.setFont(bold);
                        s.setBorderTop(BorderStyle.THIN);
                    }
                    target.put(k, s);
                }
            }

            int rowNo = 0;
            Cell t = sheet.createRow(rowNo++).createCell(0);
            t.setCellValue(r.title());
            t.setCellStyle(title);
            for (String line : r.subtitle()) sheet.createRow(rowNo++).createCell(0).setCellValue(line);
            if (!r.summary().isEmpty()) {
                rowNo++;
                for (Item it : r.summary()) {
                    Row row = sheet.createRow(rowNo++);
                    Cell label = row.createCell(0);
                    label.setCellValue(it.label());
                    label.setCellStyle(boldStyle);
                    write(row.createCell(1), it.value(), it.kind(), styles);
                }
            }
            rowNo++;
            int headerRow = rowNo;
            Row h = sheet.createRow(rowNo++);
            for (int c = 0; c < r.columns().size(); c++) {
                Cell cell = h.createCell(c);
                cell.setCellValue(r.columns().get(c).header());
                cell.setCellStyle(header);
            }
            for (List<Object> data : r.rows()) {
                Row row = sheet.createRow(rowNo++);
                for (int c = 0; c < r.columns().size(); c++) write(row.createCell(c), data.get(c), r.columns().get(c).kind(), styles);
            }
            if (r.totals() != null) {
                Row row = sheet.createRow(rowNo);
                for (int c = 0; c < r.columns().size(); c++) {
                    write(row.createCell(c), r.totals().get(c), r.columns().get(c).kind(), totalStyles);
                }
            }
            for (int c = 0; c < r.columns().size(); c++) sheet.setColumnWidth(c, width(r.columns().get(c)) * 256);
            sheet.createFreezePane(0, headerRow + 1);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build Excel file", e);
        }
    }

    public byte[] pdf(Report r) {
        boolean landscape = r.columns().size() > 7;
        Document doc = new Document(landscape ? PageSize.A4.rotate() : PageSize.A4, 24, 24, 28, 28);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        float size = r.columns().size() > 12 ? 6.5f : 8;   // the ageing report has 16 columns
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, size);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(90, 90, 90));
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, size);
        Font headFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, size, Color.WHITE);

        doc.add(new Paragraph(r.title(), titleFont));
        for (String line : r.subtitle()) doc.add(new Paragraph(line, small));
        if (!r.summary().isEmpty()) {
            PdfPTable sum = new PdfPTable(2);
            sum.setWidthPercentage(landscape ? 40 : 55);
            sum.setHorizontalAlignment(Element.ALIGN_LEFT);
            sum.setSpacingBefore(8);
            for (Item it : r.summary()) {
                sum.addCell(cell(it.label(), bold, Element.ALIGN_LEFT, null));
                sum.addCell(cell(text(it.value(), it.kind()), normal, align(it.kind()), null));
            }
            doc.add(sum);
        }

        PdfPTable table = new PdfPTable(r.columns().size());
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);
        table.setHeaderRows(1);
        table.setWidths(r.columns().stream().mapToInt(ExportService::width).map(w -> Math.max(w, 6)).toArray());
        Color green = new Color(25, 135, 84);
        for (Column c : r.columns()) table.addCell(cell(c.header(), headFont, align(c.kind()), green));
        Color stripe = new Color(244, 246, 245);
        int i = 0;
        for (List<Object> data : r.rows()) {
            Color bg = i++ % 2 == 1 ? stripe : null;
            for (int c = 0; c < r.columns().size(); c++) {
                Kind k = r.columns().get(c).kind();
                table.addCell(cell(text(data.get(c), k), normal, align(k), bg));
            }
        }
        if (r.totals() != null) {
            Color tbg = new Color(227, 241, 234);
            for (int c = 0; c < r.columns().size(); c++) {
                Kind k = r.columns().get(c).kind();
                table.addCell(cell(text(r.totals().get(c), k), bold, align(k), tbg));
            }
        }
        if (r.rows().isEmpty()) {
            PdfPCell none = cell("No records", small, Element.ALIGN_CENTER, null);
            none.setColspan(r.columns().size());
            table.addCell(none);
        }
        doc.add(table);
        doc.add(new Paragraph("Generated " + DAY_TIME.format(LocalDateTime.now(IST)) + " IST", small));
        doc.close();
        return out.toByteArray();
    }

    private static PdfPCell cell(String s, Font f, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(s == null ? "" : s, f));
        c.setHorizontalAlignment(align);
        c.setPadding(3.5f);
        c.setBorderColor(new Color(223, 229, 226));
        if (bg != null) c.setBackgroundColor(bg);
        return c;
    }

    private static int align(Kind k) {
        return switch (k) {
            case MONEY, QTY, INT, PERCENT -> Element.ALIGN_RIGHT;
            default -> Element.ALIGN_LEFT;
        };
    }

    private static void write(Cell cell, Object v, Kind k, Map<Kind, CellStyle> styles) {
        cell.setCellStyle(styles.get(k));
        if (v == null) return;
        switch (v) {
            case Number n when k != Kind.TEXT -> cell.setCellValue(n.doubleValue());
            case LocalDate d -> cell.setCellValue(d);
            case OffsetDateTime t -> cell.setCellValue(t.atZoneSameInstant(IST).toLocalDateTime());
            default -> cell.setCellValue(v.toString());
        }
    }

    /** Text form used by PDF cells (and the summary block). */
    static String text(Object v, Kind k) {
        if (v == null) return "";
        return switch (k) {
            case MONEY -> money(v);
            case QTY -> v instanceof Number n ? new DecimalFormat("#,##0.000").format(n) : v.toString();
            case PERCENT -> v + "%";
            case DATE -> v instanceof LocalDate d ? DAY.format(d)
                    : v instanceof OffsetDateTime t ? DAY.format(t.atZoneSameInstant(IST)) : v.toString();
            case DATETIME -> v instanceof OffsetDateTime t ? DAY_TIME.format(t.atZoneSameInstant(IST)) : v.toString();
            default -> v.toString();
        };
    }

    /** 1,23,45,678.90 - the JDK's en-IN number format still groups in thousands. */
    static String money(Object v) {
        if (!(v instanceof Number n)) return v.toString();
        BigDecimal d = new BigDecimal(n.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
        String plain = d.abs().toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        StringBuilder sb = new StringBuilder();
        int len = whole.length();
        for (int i = 0; i < len; i++) {
            sb.append(whole.charAt(i));
            int left = len - i - 1;
            if (left > 0 && (left == 3 || (left > 3 && (left - 3) % 2 == 0))) sb.append(',');
        }
        return (d.signum() < 0 ? "-" : "") + sb + plain.substring(plain.indexOf('.'));
    }

    private static int width(Column c) {
        return switch (c.kind()) {
            case MONEY -> 14;
            case QTY -> 11;
            case INT, PERCENT -> 7;
            case DATE -> 12;
            case DATETIME -> 16;
            default -> switch (c.key()) {
                case "customer", "particulars", "reason" -> 30;
                case "status", "type", "fuel", "method" -> 10;
                case "code", "ref", "txn", "payment", "phone", "vehicle" -> 14;
                default -> Math.min(40, Math.max(12, c.header().length() + 4));
            };
        };
    }

    private static String safeSheetName(String s) {
        String n = s.replaceAll("[\\\\/*?:\\[\\]]", " ");
        return n.length() > 31 ? n.substring(0, 31) : n;
    }
}
