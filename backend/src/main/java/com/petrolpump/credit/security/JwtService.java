package com.petrolpump.credit.security;

import com.petrolpump.credit.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(AppProperties props) {
        byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttlMinutes = props.jwt().ttlMinutes();
    }

    public IssuedToken issue(long userId, String role) {
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlMinutes * 60);
        String token = Jwts.builder()
                .id(sessionId.toString())
                .subject(Long.toString(userId))
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, sessionId, expiresAt);
    }

    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String token, UUID sessionId, Instant expiresAt) {}
}
