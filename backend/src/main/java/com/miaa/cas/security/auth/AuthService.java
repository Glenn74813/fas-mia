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
import com.miaa.cas.security.auth.AuthDtos.LoginResponse;
import com.miaa.cas.security.auth.AuthDtos.LoginStatus;
import com.miaa.cas.security.auth.AuthDtos.TotpEnrollmentResponse;
import com.miaa.cas.security.jwt.TokenService;
import com.miaa.cas.security.jwt.TokenService.MfaPurpose;
import com.miaa.cas.security.mfa.Totp;
import com.miaa.cas.security.password.PasswordPolicy;
import com.miaa.cas.security.session.SessionService;
import com.miaa.cas.security.session.SessionService.IssuedTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interactive sign-in: password step, mandatory TOTP second factor (with first-time enrolment),
 * profile and password change. Failure paths commit their lockout counters and security events
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
    private final String dummyHash;

    public AuthService(UserRepository users, MfaMethodRepository mfaMethods, RoleAssignments roleAssignments,
            PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, TokenService tokens, SessionService sessions,
            SecurityEventService securityEvents, AuditService audit, AesGcmEncryptor encryptor, CasProperties properties,
            Clock clock, DevMfaBypass devMfaBypass) {
        this.devMfaBypass = devMfaBypass;
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
            return new LoginResponse(LoginStatus.DEV_MFA_BYPASS, tokens.issueMfaToken(user.getId(), MfaPurpose.VERIFY));
        }
        MfaPurpose purpose = enrolled ? MfaPurpose.VERIFY : MfaPurpose.ENROLL;
        return new LoginResponse(enrolled ? LoginStatus.MFA_REQUIRED : LoginStatus.MFA_ENROLLMENT_REQUIRED,
                tokens.issueMfaToken(user.getId(), purpose));
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
    public IssuedTokens activateTotp(String mfaToken, String code, ClientInfo client) {
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
        audit.record(actor, "MFA_ENROLLED", "USER", user.getId(), Map.of("method", MfaMethod.TOTP));
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

    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getUserType().name(), roleAssignments.roleCodes(userId),
                user.isMustChangePassword() ? java.util.Set.of() : roleAssignments.effectivePermissions(userId),
                user.isMustChangePassword(), activeTotp(userId) != null, user.getLastLoginAt());
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
