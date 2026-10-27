package com.miaa.cas.security.client;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.SecurityEventService;
import com.miaa.cas.audit.SecurityEventService.Severity;
import com.miaa.cas.security.CallerResolver;
import com.miaa.cas.security.CasPrincipal;
import com.miaa.cas.security.jwt.TokenService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 2.0 client-credentials sign-in for API clients (TOR §4.6, RFC 6749 §4.4) and their resolution on every request.
 * A client gets only read scopes from {@link #SCOPES}; its status and expiry are checked on each request, so a
 * revoked client's tokens stop working at once. A client is its own caller for rate limiting and the audit trail.
 */
@Service
public class ApiClientAuthenticator {

    /** The scopes a client may hold: read access to the finance areas, nothing that changes data. */
    public static final Set<String> SCOPES = Set.of("LEDGER_VIEW", "AP_VIEW", "AR_VIEW", "TREASURY_VIEW", "BUDGET_VIEW", "FA_VIEW",
            "INVENTORY_VIEW", "FINANCIAL_REPORT_VIEW");
    public static final String ROLE = "ROLE_API_CLIENT";

    public record IssuedToken(String accessToken, long expiresIn, String scope) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final SecurityEventService securityEvents;
    private final Clock clock;
    private final String dummyHash;

    public ApiClientAuthenticator(JdbcTemplate jdbc, PasswordEncoder encoder, TokenService tokens, SecurityEventService securityEvents,
            Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.tokens = tokens;
        this.securityEvents = securityEvents;
        this.clock = clock;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /** Checks the client's credentials; empty when they are wrong, unknown, revoked or expired (always the same answer). */
    @Transactional
    public Optional<IssuedToken> issue(String clientId, String secret) {
        Instant now = Instant.now(clock);
        List<Map<String, Object>> rows = clientId == null || clientId.length() > 40 ? List.of()
                : jdbc.queryForList("SELECT id, secret_hash, status, expires_at, scopes FROM api_clients WHERE client_id = ?", clientId);
        Map<String, Object> c = rows.isEmpty() ? null : rows.get(0);
        // Hash even for an unknown client, so response time does not reveal which client ids exist.
        boolean matches = encoder.matches(secret == null ? "" : secret, c == null ? dummyHash : (String) c.get("secret_hash"));
        boolean usable = c != null && matches && "ACTIVE".equals(c.get("status"))
                && (c.get("expires_at") == null || ((Timestamp) c.get("expires_at")).toInstant().isAfter(now));
        Actor actor = new Actor(c == null ? null : (UUID) c.get("id"), "api:" + (clientId == null ? "" : clientId.length() > 40
                ? clientId.substring(0, 40) : clientId));
        if (!usable) {
            securityEvents.record(actor, "API_CLIENT_AUTH_FAILED", "FAILURE", Severity.MEDIUM,
                    Map.of("reason", c == null ? "unknown client" : !matches ? "wrong secret" : "revoked or expired"));
            return Optional.empty();
        }
        UUID id = (UUID) c.get("id");
        List<String> scopes = scopes(c.get("scopes"));
        jdbc.update("UPDATE api_clients SET last_used_at = ? WHERE id = ?", Timestamp.from(now), id);
        TokenService.AccessToken t = tokens.issueClientToken(id, clientId, scopes);
        securityEvents.record(actor, "API_CLIENT_TOKEN_ISSUED", "SUCCESS", Severity.INFO, Map.of("scopes", String.join(" ", scopes)));
        return Optional.of(new IssuedToken(t.value(), Math.max(1, t.expiresAt().getEpochSecond() - now.getEpochSecond()),
                String.join(" ", scopes)));
    }

    /** The live client behind a token: still active and unexpired, with its current scopes. */
    @Transactional(readOnly = true)
    public Optional<CallerResolver.Caller> resolve(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT client_id, scopes FROM api_clients WHERE id = ? AND status = 'ACTIVE' "
                + "AND (expires_at IS NULL OR expires_at > ?)", id, Timestamp.from(Instant.now(clock)));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(ROLE));
        scopes(rows.get(0).get("scopes")).forEach(s -> authorities.add(new SimpleGrantedAuthority("PERM_" + s)));
        return Optional.of(new CallerResolver.Caller(new CasPrincipal(id, "api:" + rows.get(0).get("client_id"), id), authorities));
    }

    private static List<String> scopes(Object array) {
        try {
            Object[] values = (Object[]) ((java.sql.Array) array).getArray();
            return Arrays.stream(values).map(Object::toString).filter(SCOPES::contains).sorted().toList();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
