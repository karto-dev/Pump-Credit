package com.petrolpump.credit.report;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.report.ReportService.StaffDashboard;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.user.Role;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/** ?format=json (default), xlsx or pdf on every report. Exports are audited. */
@RestController
public class ReportController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportService reports;
    private final ExportService export;
    private final AuditService audit;

    public ReportController(ReportService reports, ExportService export, AuditService audit) {
        this.reports = reports;
        this.export = export;
        this.audit = audit;
    }

    /** Customer: own account with LEDGER_VIEW_OWN. Staff: LEDGER_VIEW_ALL. Whoever can see it can download it. */
    @GetMapping("/api/customers/{id}/statement")
    public ResponseEntity<?> statement(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(defaultValue = "json") String format) {
        if (Role.CUSTOMER.equals(user.role())) {
            if (!Objects.equals(user.customerId(), id) || !user.has("LEDGER_VIEW_OWN")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
            }
        } else if (!user.has("LEDGER_VIEW_ALL")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view ledgers");
        }
        LocalDate t = to != null ? to : today();
        LocalDate f = from != null ? from : t.withDayOfMonth(1);
        return respond(user, "statement-" + id, format, reports.statement(id, checkRange(f, t), t), false);
    }

    @GetMapping("/api/reports/outstanding")
    public ResponseEntity<?> outstanding(@AuthenticationPrincipal CurrentUser user,
                                         @RequestParam(defaultValue = "true") boolean onlyWithBalance,
                                         @RequestParam(defaultValue = "false") boolean overdueOnly,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "json") String format) {
        require(user, "REPORT_FINANCIAL");
        return respond(user, "outstanding", format,
                reports.outstanding(onlyWithBalance, overdueOnly, status == null || status.isBlank() ? null : status), true);
    }

    @GetMapping("/api/reports/sales")
    public ResponseEntity<?> sales(@AuthenticationPrincipal CurrentUser user,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(defaultValue = "day") String groupBy,
                                   @RequestParam(required = false) Long customerId,
                                   @RequestParam(defaultValue = "json") String format) {
        require(user, "REPORT_VIEW");
        LocalDate t = to != null ? to : today();
        LocalDate f = from != null ? from : t.withDayOfMonth(1);
        return respond(user, "sales-" + groupBy, format, reports.sales(checkRange(f, t), t, groupBy, customerId), true);
    }

    @GetMapping("/api/reports/collections")
    public ResponseEntity<?> collections(@AuthenticationPrincipal CurrentUser user,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Long customerId,
                                         @RequestParam(defaultValue = "json") String format) {
        require(user, "REPORT_FINANCIAL");
        LocalDate t = to != null ? to : today();
        LocalDate f = from != null ? from : t.withDayOfMonth(1);
        return respond(user, "recoveries", format, reports.collections(checkRange(f, t), t, customerId), true);
    }

    @GetMapping("/api/reports/cashiers")
    public ResponseEntity<?> cashiers(@AuthenticationPrincipal CurrentUser user,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(defaultValue = "json") String format) {
        require(user, "REPORT_VIEW");
        LocalDate t = to != null ? to : today();
        LocalDate f = from != null ? from : t;
        return respond(user, "cashier-day-book", format, reports.cashiers(checkRange(f, t), t), true);
    }

    @GetMapping("/api/dashboard/staff")
    public StaffDashboard staffDashboard(@AuthenticationPrincipal CurrentUser user) {
        if (Role.CUSTOMER.equals(user.role())
                || !(user.has("REPORT_VIEW") || user.has("REPORT_FINANCIAL") || user.has("USER_MANAGE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff dashboard is for managers and admins");
        }
        return reports.staffDashboard();
    }

    private ResponseEntity<?> respond(CurrentUser user, String name, String format, Report report, boolean exportNeedsPermission) {
        String fmt = format.toLowerCase();
        if (fmt.equals("json")) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report);
        if (!fmt.equals("xlsx") && !fmt.equals("pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be json, xlsx or pdf");
        }
        if (exportNeedsPermission) require(user, "REPORT_EXPORT");
        byte[] body = fmt.equals("xlsx") ? export.xlsx(report) : export.pdf(report);
        String file = "%s-%s.%s".formatted(name, today(), fmt);
        audit.log(user, "REPORT_EXPORTED", "REPORT", name, null, Map.of("format", fmt, "rows", report.rows().size()), null);
        return ResponseEntity.ok()
                .contentType(fmt.equals("xlsx") ? XLSX : MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static void require(CurrentUser user, String perm) {
        if (Role.CUSTOMER.equals(user.role()) || !user.has(perm)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission for this report");
        }
    }

    private static LocalDate checkRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "From date is after to date");
        if (from.isBefore(to.minusDays(366 * 3))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a period of three years or less");
        }
        return from;
    }

    private static LocalDate today() {
        return LocalDate.now(IST);
    }
}
