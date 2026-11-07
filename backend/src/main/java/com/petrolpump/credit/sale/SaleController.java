package com.petrolpump.credit.sale;

import com.petrolpump.credit.sale.SaleService.Bill;
import com.petrolpump.credit.sale.SaleService.Filter;
import com.petrolpump.credit.sale.SaleService.SaleRequest;
import com.petrolpump.credit.sale.SaleService.SaleRow;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/credit-sales")
public class SaleController {

    public record CreateSale(@NotNull Long customerId, Long indentId, @NotNull Long vehicleId,
                             @NotNull @Pattern(regexp = "PETROL|DIESEL") String fuelType,
                             @NotNull @DecimalMin("0.001") @DecimalMax("20000") @Digits(integer = 9, fraction = 3) BigDecimal quantity,
                             @NotNull @DecimalMin("1.00") @DecimalMax("500.00") @Digits(integer = 8, fraction = 2) BigDecimal ratePerLitre,
                             OffsetDateTime suppliedAt, @Size(max = 40) String physicalBillNo,
                             @Size(max = 500) String remarks, boolean confirmDuplicate) {}

    private final SaleService sales;

    public SaleController(SaleService sales) {
        this.sales = sales;
    }

    /** multipart/form-data: "sale" (JSON) + "bill" (photo of the physical bill). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CREDIT_TXN_CREATE')")
    public SaleRow create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestPart("sale") CreateSale req,
                          @RequestPart("bill") MultipartFile bill) {
        return sales.create(new SaleRequest(req.customerId(), req.indentId(), req.vehicleId(), req.fuelType(),
                req.quantity(), req.ratePerLitre(), req.suppliedAt(), req.physicalBillNo(), req.remarks(),
                req.confirmDuplicate()), bill, user);
    }

    @GetMapping
    public List<SaleRow> list(@AuthenticationPrincipal CurrentUser user,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(required = false) Long customerId,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) String fuelType,
                              @RequestParam(required = false) Long cashierId,
                              @RequestParam(required = false) String approvalStatus) {
        if (Role.CUSTOMER.equals(user.role())) {
            return sales.list(new Filter(from, to, user.customerId(), q, fuelType, null, approvalStatus));
        }
        requireViewAll(user);
        return sales.list(new Filter(from, to, customerId, q, fuelType, cashierId, approvalStatus));
    }

    @GetMapping("/{id}")
    public SaleRow get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return requireCanSee(user, sales.get(id));
    }

    @GetMapping("/{id}/bill")
    public ResponseEntity<byte[]> bill(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        requireCanSee(user, sales.get(id));
        Bill bill = sales.bill(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(bill.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(bill.bytes());
    }

    public static SaleRow requireCanSee(CurrentUser user, SaleRow sale) {
        if (Role.CUSTOMER.equals(user.role())) {
            if (!Objects.equals(sale.customerId(), user.customerId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found");
            }
        } else {
            requireViewAll(user);
        }
        return sale;
    }

    private static void requireViewAll(CurrentUser user) {
        if (!user.has("CREDIT_TXN_VIEW_ALL")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view credit sales");
        }
    }
}
