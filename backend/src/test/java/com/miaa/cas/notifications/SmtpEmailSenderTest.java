package com.miaa.cas.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaa.cas.security.TestProperties;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpEmailSenderTest {

    /** Captures messages instead of connecting to a relay. */
    static class CapturingSender extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new ArrayList<>();

        @Override
        protected void doSend(MimeMessage[] messages, Object[] originals) {
            sent.addAll(List.of(messages));
        }
    }

    private final CapturingSender relay = new CapturingSender();

    private SmtpEmailSender sender() throws Exception {
        return new SmtpEmailSender(relay, "MIAA CAS <no-reply@cas.miaa.gov.ph>", TestProperties.defaults());
    }

    @Test
    void sendsPlainTextFromTheConfiguredAddressWithAbsoluteLinks() throws Exception {
        sender().send("chief@miaa.gov.ph", AlertService.EMAIL_TEMPLATE,
                Map.of("title", "Integration BIR_EIS needs attention", "body", "A transmission failed.", "link", "/integrations"));
        MimeMessage m = relay.sent.get(0);
        assertThat(m.getFrom()[0].toString()).isEqualTo("MIAA CAS <no-reply@cas.miaa.gov.ph>");
        assertThat(m.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("chief@miaa.gov.ph");
        assertThat(m.getSubject()).isEqualTo("[MIAA CAS alert] Integration BIR_EIS needs attention");
        assertThat(m.getContentType()).startsWith("text/plain");
        assertThat((String) m.getContent()).contains("http://localhost:5173/integrations").contains("Do not reply");
    }

    @Test
    void everyOutboxTemplateRendersFromItsModel() {
        String base = "https://cas.miaa.gov.ph";
        assertThat(EmailTemplates.render("PASSWORD_RESET", Map.of("displayName", "Juan", "resetUrl", base + "/reset-password#token=x",
                "expiresInMinutes", 30), base).body()).contains("within 30 minutes").contains("#token=x");
        assertThat(EmailTemplates.render("portal-access-granted", Map.of("customerName", "Duty Free", "username", "df.portal"), base)
                .body()).contains(base + "/portal");
        assertThat(EmailTemplates.render("portal-inquiry-answered", Map.of("reference", "INQ-2026-000001", "subject", "Payment"), base)
                .subject()).contains("INQ-2026-000001");
        assertThat(EmailTemplates.render("ticket-sla-escalation", Map.of("ticketNumber", "TKT-1", "title", "Down", "priority", "P1",
                "kind", "RESPONSE", "detail", "No response in 1h"), base).subject()).isEqualTo("[P1] Ticket TKT-1 escalated: RESPONSE");
        assertThatThrownBy(() -> EmailTemplates.render("unknown", Map.of(), base)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelValuesCannotAddHeadersOrRecipients() throws Exception {
        sender().send("chief@miaa.gov.ph", AlertService.EMAIL_TEMPLATE,
                Map.of("title", "x\r\nBcc: attacker@example.com", "body", "b", "link", "/"));
        MimeMessage m = relay.sent.get(0);
        assertThat(m.getSubject()).doesNotContain("\n").doesNotContain("\r");
        assertThat(m.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThatThrownBy(() -> sender().send("a@b.com, evil@example.com", "PASSWORD_RESET", Map.of())).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new SmtpEmailSender(relay, " ", TestProperties.defaults())).isInstanceOf(IllegalStateException.class);
    }
}
