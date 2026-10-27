package com.miaa.cas.security.auth;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.audit.SecurityEventService;
import com.miaa.cas.audit.SecurityEventService.Severity;
import com.miaa.cas.common.crypto.AesGcmEncryptor;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.ClientInfo;
import com.miaa.cas.config.CasProperties;
import com.miaa.cas.identity.RoleAssignments;
import com.miaa.cas.identity.domain.MfaMethod;
import com.miaa.cas.identity.domain.MfaMethodRepository;
import com.miaa.cas.identity.domain.User;
import com.miaa.cas.identity.domain.UserRepository;
import com.miaa.cas.security.CasPrincipal;
import com.miaa.cas.security.auth.AuthDtos.CurrentUserResponse;
import com.miaa.cas.security.auth.AuthDtos.EmailCodeSent;
import com.miaa.cas.security.auth.AuthDtos.LoginResponse;
import com.miaa.cas.security.auth.AuthDtos.LoginStatus;
import com.miaa.cas.security.auth.AuthDtos.TotpEnrollmentResponse;
import com.miaa.cas.security.jwt.TokenService;
import com.miaa.cas.security.jwt.TokenService.MfaPurpose;
import com.miaa.cas.security.mfa.EmailOtpService;
import com.miaa.cas.security.mfa.PasskeyService;
import com.miaa.cas.security.mfa.RecoveryCodeService;
import com.miaa.cas.security.mfa.Totp;
import com.miaa.cas.security.password.PasswordPolicy;
import com.miaa.cas.security.session.SessionService;
import com.miaa.cas.security.session.SessionService.IssuedTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interactive sign-in: password step, mandatory TOTP second factor (with first-time enrolment and one-time recovery
 * codes for a lost authenticator), profile and password change. Failure paths commit their lockout counters and security events
 * ({@code noRollbackFor}) before the exception reaches the client.
 */
@Service
public class AuthService {

    private static final String GENERIC_FAILURE = "Sign-in failed. Check your credentials or contact the system administrator.";
    private static final int TOTP_WINDOW = 1;

    private final UserRepository users;
    private final MfaMethodRepository mfaMethods;
    private final RoleAssignments roleAssignments;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TokenService tokens;
    private final SessionService sessions;
    private final SecurityEventService securityEvents;
    private final AuditService audit;
    private final AesGcmEncryptor encryptor;
    private final CasProperties properties;
    private final Clock clock;
    private final DevMfaBypass devMfaBypass;
    private final RecoveryCodeService recoveryCodes;
    private final EmailOtpService emailOtp;
    private final PasskeyService passkeys;
    private final String dummyHash;

    /** A completed enrolment: the session, and the recovery codes to show once. */
    public record Enrolled(IssuedTokens tokens, List<String> recoveryCodes) {
    }

