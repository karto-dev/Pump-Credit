package com.petrolpump.credit.security;

import java.util.Set;
import java.util.UUID;

/** Authenticated principal placed in the SecurityContext by {@link JwtAuthFilter}. */
public record CurrentUser(long id, String username, String fullName, String role, Long customerId,
                          UUID sessionId, Set<String> permissions) {

    public boolean has(String permission) {
        return permissions.contains(permission);
    }
}
