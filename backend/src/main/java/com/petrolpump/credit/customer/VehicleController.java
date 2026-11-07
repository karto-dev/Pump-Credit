package com.petrolpump.credit.customer;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.customer.CustomerDtos.ActiveChangeRequest;
import com.petrolpump.credit.customer.CustomerDtos.VehicleRequest;
import com.petrolpump.credit.customer.CustomerDtos.VehicleRow;
import com.petrolpump.credit.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class VehicleController {

    private static final String SELECT = """
            SELECT id, customer_id, vehicle_number, vehicle_type, default_fuel, description, is_active, created_at
              FROM vehicles
            """;
    private static final DataClassRowMapper<VehicleRow> MAPPER = new DataClassRowMapper<>(VehicleRow.class);

    private final JdbcTemplate jdbc;
    private final CustomerAccess access;
    private final AuditService audit;

    public VehicleController(JdbcTemplate jdbc, CustomerAccess access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/api/customers/{customerId}/vehicles")
    public List<VehicleRow> list(@AuthenticationPrincipal CurrentUser user, @PathVariable long customerId) {
        access.requireCanView(user, customerId);
        return jdbc.query(SELECT + " WHERE customer_id = ? ORDER BY is_active DESC, vehicle_number", MAPPER, customerId);
    }

    @PostMapping("/api/customers/{customerId}/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('VEHICLE_MANAGE')")
    @Transactional
    public VehicleRow add(@AuthenticationPrincipal CurrentUser user, @PathVariable long customerId,
                          @Valid @RequestBody VehicleRequest req) {
        Long id = jdbc.queryForObject("""
                INSERT INTO vehicles (customer_id, vehicle_number, vehicle_type, default_fuel, description,
                                      created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, customerId, normalise(req.vehicleNumber()), blankToNull(req.vehicleType()),
                blankToNull(req.defaultFuel()), blankToNull(req.description()), user.id(), user.id());
        VehicleRow created = get(id);
        audit.log(user, "VEHICLE_ADDED", "VEHICLE", id, null, created, null);
        return created;
    }

    @PutMapping("/api/vehicles/{id}")
    @PreAuthorize("hasAuthority('VEHICLE_MANAGE')")
    @Transactional
    public VehicleRow update(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                             @Valid @RequestBody VehicleRequest req) {
        VehicleRow before = get(id);
        jdbc.update("""
                UPDATE vehicles SET vehicle_number = ?, vehicle_type = ?, default_fuel = ?, description = ?,
                       updated_by = ? WHERE id = ?
                """, normalise(req.vehicleNumber()), blankToNull(req.vehicleType()), blankToNull(req.defaultFuel()),
                blankToNull(req.description()), user.id(), id);
        VehicleRow after = get(id);
        audit.log(user, "VEHICLE_UPDATED", "VEHICLE", id, before, after, null);
        return after;
    }

    @PatchMapping("/api/vehicles/{id}/status")
    @PreAuthorize("hasAuthority('VEHICLE_MANAGE')")
    @Transactional
    public VehicleRow setActive(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                @Valid @RequestBody ActiveChangeRequest req) {
        VehicleRow before = get(id);
        jdbc.update("UPDATE vehicles SET is_active = ?, updated_by = ? WHERE id = ?", req.active(), user.id(), id);
        audit.log(user, req.active() ? "VEHICLE_REACTIVATED" : "VEHICLE_DEACTIVATED", "VEHICLE", id,
                Map.of("isActive", before.isActive()), Map.of("isActive", req.active()), req.reason());
        return get(id);
    }

    private VehicleRow get(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found"));
    }

    /** "ka 01 ab-1234" -> "KA01AB1234" */
    static String normalise(String vehicleNumber) {
        String n = vehicleNumber.replaceAll("[\\s-]", "").toUpperCase();
        if (!n.matches("^[A-Z0-9]{4,12}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vehicle number looks invalid: " + vehicleNumber);
        }
        return n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