    public AuthService(UserRepository users, MfaMethodRepository mfaMethods, RoleAssignments roleAssignments,
            PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, TokenService tokens, SessionService sessions,
            SecurityEventService securityEvents, AuditService audit, AesGcmEncryptor encryptor, CasProperties properties,
            Clock clock, DevMfaBypass devMfaBypass, RecoveryCodeService recoveryCodes, EmailOtpService emailOtp,
            PasskeyService passkeys) {
        this.passkeys = passkeys;
        this.devMfaBypass = devMfaBypass;
        this.emailOtp = emailOtp;
        this.recoveryCodes = recoveryCodes;
        this.users = users;
        this.mfaMethods = mfaMethods;
        this.roleAssignments = roleAssignments;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.tokens = tokens;
        this.sessions = sessions;
        this.securityEvents = securityEvents;
        this.audit = audit;
        this.encryptor = encryptor;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse login(String identifier, String password) {
        Instant now = Instant.now(clock);
        User user = users.findByIdentifier(identifier.trim()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash); // equalise timing for unknown accounts
            securityEvents.record(Actor.anonymous(identifier), "LOGIN_FAILED", "FAILURE", Severity.LOW,
                    Map.of("reason", "UNKNOWN_ACCOUNT"));
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, GENERIC_FAILURE);
        }
        user = users.findByIdForUpdate(user.getId()).orElseThrow();
        Actor actor = new Actor(user.getId(), user.getUsername());
        if (user.isLocked(now)) {
            securityEvents.record(actor, "LOGIN_BLOCKED", "DENIED", Severity.MEDIUM, Map.of("reason", "LOCKED"));
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, GENERIC_FAILURE);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            registerFailure(user, actor, "LOGIN_FAILED", now);
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, GENERIC_FAILURE);
        }
        if (!user.canSignIn(now)) {
            securityEvents.record(actor, "LOGIN_BLOCKED", "DENIED", Severity.MEDIUM,
                    Map.of("reason", "INACTIVE_OR_EXPIRED"));
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, GENERIC_FAILURE);
        }
        user.clearFailures();
        boolean enrolled = activeTotp(user.getId()) != null;
        securityEvents.record(actor, "LOGIN_PASSWORD_VERIFIED", "SUCCESS", Severity.INFO, Map.of());
        if (devMfaBypass.applies(user.getUsername())) {
            return new LoginResponse(LoginStatus.DEV_MFA_BYPASS, tokens.issueMfaToken(user.getId(), MfaPurpose.VERIFY), List.of());
        }
        MfaPurpose purpose = enrolled ? MfaPurpose.VERIFY : MfaPurpose.ENROLL;
        return new LoginResponse(enrolled ? LoginStatus.MFA_REQUIRED : LoginStatus.MFA_ENROLLMENT_REQUIRED,
                tokens.issueMfaToken(user.getId(), purpose), enrolled ? verifyMethods(user) : List.of("TOTP"));
    }

    @Transactional
    public TotpEnrollmentResponse beginTotpEnrollment(String mfaToken) {
        // Row lock serialises concurrent enrolment requests (e.g. a double-submitted page) per user.
        TokenService.MfaChallenge challenge = tokens.parseMfaToken(mfaToken, MfaPurpose.ENROLL);
        User user = lockedCandidate(challenge.userId());
        if (activeTotp(user.getId()) != null) {
            throw new ApiException(ErrorCode.MFA_ALREADY_ENROLLED,
                    "An authenticator is already enrolled. Ask an administrator to reset it.");
        }
        Instant now = Instant.now(clock);
        MfaMethod pending = mfaMethods.findByUserIdAndMethodTypeAndStatus(user.getId(), MfaMethod.TOTP, MfaMethod.PENDING)
                .orElse(null);
        byte[] secret;
        if (pending != null && challenge.challengeId().equals(pending.getEnrollmentChallengeId())) {
            // Same sign-in challenge: return the secret already issued so duplicate requests converge.
            secret = encryptor.decrypt(pending.getSecretCiphertext(), aad(user.getId()));
        } else {
            // New challenge: always a fresh secret, so a secret seen in an earlier attempt is never reused.
            secret = Totp.newSecret();
            byte[] ciphertext = encryptor.encrypt(secret, aad(user.getId()));
            if (pending != null) {
                pending.replacePendingSecret(ciphertext, challenge.challengeId(), now);
            } else {
                mfaMethods.save(new MfaMethod(UUID.randomUUID(), user.getId(), MfaMethod.TOTP, ciphertext,
                        challenge.challengeId(), now));
            }
        }
        String base32 = Totp.toBase32(secret);
        return new TotpEnrollmentResponse(base32,
                Totp.otpauthUri(properties.mfa().issuerLabel(), user.getUsername(), base32));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Enrolled activateTotp(String mfaToken, String code, ClientInfo client) {
        UUID userId = tokens.parseMfaToken(mfaToken, MfaPurpose.ENROLL).userId();
        User user = lockedCandidate(userId);
        Instant now = Instant.now(clock);
        MfaMethod pending = mfaMethods.findByUserIdAndMethodTypeAndStatus(userId, MfaMethod.TOTP, MfaMethod.PENDING)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE, "Start authenticator enrolment first."));
        OptionalLong step = Totp.verify(encryptor.decrypt(pending.getSecretCiphertext(), aad(userId)), code, now, null,
                TOTP_WINDOW);
        Actor actor = new Actor(user.getId(), user.getUsername());
        if (step.isEmpty()) {
            registerFailure(user, actor, "MFA_FAILED", now);
            throw new ApiException(ErrorCode.AUTH_MFA_INVALID, "The verification code is not valid.");
        }
        pending.activate(step.getAsLong(), now);
        List<String> codes = recoveryCodes.issue(userId, now);
        audit.record(actor, "MFA_ENROLLED", "USER", user.getId(), Map.of("method", MfaMethod.TOTP, "recoveryCodesIssued", codes.size()));
        return new Enrolled(completeSignIn(user, actor, client, now), codes);
    }

    /**
     * Lost authenticator: a recovery code replaces the authenticator code at the second step. The code is spent, the
     * old authenticator and the user's other sessions are withdrawn, and the user must enrol a new authenticator now
     * (which issues a fresh set of codes) before any session starts. Failures count towards the account lockout.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse recoverWithCode(String mfaToken, String recoveryCode) {
        UUID userId = tokens.parseMfaToken(mfaToken, MfaPurpose.VERIFY).userId();
        User user = lockedCandidate(userId);
        Instant now = Instant.now(clock);
        Actor actor = new Actor(user.getId(), user.getUsername());
        if (!recoveryCodes.consume(userId, recoveryCode, now)) {
            registerFailure(user, actor, "MFA_RECOVERY_FAILED", now);
            throw new ApiException(ErrorCode.AUTH_MFA_INVALID, "The recovery code is not valid or has already been used.");
        }
        List<MfaMethod> active = mfaMethods.findByUserIdAndStatus(userId, MfaMethod.ACTIVE);
        active.forEach(m -> m.revoke(now));
        int revokedSessions = sessions.revokeAll(userId, "MFA_RECOVERED");
        int left = recoveryCodes.remaining(userId);
        securityEvents.record(actor, "MFA_RECOVERY_CODE_USED", "SUCCESS", Severity.MEDIUM,
                Map.of("authenticatorsRevoked", active.size(), "sessionsRevoked", revokedSessions, "codesLeft", left));
        audit.record(actor, "MFA_RECOVERED", "USER", userId, Map.of("authenticatorsRevoked", active.size()));
        return new LoginResponse(LoginStatus.MFA_ENROLLMENT_REQUIRED, tokens.issueMfaToken(userId, MfaPurpose.ENROLL), List.of("TOTP"));
    }

    /** Replaces the signed-in user's recovery codes; needs a current authenticator code, so a stolen session cannot. */
    @Transactional(noRollbackFor = ApiException.class)
    public List<String> regenerateRecoveryCodes(CasPrincipal principal, String code) {
        User user = users.findByIdForUpdate(principal.userId()).orElseThrow(() -> ApiException.notFound("User"));
        Instant now = Instant.now(clock);
        Actor actor = new Actor(user.getId(), user.getUsername());
        requireCurrentCode(user, actor, code, now);
        List<String> codes = recoveryCodes.issue(user.getId(), now);
        audit.record(actor, "MFA_RECOVERY_CODES_REGENERATED", "USER", user.getId(), Map.of("issued", codes.size()));
        return codes;
    }

    /** A current authenticator code from the signed-in user, so a stolen session alone cannot change their factors. */
    private void requireCurrentCode(User user, Actor actor, String code, Instant now) {
        MfaMethod method = activeTotp(user.getId());
        if (method == null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Set up an authenticator first.");
        }
        OptionalLong step = Totp.verify(encryptor.decrypt(method.getSecretCiphertext(), aad(user.getId())), code, now,
                method.getLastUsedStep(), TOTP_WINDOW);
        if (step.isEmpty()) {
            registerFailure(user, actor, "MFA_FAILED", now);
            throw new ApiException(ErrorCode.AUTH_MFA_INVALID, "The verification code is not valid.");
        }
        method.markUsed(step.getAsLong());
    }

    /** Starts adding a passkey: needs a current authenticator code; returns the options for the browser. */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> passkeyRegistrationOptions(CasPrincipal principal, String code) {
        User user = users.findByIdForUpdate(principal.userId()).orElseThrow(() -> ApiException.notFound("User"));
        Instant now = Instant.now(clock);
        requireCurrentCode(user, new Actor(user.getId(), user.getUsername()), code, now);
        return passkeys.registrationOptions(user.getId(), user.getUsername(), user.getDisplayName(), now);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public PasskeyService.Passkey registerPasskey(CasPrincipal principal, String name, String responseJson) {
        User user = users.findByIdForUpdate(principal.userId()).orElseThrow(() -> ApiException.notFound("User"));
        Instant now = Instant.now(clock);
        PasskeyService.Passkey p = passkeys.register(user.getId(), name, responseJson, now);
        Actor actor = new Actor(user.getId(), user.getUsername());
        audit.record(actor, "PASSKEY_REGISTERED", "USER", user.getId(), Map.of("passkey", p.id().toString(), "name", p.name()));
        securityEvents.record(actor, "PASSKEY_REGISTERED", "SUCCESS", Severity.MEDIUM, Map.of("name", p.name()));
        return p;
    }

    @Transactional(readOnly = true)
    public List<PasskeyService.Passkey> passkeys(CasPrincipal principal) {
        return passkeys.list(principal.userId());
    }

    @Transactional
    public void removePasskey(CasPrincipal principal, UUID passkeyId) {
        if (!passkeys.revoke(principal.userId(), passkeyId, Instant.now(clock))) {
            throw ApiException.notFound("Passkey");
        }
        audit.record(new Actor(principal.userId(), principal.username()), "PASSKEY_REMOVED", "USER", principal.userId(),
                Map.of("passkey", passkeyId.toString()));
    }

    /** Options for signing in with a passkey at this sign-in step (users who have one). */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> passkeySignInOptions(String mfaToken) {
        TokenService.MfaChallenge challenge = tokens.parseMfaToken(mfaToken, MfaPurpose.VERIFY);
        User user = lockedCandidate(challenge.userId());
        if (!passkeys.has(user.getId())) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_INVALID, "The sign-in step is not valid. Start again.");
        }
        return passkeys.authenticationOptions(user.getId(), challenge.challengeId(), Instant.now(clock));
    }

    /** Completes the second step with a passkey; a failed assertion counts towards the lockout. */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens passkeySignIn(String mfaToken, String responseJson, ClientInfo client) {
        TokenService.MfaChallenge challenge = tokens.parseMfaToken(mfaToken, MfaPurpose.VERIFY);
        User user = lockedCandidate(challenge.userId());
        Instant now = Instant.now(clock);
        Actor actor = new Actor(user.getId(), user.getUsername());
        if (!passkeys.authenticate(user.getId(), challenge.challengeId(), responseJson, now)) {
            registerFailure(user, actor, "MFA_FAILED", now);
            throw new ApiException(ErrorCode.AUTH_MFA_INVALID, "The passkey could not be verified. Try again or use your authenticator app.");
        }
        securityEvents.record(actor, "MFA_PASSKEY_USED", "SUCCESS", Severity.INFO, Map.of());
        return completeSignIn(user, actor, client, now);
    }

    /** Development only: completes the sign-in of a user named in cas.mfa.dev-bypass-users without a code. */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens devBypass(String mfaToken, ClientInfo client) {
        UUID userId = tokens.parseMfaToken(mfaToken, MfaPurpose.VERIFY).userId();
        User user = lockedCandidate(userId);
        if (!devMfaBypass.applies(user.getUsername())) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_INVALID, "The sign-in step is not valid. Start again.");
        }
        Actor actor = new Actor(user.getId(), user.getUsername());
        securityEvents.record(actor, "MFA_BYPASSED_DEV", "SUCCESS", Severity.MEDIUM, Map.of("setting", "cas.mfa.dev-bypass-users"));
        return completeSignIn(user, actor, client, Instant.now(clock));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens verifyTotp(String mfaToken, String code, ClientInfo client) {
        UUID userId = tokens.parseMfaToken(mfaToken, MfaPurpose.VERIFY).userId();
        User user = lockedCandidate(userId);
        Instant now = Instant.now(clock);
        MfaMethod method = activeTotp(userId);
        if (method == null) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_INVALID, "The sign-in step is not valid. Start again.");
        }
        OptionalLong step = Totp.verify(encryptor.decrypt(method.getSecretCiphertext(), aad(userId)), code, now,
                method.getLastUsedStep(), TOTP_WINDOW);
        Actor actor = new Actor(user.getId(), user.getUsername());
        if (step.isEmpty()) {
            registerFailure(user, actor, "MFA_FAILED", now);
            throw new ApiException(ErrorCode.AUTH_MFA_INVALID, "The verification code is not valid.");
        }
        method.markUsed(step.getAsLong());
        return completeSignIn(user, actor, client, now);
    }

    /**
     * Sends a one-time code to the user's e-mail for this sign-in step (an enrolled user's alternative to the
     * authenticator, TOR §4.2). Returns the masked address and when the code expires.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public EmailCodeSent sendEmailCode(String mfaToken) {
        TokenService.MfaChallenge challenge = tokens.parseMfaToken(mfaToken, MfaPurpose.VERIFY);
        User user = lockedCandidate(challenge.userId());
        if (activeTotp(user.getId()) == null) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_INVALID, "The sign-in step is not valid. Start again.");
        }
        Instant now = Instant.now(clock);
        EmailOtpService.Sent sent = emailOtp.send(user.getId(), challenge.challengeId(), user.getEmail(), challenge.expiresAt(), now);
        securityEvents.record(new Actor(user.getId(), user.getUsername()), "MFA_EMAIL_CODE_SENT", "SUCCESS", Severity.INFO, Map.of());
        return new EmailCodeSent(sent.maskedEmail(), sent.expiresAt());
    }

    /** Completes the second step with the e-mailed code; a wrong code counts towards the lockout. */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens verifyEmailCode(String mfaToken, String code, ClientInfo client) {
        TokenService.MfaChallenge challenge = tokens.parseMfaToken(mfaToken, MfaPurpose.VERIFY);
        User user = lockedCandidate(challenge.userId());
        Instant now = Instant.now(clock);
        Actor actor = new Actor(user.getId(), user.getUsername());
        if (!emailOtp.enabled() || !emailOtp.verify(user.getId(), challenge.challengeId(), code, now)) {
            registerFailure(user, actor, "MFA_FAILED", now);
            throw new ApiException(ErrorCode.AUTH_MFA_INVALID, "The code is not valid or has expired. Request a new one.");
        }
        securityEvents.record(actor, "MFA_EMAIL_CODE_USED", "SUCCESS", Severity.INFO, Map.of());
        return completeSignIn(user, actor, client, now);
    }

    private List<String> verifyMethods(User user) {
        List<String> methods = new java.util.ArrayList<>(List.of("TOTP"));
        if (emailOtp.enabled() && user.getEmail() != null && !user.getEmail().isBlank()) {
            methods.add("EMAIL");
        }
        if (passkeys.has(user.getId())) {
            methods.add("PASSKEY");
        }
        return methods;
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getUserType().name(), roleAssignments.roleCodes(userId),
                user.isMustChangePassword() ? java.util.Set.of() : roleAssignments.effectivePermissions(userId),
                user.isMustChangePassword(), activeTotp(userId) != null, recoveryCodes.remaining(userId), user.getLastLoginAt());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void changePassword(CasPrincipal principal, String currentPassword, String newPassword) {
        User user = users.findByIdForUpdate(principal.userId()).orElseThrow(() -> ApiException.notFound("User"));
        Instant now = Instant.now(clock);
        Actor actor = new Actor(user.getId(), user.getUsername());
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            registerFailure(user, actor, "PASSWORD_CHANGE_FAILED", now);
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, "The current password is incorrect.");
        }
        passwordPolicy.enforce(newPassword, user.getUsername(), user.getEmail());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATION, "The new password must differ from the current one.");
        }
        user.changePassword(passwordEncoder.encode(newPassword), false, now, user.getUsername());
        int revoked = sessions.revokeAllExcept(user.getId(), principal.sessionId(), "PASSWORD_CHANGED");
        audit.record(actor, "PASSWORD_CHANGED", "USER", user.getId(), Map.of("otherSessionsRevoked", revoked));
    }

    private IssuedTokens completeSignIn(User user, Actor actor, ClientInfo client, Instant now) {
        user.registerSuccessfulLogin(now);
        IssuedTokens issued = sessions.start(user, client);
        securityEvents.record(actor, "LOGIN_SUCCEEDED", "SUCCESS", Severity.INFO, Map.of());
        return issued;
    }

    private void registerFailure(User user, Actor actor, String action, Instant now) {
        CasProperties.Lockout lockout = properties.lockout();
        boolean locked = user.registerFailure(lockout.maxFailures(), now.plus(lockout.duration()));
        securityEvents.record(actor, action, "FAILURE", Severity.LOW, Map.of());
        if (locked) {
            securityEvents.record(actor, "ACCOUNT_LOCKED", "DENIED", Severity.HIGH,
                    Map.of("lockedForSeconds", lockout.duration().toSeconds()));
        }
    }

    private User signInCandidate(UUID userId) {
        return checkCandidate(users.findById(userId).orElse(null));
    }

    private User lockedCandidate(UUID userId) {
        return checkCandidate(users.findByIdForUpdate(userId).orElse(null));
    }

    private User checkCandidate(User user) {
        if (user == null) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_INVALID, GENERIC_FAILURE);
        }
        Instant now = Instant.now(clock);
        if (!user.canSignIn(now) || user.isLocked(now)) {
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, GENERIC_FAILURE);
        }
        return user;
    }

    private MfaMethod activeTotp(UUID userId) {
        return mfaMethods.findByUserIdAndMethodTypeAndStatus(userId, MfaMethod.TOTP, MfaMethod.ACTIVE).orElse(null);
    }

    static String aad(UUID userId) {
        return "mfa-totp:" + userId;
    }
}
