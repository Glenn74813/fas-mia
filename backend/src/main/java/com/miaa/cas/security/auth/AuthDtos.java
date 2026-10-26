package com.miaa.cas.security.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Request and response bodies of the authentication API. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Size(max = 254) String identifier, @NotBlank @Size(max = 128) String password) {
    }

    /** DEV_MFA_BYPASS: development only (see {@link DevMfaBypass}); complete the sign-in at /auth/mfa/dev-bypass. */
    public enum LoginStatus { MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED, DEV_MFA_BYPASS }

    public record LoginResponse(LoginStatus status, String mfaToken) {
    }

    public record MfaTokenRequest(@NotBlank @Size(max = 4096) String mfaToken) {
    }

    public record MfaCodeRequest(@NotBlank @Size(max = 4096) String mfaToken,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "must be a 6-digit code") String code) {
    }

    public record TotpEnrollmentResponse(String secret, String otpauthUri) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant sessionExpiresAt,
            CurrentUserResponse user) {
    }

    public record CurrentUserResponse(UUID id, String username, String displayName, String email, String userType,
            Set<String> roles, Set<String> permissions, boolean mustChangePassword, boolean mfaEnrolled,
            Instant lastLoginAt) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword) {
    }

    public record ForgotPasswordRequest(@NotBlank @Size(max = 254) String identifier) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 128) String newPassword) {
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
