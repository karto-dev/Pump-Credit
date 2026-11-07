package com.petrolpump.credit.dispute;

import com.petrolpump.credit.dispute.DisputeService.DisputeRow;
import com.petrolpump.credit.dispute.DisputeService.Resolution;
import com.petrolpump.credit.dispute.DisputeService.Summary;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    public record NotesRequest(@Size(max = 500) String notes) {}

    public record ResolveRequest(@NotNull Resolution resolution,
                                 @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                 @NotBlank @Size(max = 900) String notes) {}

    private final DisputeService disputes;

    public DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    /** Customers see their own disputes; staff need dispute or sales visibility. */
    @GetMapping
    public List<DisputeRow> list(@AuthenticationPrincipal CurrentUser user,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Long customerId,
                                 @RequestParam(required = false) String q) {
        if (Role.CUSTOMER.equals(user.role())) return disputes.list(status, user.customerId(), q);
        requireStaff(user);
        return disputes.list(status, customerId, q);
    }

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal CurrentUser user) {
        requireStaff(user);
        return disputes.summary();
    }

    @GetMapping("/{id}")
    public DisputeRow get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        DisputeRow d = disputes.get(id);
        if (Role.CUSTOMER.equals(user.role())) {
            if (!Objects.equals(d.customerId(), user.customerId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispute not found");
            }
        } else {
            requireStaff(user);
        }
        return d;
    }

    @PostMapping("/{id}/investigate")
    @PreAuthorize("hasAuthority('DISPUTE_RESOLVE')")
    public DisputeRow investigate(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                  @Valid @RequestBody(required = false) NotesRequest req) {
        return disputes.investigate(id, req == null ? null : req.notes(), user);
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('DISPUTE_RESOLVE')")
    public DisputeRow resolve(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                              @Valid @RequestBody ResolveRequest req) {
        return disputes.resolve(id, req.resolution(), req.amount(), req.notes().trim(), user);
    }

    private static void requireStaff(CurrentUser user) {
        if (!user.has("DISPUTE_RESOLVE") && !user.has("CREDIT_TXN_VIEW_ALL")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view disputes");
        }
    }
}
