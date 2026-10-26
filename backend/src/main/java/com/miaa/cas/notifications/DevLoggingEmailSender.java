package com.miaa.cas.notifications;

import com.miaa.cas.common.logging.MaskingMessageConverter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY. Writes would-be e-mails to the application log instead of sending them, so a
 * developer can follow password-reset links locally. Never active outside the {@code dev} profile.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "cas.mail.smtp-enabled", havingValue = "false", matchIfMissing = true)
public class DevLoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(DevLoggingEmailSender.class);

    @Override
    public void send(String recipient, String template, Map<String, Object> model) {
        // Unmasked on purpose: a developer must be able to follow the reset link. This class exists only in the dev profile.
        log.warn(MaskingMessageConverter.UNMASKED_DEV, "[DEV EMAIL - NOT SENT] to={} template={} model={}", recipient, template, model);
    }
}
