package com.miaa.cas.security;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Fine-grained permission checks inside services, where the required permission depends on data. */
public final class CurrentActor {

    private CurrentActor() {
    }

    public static CasPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CasPrincipal p) {
            return p;
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required.");
    }

    /** True when a signed-in user is acting (false on scheduler and worker threads). */
    public static boolean isPresent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CasPrincipal;
    }

    public static UUID userId() {
        return principal().userId();
    }

    public static String username() {
        return principal().username();
    }

    public static boolean hasPermission(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("PERM_" + permission));
    }

    public static void requirePermission(String permission) {
        if (!hasPermission(permission)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action.");
        }
    }
}
