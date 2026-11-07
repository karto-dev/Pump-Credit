package com.petrolpump.credit.security;

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
 * Validates the bearer token, checks the server-side session is still live (logout / admin revoke),
 * and reloads role + permissions from the DB on every request so permission changes apply immediately.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository users;
    private final PermissionService permissionService;
    private final JdbcTemplate jdbc;

    public JwtAuthFilter(JwtService jwtService, UserRepository users, PermissionService permissionService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.users = users;
        this.permissionService = permissionService;
        this.jdbc = jdbc;
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
        Boolean sessionLive = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_sessions WHERE id = ? AND revoked_at IS NULL AND expires_at > now())",
                Boolean.class, sessionId);
        if (!Boolean.TRUE.equals(sessionLive)) return;

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
