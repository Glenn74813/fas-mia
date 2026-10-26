package com.miaa.cas.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.common.crypto.AesGcmEncryptor;
import com.miaa.cas.identity.RoleAssignments;
import com.miaa.cas.identity.domain.MfaMethod;
import com.miaa.cas.identity.domain.MfaMethodRepository;
import com.miaa.cas.identity.domain.Role;
import com.miaa.cas.identity.domain.RoleRepository;
import com.miaa.cas.identity.domain.User;
import com.miaa.cas.identity.domain.UserRepository;
import com.miaa.cas.identity.domain.UserType;
import com.miaa.cas.security.mfa.Totp;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests against real PostgreSQL (ADR-005): Testcontainers by default, or the
 * database in {@code CAS_TEST_JDBC_URL} when Docker is unavailable. The schema is cleaned and
 * re-migrated once per Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AbstractIntegrationTest.CleanMigrate.class)
public abstract class AbstractIntegrationTest {

    public static final String PASSWORD = "Corr3ct-Horse-Battery!";
    private static final String EXTERNAL_URL = System.getenv("CAS_TEST_JDBC_URL");
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(
            com.miaa.cas.common.crypto.SecureTokens.randomBytes(32));
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static PostgreSQLContainer<?> container;

    static {
        if (EXTERNAL_URL == null || EXTERNAL_URL.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:17-alpine");
            container.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (container != null) {
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> System.getenv("CAS_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("CAS_TEST_DB_PASSWORD"));
        }
        registry.add("cas.data-encryption-key", () -> ENCRYPTION_KEY);
    }

    @TestConfiguration
    static class CleanMigrate {
        @Bean
        FlywayMigrationStrategy cleanMigrate() {
            return flyway -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }

    public record TestUser(UUID id, String username, String email, String password, byte[] totpSecret) {
    }

    public record Session(String accessToken, Cookie refreshCookie) {
        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository users;
    @Autowired protected RoleRepository roles;
    @Autowired protected RoleAssignments assignments;
    @Autowired protected MfaMethodRepository mfaMethods;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected AesGcmEncryptor encryptor;
    @Autowired protected TransactionTemplate tx;

    protected static String unique(String prefix) {
        return (prefix + "." + System.nanoTime() % 1_000_000 + SEQ.incrementAndGet()).toLowerCase();
    }

    /** Creates an active user with an enrolled authenticator and the given roles. */
    protected TestUser createUser(String prefix, String... roleCodes) {
        return createUser(prefix, true, roleCodes);
    }

    protected TestUser createUser(String prefix, boolean withTotp, String... roleCodes) {
        String username = unique(prefix);
        String email = username + "@test.miaa.local";
        byte[] secret = Totp.newSecret();
        return tx.execute(s -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID(), username, email, "Test " + prefix,
                    UserType.INTERNAL, encoder.encode(PASSWORD), false, Instant.now(), "test"));
            List<UUID> roleIds = roles.findByCodeIn(List.of(roleCodes)).stream().map(Role::getId).toList();
            assignments.replaceRoles(user.getId(), roleIds, "test");
            if (withTotp) {
                MfaMethod m = new MfaMethod(UUID.randomUUID(), user.getId(), MfaMethod.TOTP,
                        encryptor.encrypt(secret, "mfa-totp:" + user.getId()), null, Instant.now());
                m.activate(0, Instant.now());
                mfaMethods.save(m);
            }
            return new TestUser(user.getId(), username, email, PASSWORD, secret);
        });
    }

    protected String currentCode(byte[] secret) {
        return Totp.code(secret, Totp.stepAt(Instant.now()));
    }

    /** Allows a fresh TOTP code in the same 30-second step (tests sign in repeatedly). */
    protected void allowCodeReuse(UUID userId) {
        jdbc.update("UPDATE mfa_methods SET last_used_step = 0 WHERE user_id = ?", userId);
    }

    protected JsonNode passwordStep(String identifier, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("identifier", identifier, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    protected Session login(TestUser user) throws Exception {
        allowCodeReuse(user.id());
        JsonNode step = passwordStep(user.username(), user.password());
        MvcResult r = mvc.perform(post("/api/v1/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("mfaToken", step.get("mfaToken").asText(),
                        "code", currentCode(user.totpSecret())))))
                .andExpect(status().isOk()).andReturn();
        return new Session(json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText(),
                r.getResponse().getCookie("CAS_REFRESH"));
    }

    /** Fetches a CSRF token; returns {cookie, headerValue}. */
    protected Object[] csrf() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token = json.readTree(r.getResponse().getContentAsString()).get("token").asText();
        return new Object[] {r.getResponse().getCookie("XSRF-TOKEN"), token};
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** A fresh Idempotency-Key for one logical request. */
    protected static String key() {
        return "test-" + UUID.randomUUID();
    }

    protected String toJson(Object o) throws Exception {
        return json.writeValueAsString(o);
    }

    protected long countSecurityEvents(UUID userId, String action) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM security_events WHERE actor_user_id = ? AND action = ?",
                Long.class, userId, action);
        return n == null ? 0 : n;
    }
}
