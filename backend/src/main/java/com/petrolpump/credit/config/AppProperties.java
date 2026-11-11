package com.petrolpump.credit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Security security, Bootstrap bootstrap, Cors cors) {

    public record Jwt(String secret, long ttlMinutes) {}

    public record Security(int maxFailedLogins, int lockMinutes, int idleMinutes) {}

    public record Bootstrap(String adminUsername, String adminPassword) {}

    public record Cors(List<String> allowedOrigins) {}
}
