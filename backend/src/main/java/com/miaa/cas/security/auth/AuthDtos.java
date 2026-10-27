package com.miaa.cas.security.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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

    /** methods: the second factors this sign-in step accepts (TOTP, EMAIL), so the screen can offer them. */
    public record LoginResponse(LoginStatus status, String mfaToken, List<String> methods) {
    }

    public record EmailCodeRequest(@NotBlank @Size(max = 4096) String mfaToken) {
    }

    public record PasskeySignInRequest(@NotBlank @Size(max = 4096) String mfaToken, @NotNull JsonNode credential) {
    }

    public record PasskeyRegistrationRequest(@NotBlank @Size(max = 100) String name, @NotNull JsonNode credential) {
    }

    public record EmailCodeSent(String sentTo, Instant expiresAt) {
    }

    public record MfaTokenRequest(@NotBlank @Size(max = 4096) String mfaToken) {
    }

    public record MfaCodeRequest(@NotBlank @Size(max = 4096) String mfaToken,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "must be a 6-digit code") String code) {
    }

    public record TotpEnrollmentResponse(String secret, String otpauthUri) {
    }

    public record RecoveryRequest(@NotBlank @Size(max = 4096) String mfaToken, @NotBlank @Size(max = 32) String recoveryCode) {
    }

    public record RegenerateRecoveryCodesRequest(
            @NotBlank @Pattern(regexp = "\\d{6}", message = "must be a 6-digit code") String code) {
    }

    public record RecoveryCodesResponse(List<String> codes) {
    }

    /** recoveryCodes is present only right after an authenticator is enrolled: the one time they are shown. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant sessionExpiresAt,
            CurrentUserResponse user, List<String> recoveryCodes) {
    }

    public record CurrentUserResponse(UUID id, String username, String displayName, String email, String userType,
            Set<String> roles, Set<String> permissions, boolean mustChangePassword, boolean mfaEnrolled,
            int recoveryCodesRemaining, Instant lastLoginAt) {
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
