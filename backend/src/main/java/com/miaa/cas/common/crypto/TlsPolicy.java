package com.miaa.cas.common.crypto;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * TLS 1.3 minimum for every connection CAS makes (DICT Department Circular No. 007, s. 2020; MS §9.4). One setting,
 * {@code cas.tls.protocols} (default TLSv1.3), drives the outbound HTTP clients and SMTP. With the prod profile CAS
 * refuses to start unless:
 * <ul>
 * <li>the setting is TLSv1.3, or TLSv1.2 is added with a written justification in {@code cas.tls.legacy-justification}
 * (for a listed legacy integration only);</li>
 * <li>the JVM's own TLS clients follow it ({@code -Djdk.tls.client.protocols}), which also covers the database driver;</li>
 * <li>a database on another host is reached over verified TLS ({@code sslmode=verify-full} or {@code verify-ca}).</li>
 * </ul>
 * Inbound TLS ends at the gateway (deploy/nginx/gateway.conf: {@code ssl_protocols TLSv1.3}) or, for the Cloudflare
 * deployment, in the Worker, which refuses connections below TLS 1.3.
 */
@Component
public class TlsPolicy {

    private static final Logger log = LoggerFactory.getLogger(TlsPolicy.class);
    static final String TLS13 = "TLSv1.3";
    private static final List<String> LOOPBACK = List.of("localhost", "127.0.0.1", "::1", "[::1]");

    private final String[] protocols;

    public TlsPolicy(@Value("${cas.tls.protocols:TLSv1.3}") String protocols,
            @Value("${cas.tls.legacy-justification:}") String justification,
            @Value("${spring.datasource.url:}") String datasourceUrl, Environment env) {
        this.protocols = parse(protocols);
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        List<String> problems = check(this.protocols, justification, System.getProperty("jdk.tls.client.protocols"), datasourceUrl);
        if (prod && !problems.isEmpty()) {
            throw new IllegalStateException("TLS policy (TLS 1.3 minimum) not met: " + String.join(" ", problems));
        }
        if (!problems.isEmpty()) {
            log.debug("TLS policy not enforced outside prod: {}", problems);
        }
        if (Arrays.asList(this.protocols).contains("TLSv1.2")) {
            log.warn("TLS 1.2 is allowed for outbound connections: {}", justification);
        }
    }

    /** The protocols outbound clients may use, in preference order. */
    public String[] protocols() {
        return protocols.clone();
    }

    /** SSL parameters for java.net.http clients: the allowed protocols only. */
    public SSLParameters clientParameters() {
        SSLParameters p = new SSLParameters();
        p.setProtocols(protocols());
        return p;
    }

    static String[] parse(String value) {
        String[] out = Arrays.stream((value == null ? "" : value).split("[,\\s]+")).filter(s -> !s.isBlank()).toArray(String[]::new);
        for (String p : out) {
            if (!p.equals(TLS13) && !p.equals("TLSv1.2")) {
                throw new IllegalStateException("cas.tls.protocols may contain only TLSv1.3 and TLSv1.2, not " + p);
            }
        }
        if (out.length == 0) {
            throw new IllegalStateException("cas.tls.protocols is empty");
        }
        return out;
    }

    /** The reasons the policy is not met; empty when it is. */
    static List<String> check(String[] protocols, String justification, String jvmClientProtocols, String datasourceUrl) {
        List<String> problems = new ArrayList<>();
        boolean legacy = Arrays.asList(protocols).contains("TLSv1.2");
        if (legacy && (justification == null || justification.isBlank())) {
            problems.add("TLSv1.2 needs a written reason in cas.tls.legacy-justification (CAS_TLS_LEGACY_JUSTIFICATION).");
        }
        String expected = String.join(",", protocols);
        String jvm = jvmClientProtocols == null ? "" : jvmClientProtocols.replace(" ", "");
        if (!jvm.equals(expected)) {
            problems.add("Start the JVM with -Djdk.tls.client.protocols=" + expected + " (it is '" + jvm + "').");
        }
        String host = databaseHost(datasourceUrl);
        if (host != null && !LOOPBACK.contains(host)) {
            String url = datasourceUrl.toLowerCase(Locale.ROOT);
            if (!url.contains("sslmode=verify-full") && !url.contains("sslmode=verify-ca")) {
                problems.add("The database at " + host + " must be reached with sslmode=verify-full (or verify-ca) in CAS_DB_URL.");
            }
        }
        return problems;
    }

    /** The host of a jdbc:postgresql URL, or null when it cannot be read. */
    static String databaseHost(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return null;
        }
        try {
            String host = URI.create(jdbcUrl.substring(5)).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
