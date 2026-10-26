package com.miaa.cas.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Application configuration bound from {@code cas.*}. Defaults live in application.yml; secrets
 * (keys, bootstrap password) are supplied through environment variables or mounted files only.
 */
@Validated
@ConfigurationProperties(prefix = "cas")
public record CasProperties(
        @NotBlank String frontendBaseUrl,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Session session,
        @Valid @NotNull Lockout lockout,
        @Valid @NotNull Password password,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Mfa mfa,
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull Bootstrap bootstrap,
        String dataEncryptionKey,
        List<String> corsAllowedOrigins) {

    public record Jwt(
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration mfaTokenTtl,
            String privateKeyPath,
            String publicKeyPath,
            String previousPublicKeyPath,
            boolean allowEphemeralKeys) {
    }

    public record Session(
            @NotNull Duration absoluteTtl,
            @NotNull Duration refreshReuseGrace,
            @NotBlank String refreshCookieName,
            @NotBlank String refreshCookiePath,
            boolean cookieSecure) {
    }

    public record Lockout(@Min(1) int maxFailures, @NotNull Duration duration) {
    }

    public record Password(@Min(12) int minLength, @Min(12) int maxLength, @NotNull Duration resetTokenTtl) {
    }

    /**
     * Sign-in endpoints are limited per client address; every other API call per signed-in user (or per address when
     * unauthenticated), counted across all instances.
     */
    public record RateLimit(boolean enabled, @Min(1) int authRequestsPerMinute,
            @DefaultValue("600") @Min(1) int apiRequestsPerMinute, @DefaultValue("120") @Min(1) int anonymousRequestsPerMinute) {
    }

    public record Mfa(@NotBlank String issuerLabel) {
    }

    public record Outbox(boolean dispatchEnabled, @Min(1) int maxAttempts) {
    }

    public record Bootstrap(String adminUsername, String adminEmail, String adminPassword) {
    }
}
