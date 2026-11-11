package com.petrolpump.credit.auth;

import com.petrolpump.credit.audit.AuditService;
import com.petrolpump.credit.audit.AuditService.AuditEvent;
import com.petrolpump.credit.common.Passwords;
import com.petrolpump.credit.config.AppProperties;
import com.petrolpump.credit.security.CurrentUser;
import com.petrolpump.credit.security.JwtService;
import com.petrolpump.credit.user.PermissionService;
import com.petrolpump.credit.user.User;
import com.petrolpump.credit.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PermissionService permissionService;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final AppProperties props;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                          PermissionService permissionService, AuditService audit, JdbcTemplate jdbc, AppProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.permissionService = permissionService;
        this.audit = audit;
        this.jdbc = jdbc;
        this.props = props;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    /** idleMinutes: the browser signs the user out after this long without activity (the API enforces it too). */
    public record MeResponse(long id, String username, String fullName, String role, Long customerId,
                             boolean mustChangePassword, Set<String> permissions, int idleMinutes) {}

    public record LoginResponse(String token, Instant expiresAt, MeResponse user) {}

    // noRollbackFor: failed-attempt counters must be saved even though we reject the login.
    @PostMapping("/login")
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        Optional<User> found = users.findByUsernameIgnoreCase(req.username().trim());
        String ip = http.getRemoteAddr();
        String agent = http.getHeader("User-Agent");

        if (found.isEmpty()) {
            audit.record(new AuditEvent(null, null, "LOGIN_FAILED", "USER", null, null, null,
                    "Unknown username: " + req.username(), ip, agent));
            throw invalidCredentials();
        }
        User user = found.get();
        if (!user.isActive()) {
            audit.record(new AuditEvent(user.getId(), user.getRole().getCode(), "LOGIN_FAILED", "USER",
                    user.getId().toString(), null, null, "Account deactivated", ip, agent));
            throw invalidCredentials();
        }
        if (user.isLocked()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Account temporarily locked. Try again later.");
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            user.recordFailedLogin(props.security().maxFailedLogins(), props.security().lockMinutes());
            audit.record(new AuditEvent(user.getId(), user.getRole().getCode(), "LOGIN_FAILED", "USER",
                    user.getId().toString(), null, null, "Wrong password", ip, agent));
            throw invalidCredentials();
        }

        user.recordSuccessfulLogin();
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getRole().getCode());
        jdbc.update("INSERT INTO user_sessions (id, user_id, expires_at, ip_address, user_agent) VALUES (?, ?, ?, ?, ?)",
                issued.sessionId(), user.getId(), Timestamp.from(issued.expiresAt()), ip,
                agent == null ? null : agent.substring(0, Math.min(agent.length(), 300)));
        audit.record(new AuditEvent(user.getId(), user.getRole().getCode(), "LOGIN_SUCCESS", "USER",
                user.getId().toString(), null, null, null, ip, agent));

        return new LoginResponse(issued.token(), issued.expiresAt(), toMe(user, permissionService.effectivePermissions(user)));
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal CurrentUser current) {
        User user = users.findById(current.id()).orElseThrow();
        return toMe(user, current.permissions());
    }

    @PostMapping("/logout")
    public void logout(@AuthenticationPrincipal CurrentUser current, HttpServletRequest http) {
        jdbc.update("UPDATE user_sessions SET revoked_at = now(), revoked_reason = 'LOGOUT' WHERE id = ? AND revoked_at IS NULL",
                current.sessionId());
        audit.record(new AuditEvent(current.id(), current.role(), "LOGOUT", "USER", Long.toString(current.id()),
                null, null, null, http.getRemoteAddr(), http.getHeader("User-Agent")));
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    @PostMapping("/change-password")
    @Transactional
    public MeResponse changePassword(@AuthenticationPrincipal CurrentUser current,
                                     @Valid @RequestBody ChangePasswordRequest req) {
        User user = users.findById(current.id()).orElseThrow();
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (req.currentPassword().equals(req.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different");
        }
        Passwords.requireStrong(req.newPassword());
        jdbc.update("""
                UPDATE users SET password_hash = ?, must_change_password = FALSE, password_changed_at = now(),
                       version = version + 1
                 WHERE id = ?
                """, passwordEncoder.encode(req.newPassword()), user.getId());
        // Sign out every other device; keep this session.
        jdbc.update("""
                UPDATE user_sessions SET revoked_at = now(), revoked_reason = 'PASSWORD_CHANGED'
                 WHERE user_id = ? AND id <> ? AND revoked_at IS NULL
                """, user.getId(), current.sessionId());
        audit.log(current, "PASSWORD_CHANGED", "USER", user.getId(), null, null, null);
        return new MeResponse(user.getId(), user.getUsername(), user.getFullName(), user.getRole().getCode(),
                user.getCustomerId(), false, current.permissions(), props.security().idleMinutes());
    }

    private MeResponse toMe(User u, Set<String> perms) {
        return new MeResponse(u.getId(), u.getUsername(), u.getFullName(), u.getRole().getCode(), u.getCustomerId(),
                u.isMustChangePassword(), perms, props.security().idleMinutes());
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
}
