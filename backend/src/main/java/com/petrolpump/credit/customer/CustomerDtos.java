package com.petrolpump.credit.customer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public final class CustomerDtos {

    private CustomerDtos() {}

    static final String PHONE = "^[6-9]\\d{9}$";
    static final String GSTIN = "^\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";
    static final String PAN = "^[A-Z]{5}\\d{4}[A-Z]$";

    public record CustomerRow(long id, String customerCode, String name, String businessName, String phone,
                              String city, String status, BigDecimal creditLimit, BigDecimal confirmedOutstanding,
                              BigDecimal activeExceptionLimit, BigDecimal availableCredit,
                              BigDecimal unverifiedPaymentClaims, long pendingApprovalCount, long disputedCount,
                              long vehicleCount) {}

    public record CustomerDetail(long id, String customerCode, String name, String businessName, String contactPerson,
                                 String phone, String alternatePhone, String email, String address, String city,
                                 String state, String pincode, String gstin, String pan, BigDecimal creditLimit,
                                 int creditPeriodDays, BigDecimal openingBalance, LocalDate openingBalanceDate,
                                 String status, int version, OffsetDateTime createdAt,
                                 BigDecimal confirmedOutstanding, BigDecimal activeExceptionLimit,
                                 BigDecimal availableCredit, BigDecimal unverifiedPaymentClaims,
                                 long pendingApprovalCount, BigDecimal pendingApprovalAmount,
                                 long disputedCount, BigDecimal disputedAmount) {}

    /** Fields editable after creation. Credit limit and opening balance have their own controlled flows. */
    public record CustomerProfile(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 150) String businessName,
            @Size(max = 120) String contactPerson,
            @NotBlank @Pattern(regexp = PHONE, message = "must be a 10-digit Indian mobile number") String phone,
            @Pattern(regexp = PHONE, message = "must be a 10-digit Indian mobile number") String alternatePhone,
            @Email @Size(max = 150) String email,
            String address,
            @Size(max = 80) String city,
            @Size(max = 80) String state,
            @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String pincode,
            @Pattern(regexp = GSTIN, message = "is not a valid GSTIN") String gstin,
            @Pattern(regexp = PAN, message = "is not a valid PAN") String pan,
            @NotNull @Min(0) @Max(365) Integer creditPeriodDays) {}

    public record CreateCustomerRequest(
            @NotNull @jakarta.validation.Valid CustomerProfile profile,
            @NotNull @DecimalMin("0.00") BigDecimal creditLimit,
            @NotNull BigDecimal openingBalance,
            LocalDate openingBalanceDate) {}

    public record UpdateCustomerRequest(@NotNull @jakarta.validation.Valid CustomerProfile profile,
                                        @NotNull Integer version) {}

    public record StatusChangeRequest(@NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED|CLOSED") String status,
                                      @NotBlank @Size(max = 500) String reason) {}

    public record VehicleRow(long id, long customerId, String vehicleNumber, String vehicleType, String defaultFuel,
                             String description, boolean isActive, OffsetDateTime createdAt) {}

    public record VehicleRequest(@NotBlank @Size(max = 15) String vehicleNumber,
                                 @Size(max = 30) String vehicleType,
                                 @Pattern(regexp = "PETROL|DIESEL") String defaultFuel,
                                 @Size(max = 150) String description) {}

    public record ActiveChangeRequest(boolean active, @NotBlank @Size(max = 500) String reason) {}
}
