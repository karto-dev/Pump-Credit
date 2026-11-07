package com.petrolpump.credit.payment;

import com.petrolpump.credit.payment.PaymentService.Filter;
import com.petrolpump.credit.payment.PaymentService.HistoryRow;
import com.petrolpump.credit.payment.PaymentService.PaymentInput;
import com.petrolpump.credit.payment.PaymentService.PaymentRow;
import com.petrolpump.credit.payment.PaymentService.Proof;
import com.petrolpump.credit.payment.PaymentService.ProofRow;
import com.petrolpump.credit.payment.PaymentService.Summary;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    /** Staff who may look at payments: viewers, verifiers, and cashiers who need the open-claim queue. */
    private static final List<String> STAFF_VIEW = List.of("PAYMENT_VIEW_ALL", "PAYMENT_VERIFY", "PAYMENT_RECORD");

    public record SubmitPayment(@NotNull LocalDate paymentDate,
                                @NotNull @DecimalMin("1.00") @DecimalMax("10000000") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                @NotNull @Pattern(regexp = "UPI|NEFT|IMPS|RTGS|CASH|CHEQUE") String method,
                                @Size(max = 60) String referenceNo, @Size(max = 500) String remarks,
                                boolean confirmDuplicate) {}

    public record RecordPayment(@NotNull Long customerId, Long paymentId, @NotNull LocalDate paymentDate,
                                @NotNull @DecimalMin("1.00") @DecimalMax("10000000") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                @NotNull @Pattern(regexp = "UPI|NEFT|IMPS|RTGS|CASH|CHEQUE") String method,
                                @Size(max = 60) String referenceNo, @Size(max = 80) String chequeBank,
                                @Size(max = 500) String remarks, boolean confirmDuplicate) {}

    public record VerifyRequest(@DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal verifiedAmount,
                                @Size(max = 255) String evidenceChecked, @NotBlank @Size(max = 1000) String remarks) {}

    public record RemarksRequest(@NotBlank @Size(max = 1000) String remarks) {}

    public record DuplicateRequest(@NotBlank @Size(max = 20) String duplicateOfNo, @NotBlank @Size(max = 1000) String remarks) {}

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    public List<PaymentRow> list(@AuthenticationPrincipal CurrentUser user,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Long customerId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(required = false) String method) {
        if (Role.CUSTOMER.equals(user.role())) return payments.list(new Filter(status, user.customerId(), from, to, q, method));
        requireStaff(user);
        return payments.list(new Filter(status, customerId, from, to, q, method));
    }

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal CurrentUser user) {
        requireStaff(user);
        return payments.summary();
    }

    @GetMapping("/{id}")
    public PaymentRow get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return requireCanSee(user, payments.get(id));
    }

    @GetMapping("/{id}/history")
    public List<HistoryRow> history(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        requireCanSee(user, payments.get(id));
        return payments.history(id);
    }

    @GetMapping("/{id}/proofs")
    public List<ProofRow> proofs(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        requireCanSee(user, payments.get(id));
        return payments.proofs(id);
    }

    @GetMapping("/{id}/proofs/{proofId}")
    public ResponseEntity<byte[]> proof(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                        @PathVariable long proofId) {
        requireCanSee(user, payments.get(id));
        Proof p = payments.proof(id, proofId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(p.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(p.bytes());
    }

    /** Customer: multipart "payment" (JSON) + optional "proof" (screenshot / slip). */
    @PostMapping(value = "/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAYMENT_SUBMIT')")
    public PaymentRow submit(@AuthenticationPrincipal CurrentUser user, @Valid @RequestPart("payment") SubmitPayment req,
                             @RequestPart(value = "proof", required = false) MultipartFile proof) {
        if (!Role.CUSTOMER.equals(user.role()) || user.customerId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a customer login can submit a payment claim");
        }
        return payments.submit(new PaymentInput(req.paymentDate(), req.amount(), req.method(), req.referenceNo(), null,
                req.remarks(), req.confirmDuplicate()), proof, user);
    }

    /** Cashier: multipart "entry" (JSON) + optional "proof". paymentId links an open customer claim. */
    @PostMapping(value = "/cashier-entries", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAYMENT_RECORD')")
    public PaymentRow record(@AuthenticationPrincipal CurrentUser user, @Valid @RequestPart("entry") RecordPayment req,
                             @RequestPart(value = "proof", required = false) MultipartFile proof) {
        if (Role.CUSTOMER.equals(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff only");
        return payments.record(req.customerId(), req.paymentId(), new PaymentInput(req.paymentDate(), req.amount(),
                req.method(), req.referenceNo(), req.chequeBank(), req.remarks(), req.confirmDuplicate()), proof, user);
    }

    @PostMapping(value = "/{id}/proofs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PaymentRow addProof(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                               @RequestPart("proof") MultipartFile proof,
                               @RequestParam(required = false) String remarks) {
        PaymentRow p = requireCanSee(user, payments.get(id));
        boolean owner = Role.CUSTOMER.equals(user.role()) && user.has("PAYMENT_SUBMIT");
        boolean recorder = p.recordedBy() != null && p.recordedBy() == user.id();
        if (!owner && !recorder && !user.has("PAYMENT_RECORD")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot add proof to this payment");
        }
        return payments.addProof(id, proof, remarks == null ? null : remarks.substring(0, Math.min(remarks.length(), 1000)), user);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAYMENT_SUBMIT')")
    public PaymentRow cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                             @Valid @RequestBody RemarksRequest req) {
        PaymentRow p = payments.get(id);
        if (!Role.CUSTOMER.equals(user.role()) || !Objects.equals(p.customerId(), user.customerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        return payments.cancel(id, req.remarks().trim(), user);
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAuthority('PAYMENT_VERIFY')")
    public PaymentRow verify(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                             @Valid @RequestBody VerifyRequest req) {
        return payments.verify(id, req.verifiedAmount(), req.evidenceChecked(), req.remarks().trim(), user);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAYMENT_VERIFY')")
    public PaymentRow reject(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                             @Valid @RequestBody RemarksRequest req) {
        return payments.reject(id, req.remarks().trim(), user);
    }

    @PostMapping("/{id}/request-proof")
    @PreAuthorize("hasAuthority('PAYMENT_VERIFY')")
    public PaymentRow requestProof(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                   @Valid @RequestBody RemarksRequest req) {
        return payments.requestProof(id, req.remarks().trim(), user);
    }

    @PostMapping("/{id}/mark-duplicate")
    @PreAuthorize("hasAuthority('PAYMENT_VERIFY')")
    public PaymentRow markDuplicate(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                    @Valid @RequestBody DuplicateRequest req) {
        return payments.markDuplicate(id, req.duplicateOfNo(), req.remarks().trim(), user);
    }

    private static PaymentRow requireCanSee(CurrentUser user, PaymentRow p) {
        if (Role.CUSTOMER.equals(user.role())) {
            if (!Objects.equals(p.customerId(), user.customerId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
            }
        } else {
            requireStaff(user);
        }
        return p;
    }

    private static void requireStaff(CurrentUser user) {
        if (Role.CUSTOMER.equals(user.role()) || STAFF_VIEW.stream().noneMatch(user::has)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view payments");
        }
    }
}
