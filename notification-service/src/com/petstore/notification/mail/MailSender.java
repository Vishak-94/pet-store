package com.petstore.notification.mail;

/**
 * Port for sending email — the seam the legacy {@code MailHelper}/{@code MailerMDB}
 * filled with JavaMail. Keeping it an interface lets us swap a logging dev-sender
 * (no infra) for a real SMTP sender (Spring JavaMailSender) by configuration only,
 * with no change to the notification logic.
 */
public interface MailSender {

    /**
     * Deliver the composed email. The transport is adapter-defined: the default
     * {@link LoggingMailSender} logs it; an SMTP adapter would actually send it.
     *
     * @param email the fully-composed email (to, subject, body)
     */
    void send(Email email);
}
