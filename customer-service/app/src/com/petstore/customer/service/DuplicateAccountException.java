package com.petstore.customer.service;

/**
 * Thrown when registering a user name that is already taken. Mirrors the legacy
 * {@code DuplicateAccountException} that routed to the duplicate_account screen.
 */
public class DuplicateAccountException extends RuntimeException {

    public DuplicateAccountException(String userName) {
        super("Account already exists: " + userName);
    }
}
