package com.petrolpump.credit.approval;

import com.petrolpump.credit.approval.ApprovalService.HistoryRow;
import com.petrolpump.credit.sale.SaleController;
import com.petrolpump.credit.sale.SaleService;
import com.petrolpump.credit.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/credit-sales/{id}")
public class ApprovalController {

    public record ApproveRequest(@Size(max = 500) String remarks) {}

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}

    private final ApprovalService approvals;
    private final SaleService sales;

    public ApprovalController(ApprovalService approvals, SaleService sales) {
        this.approvals = approvals;
        this.sales = sales;
    }

    /** Only the billed customer answers. Staff, including admin, can never approve on a customer's behalf. */
    @PostMapping("/approve")
    @PreAuthorize("hasRole('CUSTOMER') and hasAuthority('BILL_APPROVE_OWN')")
    public SaleService.SaleRow approve(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                       @Valid @RequestBody(required = false) ApproveRequest req) {
        approvals.approve(id, req == null ? null : req.remarks(), user);
        return sales.get(id);
    }

    @PostMapping("/reject")
    @PreAuthorize("hasRole('CUSTOMER') and hasAuthority('BILL_APPROVE_OWN')")
    public Map<String, Object> reject(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                      @Valid @RequestBody RejectRequest req) {
        long disputeId = approvals.reject(id, req.reason().trim(), user);
        return Map.of("sale", sales.get(id), "disputeId", disputeId);
    }

    @GetMapping("/approvals")
    public List<HistoryRow> history(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        SaleController.requireCanSee(user, sales.get(id));
        return approvals.history(id);
    }
}
