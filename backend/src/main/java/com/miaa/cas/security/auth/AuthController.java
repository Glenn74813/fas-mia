package com.miaa.cas.security.auth;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.SecurityEventService;
import com.miaa.cas.audit.SecurityEventService.Severity;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.error.ProblemFactory;
import com.miaa.cas.common.web.ClientInfo;
import com.miaa.cas.security.CasPrincipal;
import com.miaa.cas.security.auth.AuthDtos.ChangePasswordRequest;
import com.miaa.cas.security.auth.AuthDtos.CsrfResponse;
import com.miaa.cas.security.auth.AuthDtos.CurrentUserResponse;
import com.miaa.cas.security.auth.AuthDtos.ForgotPasswordRequest;
import com.miaa.cas.security.auth.AuthDtos.LoginRequest;
import com.miaa.cas.security.auth.AuthDtos.LoginResponse;
import com.miaa.cas.security.auth.AuthDtos.MfaCodeRequest;
import com.miaa.cas.security.auth.AuthDtos.MfaTokenRequest;
import com.miaa.cas.security.auth.AuthDtos.ResetPasswordRequest;
import com.miaa.cas.security.auth.AuthDtos.TokenResponse;
import com.miaa.cas.security.auth.AuthDtos.TotpEnrollmentResponse;
import com.miaa.cas.security.session.SessionService;
import com.miaa.cas.security.session.SessionService.IssuedTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Sign-in with mandatory MFA, token refresh, logout and password management")
public class AuthController {

    private final AuthService auth;
    private final PasswordRecoveryService recovery;
    private final SessionService sessions;
    private final RefreshCookies cookies;
    private final SecurityEventService securityEvents;

    public AuthController(AuthService auth, PasswordRecoveryService recovery, SessionService sessions,
            RefreshCookies cookies, SecurityEventService securityEvents) {
        this.auth = auth;
        this.recovery = recovery;
        this.sessions = sessions;
        this.cookies = cookies;
        this.securityEvents = securityEvents;
    }

    @GetMapping("/csrf")
    @Operation(summary = "Issue the CSRF cookie/token required by /refresh and /logout")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    @Operation(summary = "Password step. Always followed by an MFA step; never returns an access token")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.identifier(), request.password());
    }

    @PostMapping("/mfa/totp/enroll")
    @Operation(summary = "Start authenticator-app enrolment (requires an ENROLL mfaToken)")
    public TotpEnrollmentResponse enroll(@Valid @RequestBody MfaTokenRequest request) {
        return auth.beginTotpEnrollment(request.mfaToken());
    }

    @PostMapping("/mfa/totp/activate")
    @Operation(summary = "Confirm enrolment with a first code and sign in")
    public ResponseEntity<TokenResponse> activate(@Valid @RequestBody MfaCodeRequest request, HttpServletRequest http) {
        return tokenResponse(auth.activateTotp(request.mfaToken(), request.code(), ClientInfo.from(http)));
    }

    @PostMapping("/mfa/dev-bypass")
    @Operation(summary = "DEVELOPMENT ONLY: complete sign-in without a code for users in cas.mfa.dev-bypass-users")
    public ResponseEntity<TokenResponse> devBypass(@Valid @RequestBody MfaTokenRequest request, HttpServletRequest http) {
        return tokenResponse(auth.devBypass(request.mfaToken(), ClientInfo.from(http)));
    }

    @PostMapping("/mfa/verify")
    @Operation(summary = "Second-factor step: verify an authenticator code and sign in")
    public ResponseEntity<TokenResponse> verify(@Valid @RequestBody MfaCodeRequest request, HttpServletRequest http) {
        return tokenResponse(auth.verifyTotp(request.mfaToken(), request.code(), ClientInfo.from(http)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh-token cookie and obtain a new access token (CSRF header required)")
    public ResponseEntity<?> refresh(@CookieValue(name = "${cas.session.refresh-cookie-name}", required = false)
            String refreshToken) {
        try {
            if (refreshToken == null || refreshToken.isBlank()) {
                throw new ApiException(ErrorCode.AUTH_SESSION_INVALID, "Your session has ended. Sign in again.");
            }
            return tokenResponse(sessions.refresh(refreshToken));
        } catch (ApiException e) {
            return ResponseEntity.status(e.code().status())
                    .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                    .body(ProblemFactory.create(e.code(), e.getMessage()));
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "End the current session (CSRF header required)")
    public ResponseEntity<Void> logout(@CookieValue(name = "${cas.session.refresh-cookie-name}", required = false)
            String refreshToken) {
        sessions.logout(refreshToken).ifPresent(userId ->
                securityEvents.record(new Actor(userId, null), "LOGOUT", "SUCCESS", Severity.INFO, Map.of()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Current user, roles and effective permissions")
    public CurrentUserResponse me(@AuthenticationPrincipal CasPrincipal principal) {
        return auth.currentUser(principal.userId());
    }

    @PostMapping("/password/change")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change own password (also completes a forced change of a temporary password)")
    public void changePassword(@AuthenticationPrincipal CasPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        auth.changePassword(principal, request.currentPassword(), request.newPassword());
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request a password-reset e-mail. Always returns 202")
    public void forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        recovery.requestReset(request.identifier());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a new password with a single-use reset token")
    public void reset(@Valid @RequestBody ResetPasswordRequest request) {
        recovery.reset(request.token(), request.newPassword());
    }

    private ResponseEntity<TokenResponse> tokenResponse(IssuedTokens issued) {
        long expiresIn = Duration.between(Instant.now(), issued.accessToken().expiresAt()).toSeconds();
        TokenResponse body = new TokenResponse(issued.accessToken().value(), "Bearer", Math.max(expiresIn, 0),
                issued.refreshExpiresAt(), auth.currentUser(issued.userId()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(issued.refreshToken(), issued.refreshExpiresAt()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
