package com.petrolpump.credit.settings;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    public record SettingRow(String key, String value, String description, String updatedByName, OffsetDateTime updatedAt) {}

    public record UpdateSetting(@NotNull @Size(max = 500) String value, @NotBlank @Size(max = 500) String reason) {}

    /** Safe for every signed-in user: shown on the customer's Pay screen and on statements. */
    private static final List<String> PUBLIC = List.of("PUMP_NAME", "PUMP_UPI_ID", "PUMP_BANK_DETAILS");

    private static final Set<String> NUMERIC = Set.of("CUSTOMER_APPROVAL_TIMEOUT_HRS", "CREDIT_LIMIT_WARN_PERCENT",
            "PAYMENT_REMINDER_DAYS", "MAX_UPLOAD_MB", "BACKUP_RETENTION_DAYS", "DUPLICATE_SALE_WINDOW_MIN",
            "PAYMENT_MAX_BACKDATE_DAYS", "OVERDUE_GRACE_DAYS");

    private static final Map<String, Set<String>> CHOICES = Map.of(
            "OVER_LIMIT_INDENT_ACTION", Set.of("HOLD", "BLOCK"),
            "OVERDUE_INDENT_ACTION", Set.of("ALLOW", "HOLD", "BLOCK"));

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SettingsController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/public")
    public Map<String, String> publicSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT key, value FROM system_settings WHERE key = ANY (string_to_array(?, ','))",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString(1), rs.getString(2)),
                String.join(",", PUBLIC));
        return out;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public List<SettingRow> list() {
        return jdbc.query("""
                SELECT s.key, s.value, s.description, u.full_name AS updated_by_name, s.updated_at
                  FROM system_settings s LEFT JOIN users u ON u.id = s.updated_by ORDER BY s.key
                """, new DataClassRowMapper<>(SettingRow.class));
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    @Transactional
    public SettingRow update(@AuthenticationPrincipal CurrentUser user, @PathVariable String key,
                             @Valid @RequestBody UpdateSetting req) {
        String old = jdbc.query("SELECT value FROM system_settings WHERE key = ? FOR UPDATE", (rs, i) -> rs.getString(1), key)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown setting " + key));
        String value = req.value().trim();
        if (NUMERIC.contains(key)) {
            try {
                if (Integer.parseInt(value) < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a whole number of 0 or more");
            }
        }
        if (CHOICES.containsKey(key) && !CHOICES.get(key).contains(value.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be one of " + CHOICES.get(key));
        }
        if (CHOICES.containsKey(key)) value = value.toUpperCase();
        jdbc.update("UPDATE system_settings SET value = ?, updated_by = ?, updated_at = now() WHERE key = ?", value, user.id(), key);
        audit.log(user, "SETTING_CHANGED", "SYSTEM_SETTING", key, Map.of("value", old), Map.of("value", value), req.reason().trim());
        return jdbc.queryForObject("""
                SELECT s.key, s.value, s.description, u.full_name AS updated_by_name, s.updated_at
                  FROM system_settings s LEFT JOIN users u ON u.id = s.updated_by WHERE s.key = ?
                """, new DataClassRowMapper<>(SettingRow.class), key);
    }
}
