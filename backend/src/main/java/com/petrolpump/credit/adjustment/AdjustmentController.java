package com.petrolpump.credit.adjustment;

import com.petrolpump.credit.adjustment.AdjustmentService.AdjustmentRow;
import com.petrolpump.credit.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/adjustments")
public class AdjustmentController {

    public record RemarksRequest(@NotBlank @Size(max = 500) String remarks) {}

    public record CreateAdjustment(@NotNull Long customerId,
                                   @NotBlank @Pattern(regexp = "DEBIT|CREDIT") String adjType,
                                   @NotNull @DecimalMin("0.01")
                                   @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                   @NotBlank @Size(max = 1000) String reason, Long saleId) {}

    private final AdjustmentService adjustments;

    public AdjustmentController(AdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ADJUSTMENT_APPROVE', 'ADJUSTMENT_CREATE', 'LEDGER_VIEW_ALL')")
    public List<AdjustmentRow> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long customerId) {
        return adjustments.list(status, customerId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADJUSTMENT_CREATE')")
    public AdjustmentRow create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateAdjustment req) {
        return adjustments.create(req.customerId(), req.adjType(), req.amount(), req.reason().trim(), req.saleId(), user);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('ADJUSTMENT_APPROVE')")
    public AdjustmentRow approve(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                 @Valid @RequestBody RemarksRequest req) {
        return adjustments.approve(id, req.remarks().trim(), user);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('ADJUSTMENT_APPROVE')")
    public AdjustmentRow reject(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                @Valid @RequestBody RemarksRequest req) {
        return adjustments.reject(id, req.remarks().trim(), user);
    }
}
