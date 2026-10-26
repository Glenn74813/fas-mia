package com.miaa.cas.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.error.ProblemFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Edge-proxy gate for deployments where the SPA is served by an edge worker (Cloudflare Workers) that forwards
 * {@code /api} to this backend over a tunnel. With {@code cas.proxy.shared-secret} set:
 * <ul>
 *   <li>every {@code /api} request must carry {@value #SECRET_HEADER} with that secret (compared in constant time),
 *       so the backend cannot be called directly through its tunnel address, bypassing the edge;</li>
 *   <li>the client address the edge reports in {@value #CLIENT_IP_HEADER} becomes the request's remote address, so
 *       rate limits, sessions and the audit trail see the real user rather than the tunnel.</li>
 * </ul>
 * Unset (the default), the filter does nothing and Tomcat's forwarded-header handling applies as before.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ProxyGatewayFilter extends OncePerRequestFilter {

    public static final String SECRET_HEADER = "X-CAS-Proxy-Secret";
    public static final String CLIENT_IP_HEADER = "X-CAS-Client-IP";

    /** An IPv4 or IPv6 literal; anything else is ignored. */
    private static final Pattern IP = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9A-Fa-f:]{2,39})$");

    private final byte[] secret;
    private final ObjectMapper mapper;

    public ProxyGatewayFilter(@Value("${cas.proxy.shared-secret:}") String secret, ObjectMapper mapper) {
        this.secret = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return secret == null || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(SECRET_HEADER);
        if (presented == null || !MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ProblemFactory.create(ErrorCode.ACCESS_DENIED,
                    "This API is only reachable through the CAS web application."));
            return;
        }
        String client = request.getHeader(CLIENT_IP_HEADER);
        if (client == null || !IP.matcher(client.trim()).matches()) {
            chain.doFilter(request, response);
            return;
        }
        String address = client.trim();
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getRemoteAddr() {
                return address;
            }

            @Override
            public String getRemoteHost() {
                return address;
            }
        }, response);
    }
}
