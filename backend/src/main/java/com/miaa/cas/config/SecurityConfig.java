package com.miaa.cas.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.common.crypto.AesGcmEncryptor;
import com.miaa.cas.security.CasJwtAuthenticationConverter;
import com.miaa.cas.security.ProblemSecurityHandlers;
import com.miaa.cas.security.jwt.TokenService;
import com.miaa.cas.security.ratelimit.ApiRateLimitFilter;
import com.miaa.cas.security.ratelimit.ApiRateLimiter;
import com.miaa.cas.security.ratelimit.AuthRateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Deny-by-default security baseline (docs/05-security-design.md). Stateless bearer-token API; the
 * only cookie-authenticated endpoints (/auth/refresh, /auth/logout) are CSRF-protected.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_AUTH_POSTS = {
        "/api/v1/auth/login", "/api/v1/auth/mfa/**", "/api/v1/auth/refresh", "/api/v1/auth/logout",
        "/api/v1/auth/password/forgot", "/api/v1/auth/password/reset", "/api/v1/auth/token"
    };

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, TokenService tokens,
            CasJwtAuthenticationConverter converter, ProblemSecurityHandlers handlers, CasProperties properties,
            org.springframework.core.env.Environment env, ApiRateLimiter rateLimiter, ObjectMapper mapper) throws Exception {
        boolean openApiEnabled = env.getProperty("springdoc.api-docs.enabled", Boolean.class, true);
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(c -> c.secure(properties.session().cookieSecure()).sameSite("Strict").path("/"));
        RequestMatcher cookieEndpoints = new OrRequestMatcher(
                antMatcher(HttpMethod.POST, "/api/v1/auth/refresh"), antMatcher(HttpMethod.POST, "/api/v1/auth/logout"));

        http
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher(cookieEndpoints))
            .addFilterAfter(new CsrfCookieMaterializer(), BasicAuthenticationFilter.class)
            .addFilterAfter(new ApiRateLimitFilter(rateLimiter, properties, mapper), BearerTokenAuthenticationFilter.class)
            .cors(c -> c.configurationSource(corsSource(properties.corsAllowedOrigins())))
            .headers(h -> h
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                                    + "object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"))
                    .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                    .frameOptions(f -> f.deny())
                    .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()")))
            .authorizeHttpRequests(a -> {
                a.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
                a.requestMatchers("/actuator/prometheus").permitAll(); // expose only on the internal management port in prod
                a.requestMatchers(HttpMethod.POST, PUBLIC_AUTH_POSTS).permitAll();
                a.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll();
                if (openApiEnabled) {
                    a.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                }
                a.requestMatchers("/api/v1/**").authenticated();
                a.anyRequest().denyAll();
            })
            .oauth2ResourceServer(o -> o
                    .jwt(j -> j.decoder(tokens.apiDecoder()).jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(handlers)
                    .accessDeniedHandler(handlers))
            .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .requestCache(r -> r.disable());
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilter(CasProperties properties, ObjectMapper mapper) {
        FilterRegistrationBean<AuthRateLimitFilter> reg = new FilterRegistrationBean<>(new AuthRateLimitFilter(properties, mapper));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    @Bean
    public AesGcmEncryptor dataEncryptor(CasProperties properties) {
        return AesGcmEncryptor.fromBase64(properties.dataEncryptionKey());
    }

    private static CorsConfigurationSource corsSource(List<String> allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            CorsConfiguration cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(allowedOrigins);
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN", "X-Request-Id", "Idempotency-Key"));
            cfg.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
            cfg.setAllowCredentials(true);
            cfg.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", cfg);
        }
        return source;
    }

    /** Forces the deferred CSRF token to be generated so the XSRF-TOKEN cookie is always issued. */
    static final class CsrfCookieMaterializer extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getRequestURI().startsWith("/api/v1/auth/")) {
                CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                if (token != null) {
                    token.getToken();
                }
            }
            chain.doFilter(request, response);
        }
    }
}
