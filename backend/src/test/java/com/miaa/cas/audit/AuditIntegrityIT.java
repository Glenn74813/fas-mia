package com.miaa.cas.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miaa.cas.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class AuditIntegrityIT extends AbstractIntegrationTest {

    @Autowired
    EventStore store;

    @Test
    void chainVerifies() throws Exception {
        Session auditor = login(createUser("auditor", "INTERNAL_AUDITOR"));
        mvc.perform(post("/api/v1/audit-events/verification").header("Authorization", auditor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audit.intact").value(true))
                .andExpect(jsonPath("$.security.intact").value(true));
        mvc.perform(get("/api/v1/audit-events").param("action", "AUDIT_CHAIN_VERIFIED")
                .header("Authorization", auditor.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].action").value("AUDIT_CHAIN_VERIFIED"));
        mvc.perform(get("/api/v1/security-events").param("action", "LOGIN_SUCCEEDED")
                .header("Authorization", auditor.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].severity").value("INFO"));
    }

    @Test
    void updateAndDeleteAreRejected() {
        createUser("immut", "ACCOUNTANT");
        login_quietly();
        assertThatThrownBy(() -> jdbc.update("UPDATE security_events SET action = 'X'"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM security_events"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_events"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
    }

    @Test
    void tamperingIsDetected() {
        login_quietly();
        // Simulate a privileged attacker who bypasses the trigger; rolled back afterwards.
        tx.executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE security_events DISABLE TRIGGER trg_security_events_immutable");
            jdbc.update("UPDATE security_events SET details = '{\"tampered\": true}'::jsonb WHERE id = (SELECT min(id) FROM security_events)");
            EventStore.ChainVerification result = store.verify(EventStore.Table.SECURITY);
            assertThat(result.intact()).isFalse();
            assertThat(result.firstInvalidSequence()).isNotNull();
            status.setRollbackOnly();
        });
        assertThat(store.verify(EventStore.Table.SECURITY).intact()).isTrue();
    }

    @Test
    void concurrentWritersKeepChainLinear() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 15; i++) {
                    tx.executeWithoutResult(s -> store.insert(EventStore.Table.AUDIT, Actor.SYSTEM, "CONCURRENCY_TEST",
                            "TEST", null, "SUCCESS", null, java.util.Map.of("n", 1)));
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'CONCURRENCY_TEST'", Long.class))
                .isEqualTo(120);
        assertThat(store.verify(EventStore.Table.AUDIT).intact()).isTrue();
        // Committed inserts take consecutive ids (only rolled-back transactions leave gaps).
        Long span = jdbc.queryForObject("SELECT max(id) - min(id) + 1 FROM audit_events WHERE action = 'CONCURRENCY_TEST'",
                Long.class);
        assertThat(span).isEqualTo(120);
    }

    private void login_quietly() {
        try {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                    .content("{\"identifier\":\"nobody.x\",\"password\":\"Not-Valid-123!\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
