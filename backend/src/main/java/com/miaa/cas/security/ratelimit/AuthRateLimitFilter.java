package com.miaa.cas.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.error.ProblemFactory;
import com.miaa.cas.config.CasProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client-IP token bucket on the unauthenticated authentication endpoints (ADR-004). Buckets
 * are per instance; the DB-backed account lockout is the cluster-wide control.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/api/v1/auth/";
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final boolean enabled;
    private final int perMinute;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(CasProperties properties, ObjectMapper mapper) {
        this.enabled = properties.rateLimit().enabled();
        this.perMinute = properties.rateLimit().authRequestsPerMinute();
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear();
        }
        Bucket bucket = buckets.computeIfAbsent(String.valueOf(request.getRemoteAddr()), k -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
                ProblemFactory.create(ErrorCode.RATE_LIMITED, "Too many requests. Try again in " + retryAfter + " seconds."));
    }

    /** Drops idle buckets (full buckets carry no state worth keeping). */
    @Scheduled(fixedDelay = 300_000)
    public void evictIdle() {
        buckets.entrySet().removeIf(e -> e.getValue().getAvailableTokens() >= perMinute);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)).build())
                .build();
    }
}
