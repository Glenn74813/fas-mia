package com.miaa.cas.audit;

import com.miaa.cas.security.CasPrincipal;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who performed an action. {@link #SYSTEM} is used for migrations, bootstrap and scheduled jobs. */
public record Actor(UUID userId, String username) {

    public static final Actor SYSTEM = new Actor(null, "system");

    public static Actor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CasPrincipal p) {
            return new Actor(p.userId(), p.username());
        }
        return SYSTEM;
    }

    public static Actor anonymous(String attemptedIdentifier) {
        String name = attemptedIdentifier == null ? null : attemptedIdentifier.trim().toLowerCase();
        if (name != null && name.length() > 64) {
            name = name.substring(0, 64);
        }
        return new Actor(null, name);
    }
}
