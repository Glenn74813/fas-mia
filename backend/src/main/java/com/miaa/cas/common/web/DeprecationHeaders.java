package com.miaa.cas.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Adds the deprecation headers of {@link ApiDeprecation} endpoints and logs each use, so remaining callers can be found. */
@Configuration
public class DeprecationHeaders implements WebMvcConfigurer, HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DeprecationHeaders.class);

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod m) {
            ApiDeprecation d = m.getMethodAnnotation(ApiDeprecation.class);
            if (d != null) {
                response.setHeader("Deprecation", "@" + LocalDate.parse(d.since()).atStartOfDay(ZoneOffset.UTC).toEpochSecond());
                response.setHeader("Sunset", DateTimeFormatter.RFC_1123_DATE_TIME.format(LocalDate.parse(d.sunset()).atStartOfDay(ZoneOffset.UTC)));
                if (!d.successor().isEmpty()) {
                    response.setHeader("Link", "<" + d.successor() + ">; rel=\"successor-version\"");
                }
                log.info("Deprecated endpoint {} {} called (sunset {})", request.getMethod(), request.getRequestURI(), d.sunset());
            }
        }
        return true;
    }
}
