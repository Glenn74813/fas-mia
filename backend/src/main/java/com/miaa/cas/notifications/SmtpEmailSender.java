package com.miaa.cas.notifications;

import com.miaa.cas.config.CasProperties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Production e-mail delivery over SMTP with mandatory STARTTLS (settings under {@code spring.mail}, credentials from
 * the environment). Active only when {@code cas.mail.smtp-enabled=true}; which relay MIAA uses is C-16. Subjects are
 * stripped of line breaks and addresses parsed strictly, so model values cannot add headers or recipients.
 */
@Component
@ConditionalOnProperty(name = "cas.mail.smtp-enabled", havingValue = "true")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final InternetAddress from;
    private final String baseUrl;

    public SmtpEmailSender(JavaMailSender mail, @Value("${cas.mail.from:}") String from, CasProperties properties) throws Exception {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("cas.mail.from (CAS_MAIL_FROM) is required when SMTP delivery is enabled");
        }
        this.mail = mail;
        this.from = new InternetAddress(from, true);
        this.baseUrl = properties.frontendBaseUrl().replaceAll("/+$", "");
    }

    @Override
    public void send(String recipient, String template, Map<String, Object> model) throws Exception {
        EmailTemplates.Rendered r = EmailTemplates.render(template, model, baseUrl);
        MimeMessage message = mail.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(new InternetAddress(recipient, true));
        helper.setSubject(r.subject().replaceAll("[\\r\\n]+", " "));
        helper.setText(r.body(), false);
        mail.send(message);
    }
}
