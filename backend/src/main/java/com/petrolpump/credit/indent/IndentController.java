package com.petrolpump.credit.indent;

import com.petrolpump.credit.indent.IndentService.CreateIndent;
import com.petrolpump.credit.indent.IndentService.Filter;
import com.petrolpump.credit.indent.IndentService.IndentRow;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/indents")
public class IndentController {

    public record CreateRequest(Long customerId, @NotNull Long vehicleId,
                                @NotNull @Pattern(regexp = "PETROL|DIESEL") String fuelType,
                                @DecimalMin("0.001") @DecimalMax("20000") BigDecimal requestedQuantity,
                                @DecimalMin("1.00") @DecimalMax("2000000") BigDecimal requestedAmount,
                                boolean fullTank, @Size(max = 500) String instructions) {}

    public record ReasonRequest(@NotBlank @Size(max = 255) String reason) {}

    public record AuthoriseRequest(@DecimalMin("0.01") BigDecimal extraLimit, OffsetDateTime validUntil,
                                   @NotBlank @Size(max = 500) String reason) {}

    private final IndentService indents;

    public IndentController(IndentService indents) {
        this.indents = indents;
    }

    @GetMapping
    public List<IndentRow> list(@AuthenticationPrincipal CurrentUser user,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) Long customerId,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (Role.CUSTOMER.equals(user.role())) {
            return indents.list(new Filter(status, user.customerId(), q, from, to));
        }
        requireViewAll(user);
        return indents.list(new Filter(status, customerId, q, from, to));
    }

    @GetMapping("/{id}")
    public IndentRow get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        IndentRow row = indents.get(id);
        if (Role.CUSTOMER.equals(user.role())) {
            if (!Objects.equals(row.customerId(), user.customerId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Indent not found");
            }
        } else {
            requireViewAll(user);
        }
        return row;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INDENT_CREATE')")
    public IndentRow create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateRequest req) {
        return indents.create(new CreateIndent(req.customerId(), req.vehicleId(), req.fuelType(), req.requestedQuantity(),
                req.requestedAmount(), req.fullTank(), req.instructions()), user);
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAuthority('INDENT_PROCESS')")
    public IndentRow accept(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return indents.accept(id, user);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('INDENT_PROCESS')")
    public IndentRow reject(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                            @Valid @RequestBody ReasonRequest req) {
        return indents.reject(id, req.reason(), user);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public IndentRow cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                            @Valid @RequestBody ReasonRequest req) {
        return indents.cancel(id, req.reason(), user);
    }

    @PostMapping("/{id}/authorise")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('CREDIT_LIMIT_EXCEPTION')")
    public IndentRow authorise(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                               @Valid @RequestBody AuthoriseRequest req) {
        return indents.authorise(id, req.extraLimit(), req.validUntil(), req.reason(), user);
    }

    private static void requireViewAll(CurrentUser user) {
        if (!user.has("INDENT_VIEW_ALL") && !user.has("INDENT_PROCESS")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view indents");
        }
    }
}
