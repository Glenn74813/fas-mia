package com.miaa.cas.security;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Fine-grained permission checks inside services, where the required permission depends on data. */
public final class CurrentActor {

    /** The demonstration super administrator's role; see {@link #overridesControls()}. */
    public static final String DEMO_SUPERADMIN_ROLE = "DEMO_SUPERADMIN";

    private static volatile boolean demoOverrides;

    private CurrentActor() {
    }

    /** Set once at startup from {@code cas.demo.superadmin-overrides-controls} (DemoControlOverride). */
    static void enableDemoOverrides(boolean enabled) {
        demoOverrides = enabled;
    }

    /**
     * DEMONSTRATION ONLY: true when the demo super administrator is acting on a server started with
     * {@code cas.demo.superadmin-overrides-controls}. That account then passes the record-level controls: it may approve,
     * post and decide what it prepared or requested, take every step of an approval chain, ignore approval limits, and
     * edit or submit what someone else prepared. Off by default, so a production server never does this.
     */
    public static boolean overridesControls() {
        if (!demoOverrides) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + DEMO_SUPERADMIN_ROLE));
    }

    /** True when the current user is {@code other} and the demo override does not apply: for "not the same person" checks. */
    public static boolean isSameAs(Object other) {
        return userId().equals(other) && !overridesControls();
    }

    /** True when the current user is {@code owner}, or the demo override applies: for "only the preparer may" checks. */
    public static boolean isOrActsAs(Object owner) {
        return userId().equals(owner) || overridesControls();
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
