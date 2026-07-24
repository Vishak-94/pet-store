package com.petstore.notification.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Dev {@link MailSender} that "sends" by logging the fully-composed email — no
 * SMTP, no infra. This is the DEFAULT. To send real email, drop in a
 * JavaMailSender-backed {@link MailSender} bean (e.g. {@code SmtpMailSender} guarded
 * by config) and this one backs off via {@code @ConditionalOnMissingBean}.
 */
@Component
@ConditionalOnMissingBean(name = "smtpMailSender")
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(Email email) {
        log.info("""

                ┌─ EMAIL ─────────────────────────────────────────────
                │ To:      {}
                │ Subject: {}
                │ {}
                └─────────────────────────────────────────────────────""",
                email.to(), email.subject(), email.body().replace("\n", "\n│ "));
    }
}
