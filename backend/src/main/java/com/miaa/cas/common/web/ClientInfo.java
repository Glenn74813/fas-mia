package com.miaa.cas.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Network origin of the current request. The remote address is resolved by Tomcat's RemoteIpValve
 * ({@code server.forward-headers-strategy=native}), which only trusts forwarding headers from
 * internal proxy addresses.
 */
public record ClientInfo(String ip, String userAgent) {

    public static ClientInfo current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return from(attrs.getRequest());
        }
        return new ClientInfo(null, null);
    }

    public static ClientInfo from(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > 300) {
            ua = ua.substring(0, 300);
        }
        return new ClientInfo(request.getRemoteAddr(), ua);
    }
}
