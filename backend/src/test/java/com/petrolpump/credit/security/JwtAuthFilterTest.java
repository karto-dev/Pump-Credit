package com.petrolpump.credit.security;

import com.petrolpump.credit.config.AppProperties;
import com.petrolpump.credit.user.PermissionService;
import com.petrolpump.credit.user.Role;
import com.petrolpump.credit.user.User;
import com.petrolpump.credit.user.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    private static final int IDLE_MINUTES = 10;

    private final AppProperties props = new AppProperties(
            new AppProperties.Jwt("test-secret-test-secret-test-secret-0123", 480),
            new AppProperties.Security(5, 15, IDLE_MINUTES),
            new AppProperties.Bootstrap("admin", "x"),
            new AppProperties.Cors(List.of()));
    private final JwtService jwtService = new JwtService(props);
    private final UserRepository users = mock(UserRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService, users, permissions, jdbc, props);
    private final FilterChain chain = mock(FilterChain.class);

    @BeforeEach
    void activeUser() {
        Role role = mock(Role.class);
        when(role.getCode()).thenReturn("MANAGER");
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.getUsername()).thenReturn("manager");
        when(user.getRole()).thenReturn(role);
        when(user.isActive()).thenReturn(true);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(permissions.effectivePermissions(user)).thenReturn(Set.of("REPORT_VIEW"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void liveSessionAuthenticatesAndSlidesTheIdleWindow() throws Exception {
        JwtService.IssuedToken token = jwtService.issue(7L, "MANAGER");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        filter.doFilter(bearer(token.token()), new MockHttpServletResponse(), chain);

        verify(jdbc).update(contains("SET last_seen_at = now()"), eq(token.sessionId()), eq(IDLE_MINUTES));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOfSatisfying(CurrentUser.class, u -> assertThat(u.sessionId()).isEqualTo(token.sessionId()));
        verify(chain).doFilter(any(), any());
    }

    @Test
    void idleRevokedOrExpiredSessionIsNotAuthenticated() throws Exception {
        JwtService.IssuedToken token = jwtService.issue(7L, "MANAGER");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);   // the UPDATE matched no live session

        filter.doFilter(bearer(token.token()), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(users, never()).findById(any());
        verify(chain).doFilter(any(), any());   // the request goes on and Spring Security answers 401
    }

    @Test
    void requestWithoutTokenSkipsTheDatabase() throws Exception {
        filter.doFilter(new MockHttpServletRequest("POST", "/api/auth/login"), new MockHttpServletResponse(), chain);

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    private static MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/me");
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }
}
