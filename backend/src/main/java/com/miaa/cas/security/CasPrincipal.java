package com.miaa.cas.security;

import java.security.Principal;
import java.util.UUID;

/** Authenticated caller resolved from a validated access token and the live session record. */
public record CasPrincipal(UUID userId, String username, UUID sessionId) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
