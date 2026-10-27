package com.miaa.cas.integrations.webhooks;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Which URLs webhooks may be sent to. HTTPS only, and never to this machine or private, link-local or carrier-grade
 * NAT addresses, so a subscription cannot be used to reach internal services (server-side request forgery). Checked
 * when a subscription is created or resumed and again before every delivery. Development and tests can allow plain
 * HTTP to local receivers ({@code cas.webhooks.allow-local-targets}); production never does.
 */
@Component
public class WebhookTargets {

    private final boolean allowLocal;

    public WebhookTargets(@Value("${cas.webhooks.allow-local-targets:false}") boolean allowLocal, Environment env) {
        if (allowLocal && Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("cas.webhooks.allow-local-targets is not allowed with the prod profile");
        }
        this.allowLocal = allowLocal;
    }

    public void check(String url) {
        URI u;
        try {
            u = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("The URL is not valid.");
        }
        if (u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null) {
            throw invalid("Give an absolute URL with a host and no user name or fragment.");
        }
        boolean https = "https".equalsIgnoreCase(u.getScheme());
        if (!https && !(allowLocal && "http".equalsIgnoreCase(u.getScheme()))) {
            throw invalid("Webhook URLs must use HTTPS.");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(u.getHost());
        } catch (UnknownHostException e) {
            throw invalid("The host " + u.getHost() + " cannot be resolved.");
        }
        for (InetAddress a : addresses) {
            if (!allowLocal && internal(a)) {
                throw invalid("Webhooks cannot be sent to internal or private addresses.");
            }
        }
    }

    static boolean internal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) || first >= 240;
        }
        return (b[0] & 0xfe) == 0xfc; // IPv6 unique local fc00::/7
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
