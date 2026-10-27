package com.miaa.cas.security.auth;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.SecurityEventService;
import com.miaa.cas.audit.SecurityEventService.Severity;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.error.ProblemFactory;
import com.miaa.cas.common.web.ClientInfo;
import com.miaa.cas.security.CasPrincipal;
import com.miaa.cas.security.client.ApiClientAuthenticator;
import com.miaa.cas.security.auth.AuthDtos.ChangePasswordRequest;
import com.miaa.cas.security.auth.AuthDtos.CsrfResponse;
import com.miaa.cas.security.auth.AuthDtos.CurrentUserResponse;
import com.miaa.cas.security.auth.AuthDtos.EmailCodeRequest;
import com.miaa.cas.security.auth.AuthDtos.PasskeyRegistrationRequest;
import com.miaa.cas.security.auth.AuthDtos.PasskeySignInRequest;
import com.miaa.cas.security.auth.AuthDtos.EmailCodeSent;
import com.miaa.cas.security.auth.AuthDtos.ForgotPasswordRequest;
import com.miaa.cas.security.auth.AuthDtos.LoginRequest;
import com.miaa.cas.security.auth.AuthDtos.LoginResponse;
import com.miaa.cas.security.auth.AuthDtos.MfaCodeRequest;
import com.miaa.cas.security.auth.AuthDtos.MfaTokenRequest;
import com.miaa.cas.security.auth.AuthDtos.RecoveryCodesResponse;
import com.miaa.cas.security.auth.AuthDtos.RecoveryRequest;
import com.miaa.cas.security.auth.AuthDtos.RegenerateRecoveryCodesRequest;
import com.miaa.cas.security.auth.AuthDtos.ResetPasswordRequest;
import com.miaa.cas.security.auth.AuthDtos.TokenResponse;
import com.miaa.cas.security.auth.AuthDtos.TotpEnrollmentResponse;
import com.miaa.cas.security.mfa.PasskeyService;
import com.miaa.cas.security.session.SessionService;
import com.miaa.cas.security.session.SessionService.IssuedTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ApiClientAuthenticator clients;

    public AuthController(AuthService auth, PasswordRecoveryService recovery, SessionService sessions,
            RefreshCookies cookies, SecurityEventService securityEvents, ApiClientAuthenticator clients) {
        this.clients = clients;
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
        AuthService.Enrolled enrolled = auth.activateTotp(request.mfaToken(), request.code(), ClientInfo.from(http));
        return tokenResponse(enrolled.tokens(), enrolled.recoveryCodes());
    }

    @PostMapping("/mfa/email/send")
    @Operation(summary = "Send a one-time code to the user's e-mail for this sign-in step (when enabled)")
    public ResponseEntity<EmailCodeSent> sendEmailCode(@Valid @RequestBody EmailCodeRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(auth.sendEmailCode(request.mfaToken()));
    }

    @PostMapping("/mfa/email/verify")
    @Operation(summary = "Second-factor step with the e-mailed code; signs in")
    public ResponseEntity<TokenResponse> verifyEmailCode(@Valid @RequestBody MfaCodeRequest request, HttpServletRequest http) {
        return tokenResponse(auth.verifyEmailCode(request.mfaToken(), request.code(), ClientInfo.from(http)));
    }

    @PostMapping("/mfa/passkey/options")
    @Operation(summary = "Passkey sign-in options for this sign-in step (users who have a passkey)")
    public Map<String, Object> passkeyOptions(@Valid @RequestBody EmailCodeRequest request) {
        return auth.passkeySignInOptions(request.mfaToken());
    }

    @PostMapping("/mfa/passkey/verify")
    @Operation(summary = "Second-factor step with a passkey assertion; signs in")
    public ResponseEntity<TokenResponse> passkeyVerify(@Valid @RequestBody PasskeySignInRequest request, HttpServletRequest http) {
        return tokenResponse(auth.passkeySignIn(request.mfaToken(), request.credential().toString(), ClientInfo.from(http)));
    }

    @GetMapping("/passkeys")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Own passkeys")
    public List<PasskeyService.Passkey> passkeys(@AuthenticationPrincipal CasPrincipal principal) {
        return auth.passkeys(principal);
    }

    @PostMapping("/passkeys/options")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Start adding a passkey (needs a current authenticator code)")
    public Map<String, Object> passkeyRegistrationOptions(@AuthenticationPrincipal CasPrincipal principal,
            @Valid @RequestBody RegenerateRecoveryCodesRequest request) {
        return auth.passkeyRegistrationOptions(principal, request.code());
    }

    @PostMapping("/passkeys")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Finish adding a passkey with the browser's attestation response")
    public PasskeyService.Passkey registerPasskey(@AuthenticationPrincipal CasPrincipal principal,
            @Valid @RequestBody PasskeyRegistrationRequest request) {
        return auth.registerPasskey(principal, request.name(), request.credential().toString());
    }

    @DeleteMapping("/passkeys/{id}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove one of your passkeys")
    public void removePasskey(@AuthenticationPrincipal CasPrincipal principal, @PathVariable UUID id) {
        auth.removePasskey(principal, id);
    }

    @PostMapping("/mfa/recovery")
    @Operation(summary = "Lost authenticator: spend a recovery code; continue with a new authenticator enrolment")
    public LoginResponse recover(@Valid @RequestBody RecoveryRequest request) {
        return auth.recoverWithCode(request.mfaToken(), request.recoveryCode());
    }

    @PostMapping("/recovery-codes")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Replace own recovery codes (needs a current authenticator code); the codes are shown once")
    public ResponseEntity<RecoveryCodesResponse> regenerateRecoveryCodes(@AuthenticationPrincipal CasPrincipal principal,
            @Valid @RequestBody RegenerateRecoveryCodesRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new RecoveryCodesResponse(auth.regenerateRecoveryCodes(principal, request.code())));
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

    /**
     * OAuth 2.0 client-credentials grant (RFC 6749 §4.4) for API clients: credentials in HTTP Basic or in the form body.
     * Errors use the OAuth error format rather than problem details, as OAuth client libraries expect.
     */
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Access token for an API client (grant_type=client_credentials)")
    public ResponseEntity<Map<String, Object>> clientToken(HttpServletRequest request) {
        if (!"client_credentials".equals(request.getParameter("grant_type"))) {
            return oauthError(HttpStatus.BAD_REQUEST, "unsupported_grant_type", false);
        }
        String id = request.getParameter("client_id");
        String secret = request.getParameter("client_secret");
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean basic = header != null && header.regionMatches(true, 0, "Basic ", 0, 6);
        if (basic) {
            try {
                String decoded = new String(java.util.Base64.getDecoder().decode(header.substring(6).trim()), java.nio.charset.StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon < 0) {
                    return oauthError(HttpStatus.UNAUTHORIZED, "invalid_client", true);
                }
                id = java.net.URLDecoder.decode(decoded.substring(0, colon), java.nio.charset.StandardCharsets.UTF_8);
                secret = java.net.URLDecoder.decode(decoded.substring(colon + 1), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return oauthError(HttpStatus.UNAUTHORIZED, "invalid_client", true);
            }
        }
        if (id == null || secret == null) {
            return oauthError(HttpStatus.UNAUTHORIZED, "invalid_client", basic);
        }
        return clients.issue(id, secret)
                .map(t -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header("Pragma", "no-cache")
                        .<Map<String, Object>>body(Map.of("access_token", t.accessToken(), "token_type", "Bearer", "expires_in", t.expiresIn(),
                                "scope", t.scope())))
                .orElseGet(() -> oauthError(HttpStatus.UNAUTHORIZED, "invalid_client", basic));
    }

    private static ResponseEntity<Map<String, Object>> oauthError(HttpStatus status, String error, boolean basic) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (basic) {
            b.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"cas\"");
        }
        return b.body(Map.of("error", error));
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
        return tokenResponse(issued, null);
    }

    private ResponseEntity<TokenResponse> tokenResponse(IssuedTokens issued, List<String> recoveryCodes) {
        long expiresIn = Duration.between(Instant.now(), issued.accessToken().expiresAt()).toSeconds();
        TokenResponse body = new TokenResponse(issued.accessToken().value(), "Bearer", Math.max(expiresIn, 0),
                issued.refreshExpiresAt(), auth.currentUser(issued.userId()), recoveryCodes);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(issued.refreshToken(), issued.refreshExpiresAt()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
