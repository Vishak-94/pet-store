package com.petstore.notification.mail;

/**
 * A composed email — recipient, subject, body. The legacy {@code mailer.ejb.Mail}
 * value object (address/subject/content), carried over.
 */
public record Email(String to, String subject, String body) {
}
