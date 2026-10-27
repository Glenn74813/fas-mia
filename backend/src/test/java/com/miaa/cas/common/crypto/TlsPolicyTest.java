package com.miaa.cas.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** TLS 1.3 minimum (DICT DC No. 007, s. 2020): what the prod profile refuses, and the parameters clients get. */
class TlsPolicyTest {

    private static final String[] TLS13 = {"TLSv1.3"};

    @Test
    void tls13WithAPinnedJvmAndALocalOrVerifiedDatabaseMeetsThePolicy() {
        assertThat(TlsPolicy.check(TLS13, "", "TLSv1.3", "jdbc:postgresql://127.0.0.1:5432/miacas_db")).isEmpty();
        assertThat(TlsPolicy.check(TLS13, "", "TLSv1.3", "jdbc:postgresql://db.internal:5432/cas?sslmode=verify-full")).isEmpty();
        assertThat(TlsPolicy.check(TLS13, "", "TLSv1.3", "jdbc:postgresql://localhost/cas")).isEmpty();
    }

    @Test
    void eachBreachIsNamed() {
        assertThat(TlsPolicy.check(TLS13, "", null, "jdbc:postgresql://127.0.0.1/cas"))
                .singleElement().asString().contains("-Djdk.tls.client.protocols=TLSv1.3");
        assertThat(TlsPolicy.check(TLS13, "", "TLSv1.2,TLSv1.3", "jdbc:postgresql://127.0.0.1/cas")).hasSize(1);
        assertThat(TlsPolicy.check(TLS13, "", "TLSv1.3", "jdbc:postgresql://db.internal:5432/cas"))
                .singleElement().asString().contains("sslmode=verify-full");
        assertThat(TlsPolicy.check(TLS13, "", "TLSv1.3", "jdbc:postgresql://db.internal:5432/cas?sslmode=require")).hasSize(1);
        List<String> legacy = TlsPolicy.check(new String[] {"TLSv1.3", "TLSv1.2"}, " ", "TLSv1.3,TLSv1.2", "jdbc:postgresql://127.0.0.1/cas");
        assertThat(legacy).singleElement().asString().contains("cas.tls.legacy-justification");
        assertThat(TlsPolicy.check(new String[] {"TLSv1.3", "TLSv1.2"}, "BIR EIS endpoint supports TLS 1.2 only (ticket 42)",
                "TLSv1.3,TLSv1.2", "jdbc:postgresql://127.0.0.1/cas")).isEmpty();
    }

    @Test
    void onlyTls13And12CanBeNamedAndClientsGetExactlyThePolicy() {
        assertThatThrownBy(() -> TlsPolicy.parse("TLSv1.1")).hasMessageContaining("TLSv1.1");
        assertThatThrownBy(() -> TlsPolicy.parse(" ")).hasMessageContaining("empty");
        assertThat(TlsPolicy.parse("TLSv1.3 TLSv1.2")).containsExactly("TLSv1.3", "TLSv1.2");
        TlsPolicy dev = new TlsPolicy("TLSv1.3", "", "jdbc:postgresql://db.internal/cas", new MockEnvironment());
        assertThat(dev.clientParameters().getProtocols()).containsExactly("TLSv1.3");
    }

    @Test
    void theProdProfileRefusesToStartWhenThePolicyIsNotMet() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        String previous = System.getProperty("jdk.tls.client.protocols");
        try {
            System.clearProperty("jdk.tls.client.protocols");
            assertThatThrownBy(() -> new TlsPolicy("TLSv1.3", "", "jdbc:postgresql://127.0.0.1/cas", prod))
                    .hasMessageContaining("TLS policy (TLS 1.3 minimum) not met");
            System.setProperty("jdk.tls.client.protocols", "TLSv1.3");
            assertThat(new TlsPolicy("TLSv1.3", "", "jdbc:postgresql://127.0.0.1/cas", prod).protocols()).containsExactly("TLSv1.3");
        } finally {
            if (previous == null) {
                System.clearProperty("jdk.tls.client.protocols");
            } else {
                System.setProperty("jdk.tls.client.protocols", previous);
            }
        }
    }
}
