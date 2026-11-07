package com.petrolpump.credit.fuel;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Pump price per litre. Used to prefill the cashier's rate and to estimate quantity-only indents. */
@RestController
@RequestMapping("/api/fuel-rates")
public class FuelRateController {

    public record FuelRate(String fuelType, BigDecimal ratePerLitre, OffsetDateTime effectiveFrom, String setByName) {}

    public record NewRateRequest(@NotNull @Pattern(regexp = "PETROL|DIESEL") String fuelType,
                                 @NotNull @DecimalMin("1.00") @DecimalMax("500.00") BigDecimal ratePerLitre,
                                 OffsetDateTime effectiveFrom) {}

    private static final String SELECT = """
            SELECT r.fuel_type, r.rate_per_litre, r.effective_from, u.full_name AS set_by_name
              FROM fuel_rates r LEFT JOIN users u ON u.id = r.set_by
            """;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public FuelRateController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/current")
    public List<FuelRate> current() {
        return jdbc.query("SELECT DISTINCT ON (fuel_type) * FROM (" + SELECT
                        + " WHERE r.effective_from <= now()) x ORDER BY fuel_type, effective_from DESC",
                new DataClassRowMapper<>(FuelRate.class));
    }

    @GetMapping("/history")
    public List<FuelRate> history() {
        return jdbc.query(SELECT + " ORDER BY r.effective_from DESC LIMIT 100", new DataClassRowMapper<>(FuelRate.class));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('FUEL_RATE_MANAGE')")
    @Transactional
    public List<FuelRate> setRate(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody NewRateRequest req) {
        // DB clock, not JVM clock: "now" must be visible to the current() query in this same transaction.
        OffsetDateTime from = jdbc.queryForObject("""
                INSERT INTO fuel_rates (fuel_type, rate_per_litre, effective_from, set_by)
                VALUES (?, ?, COALESCE(?::timestamptz, now()), ?) RETURNING effective_from
                """, OffsetDateTime.class, req.fuelType(), req.ratePerLitre(),
                req.effectiveFrom() == null ? null : Timestamp.from(req.effectiveFrom().toInstant()), user.id());
        audit.log(user, "FUEL_RATE_SET", "FUEL_RATE", req.fuelType(), null,
                Map.of("ratePerLitre", req.ratePerLitre(), "effectiveFrom", String.valueOf(from)), null);
        return current();
    }
}
