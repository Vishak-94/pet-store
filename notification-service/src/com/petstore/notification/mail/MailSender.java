package com.petstore.notification.mail;

/**
 * Port for sending email — the seam the legacy {@code MailHelper}/{@code MailerMDB}
 * filled with JavaMail. Keeping it an interface lets us swap a logging dev-sender
 * (no infra) for a real SMTP sender (Spring JavaMailSender) by configuration only,
 * with no change to the notification logic.
 */
public interface MailSender {

    void send(Email email);
}
