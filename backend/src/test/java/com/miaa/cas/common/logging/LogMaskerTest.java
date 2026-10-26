package com.miaa.cas.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class LogMaskerTest {

    @Test
    void secretsAndPersonalDataAreMasked() {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJlLXZhbHVl";
        assertThat(LogMasker.mask("token " + jwt + " rejected")).isEqualTo("token [JWT] rejected");
        assertThat(LogMasker.mask("Authorization: Bearer abcdEFGH1234.xyz")).doesNotContain("abcdEFGH1234");
        assertThat(LogMasker.mask("{\"password\":\"S3cret!pass\",\"username\":\"jdoe\"}"))
                .isEqualTo("{\"password\":\"[REDACTED]\",\"username\":\"jdoe\"}");
        assertThat(LogMasker.mask("reset link https://cas/reset?token=Zx81kQ&x=1")).contains("token=[REDACTED]").doesNotContain("Zx81kQ");
        assertThat(LogMasker.mask("mfaToken=abc123 code ok")).contains("mfaToken=[REDACTED]");
        assertThat(LogMasker.mask("supplier TIN 123-456-789-00000 updated")).isEqualTo("supplier TIN [TIN] updated");
        assertThat(LogMasker.mask("accountNumber=0012345678901 verified")).isEqualTo("accountNumber=****8901 verified");
        assertThat(LogMasker.mask("sent to juan.delacruz@miaa.gov.ph")).isEqualTo("sent to j***@miaa.gov.ph");
    }

    @Test
    void ordinaryMessagesAreUnchanged() {
        String s = "Report job 3f2a failed: Invalid period 2026-10 (account PLC-5201, amount 1,500.75, 1792720849123 ms)";
        assertThat(LogMasker.mask(s)).isEqualTo(s);
        assertThat(LogMasker.mask(null)).isNull();
    }

    @Test
    void structuredEcsLogsAreMaskedByTheJsonCustomizer() {
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new LogbackMDCAdapter());
        ctx.putObject(Environment.class.getName(), new MockEnvironment()
                .withProperty("logging.structured.json.customizer", MaskingJsonMembersCustomizer.class.getName())
                .withProperty("spring.application.name", "cas-backend"));
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(ctx);
        encoder.setFormat("ecs");
        encoder.start();
        LoggingEvent event = new LoggingEvent("x", ctx.getLogger("t"), Level.ERROR, "login for {} with password={}", null,
                new Object[] {"juan.delacruz@miaa.gov.ph", "hunter22"});
        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(json).contains("\"message\":\"login for j***@miaa.gov.ph with password=[REDACTED]\"").doesNotContain("hunter22");
    }

    @Test
    void theConsoleConverterMasksUnlessTheEventIsMarkedDevOnly() {
        LoggerContext ctx = new LoggerContext();
        MaskingMessageConverter converter = new MaskingMessageConverter();
        LoggingEvent masked = new LoggingEvent("x", ctx.getLogger("t"), Level.WARN, "password={}", null, new Object[] {"hunter22"});
        assertThat(converter.convert(masked)).isEqualTo("password=[REDACTED]");
        LoggingEvent dev = new LoggingEvent("x", ctx.getLogger("t"), Level.WARN, "link token={}", null, new Object[] {"abc"});
        dev.addMarker(MaskingMessageConverter.UNMASKED_DEV);
        assertThat(converter.convert(dev)).isEqualTo("link token=abc");
    }
}
