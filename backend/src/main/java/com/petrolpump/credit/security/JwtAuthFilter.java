package com.petrolpump.credit.security;

import com.petrolpump.credit.config.AppProperties;
import com.petrolpump.credit.user.PermissionService;
import com.petrolpump.credit.user.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates the bearer token, checks the server-side session is still live (logout / admin revoke / idle timeout),
 * and reloads role + permissions from the DB on every request so permission changes apply immediately.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository users;
    private final PermissionService permissionService;
    private final JdbcTemplate jdbc;
    private final int idleMinutes;

    public JwtAuthFilter(JwtService jwtService, UserRepository users, PermissionService permissionService, JdbcTemplate jdbc,
                         AppProperties props) {
        this.jwtService = jwtService;
        this.users = users;
        this.permissionService = permissionService;
        this.jdbc = jdbc;
        this.idleMinutes = props.security().idleMinutes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            jwtService.parse(header.substring(7)).ifPresent(this::authenticate);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(Claims claims) {
        UUID sessionId = UUID.fromString(claims.getId());
        // Live = not revoked, not expired, and used within the idle window. Touching it slides the window.
        int live = jdbc.update("""
                UPDATE user_sessions SET last_seen_at = now()
                 WHERE id = ? AND revoked_at IS NULL AND expires_at > now()
                   AND last_seen_at > now() - make_interval(mins => ?)
                """, sessionId, idleMinutes);
        if (live == 0) return;

        users.findById(Long.parseLong(claims.getSubject()))
                .filter(u -> u.isActive() && !u.isLocked())
                .ifPresent(u -> {
                    Set<String> perms = permissionService.effectivePermissions(u);
                    CurrentUser principal = new CurrentUser(u.getId(), u.getUsername(), u.getFullName(),
                            u.getRole().getCode(), u.getCustomerId(), sessionId, perms);
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + u.getRole().getCode()));
                    perms.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, authorities));
                });
    }
}
