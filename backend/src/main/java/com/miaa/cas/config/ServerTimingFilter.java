package com.miaa.cas.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Performance testing only (cas.observability.server-timing=true): adds {@code Server-Timing: app;dur=<ms>} to
 * every API response, measured from the first filter to the end of request processing. That covers
 * authentication, authorisation, business logic and the database commit, but not queueing before the
 * container accepts the request or network transfer, which is the server-side measure proposed in C-02 / C-17.
 * The response body is buffered so the header can be set after processing, so this stays off in production.
 */
@Configuration
@ConditionalOnProperty(name = "cas.observability.server-timing", havingValue = "true")
public class ServerTimingFilter {

    static final String HEADER = "Server-Timing";

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> serverTimingRegistration() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getRequestURI().startsWith("/api/");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                long start = System.nanoTime();
                ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
                try {
                    chain.doFilter(request, wrapped);
                } finally {
                    double ms = (System.nanoTime() - start) / 1_000_000.0;
                    wrapped.setHeader(HEADER, String.format(java.util.Locale.ROOT, "app;dur=%.1f", ms));
                    wrapped.copyBodyToResponse();
                }
            }
        });
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/api/*");
        return reg;
    }
}
