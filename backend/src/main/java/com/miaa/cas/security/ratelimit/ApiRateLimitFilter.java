package com.miaa.cas.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.error.ProblemFactory;
import com.miaa.cas.config.CasProperties;
import com.miaa.cas.security.CasPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * General API rate limit (MS §9.4), shared by all instances through {@link ApiRateLimiter}: per signed-in user, or
 * per client address for unauthenticated calls. Runs after bearer authentication. The unauthenticated sign-in
 * endpoints keep their own stricter per-address limit ({@link AuthRateLimitFilter}). If the counter store is
 * unavailable the request is let through and the failure logged: availability over throttling.
 */
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    private final ApiRateLimiter limiter;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final int perUser;
    private final int perAddress;

    public ApiRateLimitFilter(ApiRateLimiter limiter, CasProperties properties, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
        this.enabled = properties.rateLimit().enabled();
        this.perUser = properties.rateLimit().apiRequestsPerMinute();
        this.perAddress = properties.rateLimit().anonymousRequestsPerMinute();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !enabled || !uri.startsWith("/api/") || ("POST".equals(request.getMethod()) && uri.startsWith(AuthRateLimitFilter.PATH_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean user = auth != null && auth.getPrincipal() instanceof CasPrincipal;
        String bucket = user ? "user:" + ((CasPrincipal) auth.getPrincipal()).userId() : "addr:" + request.getRemoteAddr();
        ApiRateLimiter.Decision d;
        try {
            d = limiter.hit(bucket, user ? perUser : perAddress);
        } catch (RuntimeException e) {
            log.warn("Rate limit store unavailable; request allowed: {}", e.getClass().getSimpleName());
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("RateLimit-Limit", Integer.toString(d.limit()));
        response.setHeader("RateLimit-Remaining", Integer.toString(d.remaining()));
        response.setHeader("RateLimit-Reset", Long.toString(d.resetSeconds()));
        if (d.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(d.resetSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
                ProblemFactory.create(ErrorCode.RATE_LIMITED, "Too many requests. Try again in " + d.resetSeconds() + " seconds."));
    }
}
