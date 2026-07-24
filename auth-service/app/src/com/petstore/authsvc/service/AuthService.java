package com.petstore.authsvc.service;

import com.petstore.authsvc.domain.AccountEntity;
import com.petstore.authsvc.domain.AccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Credential authentication against the single account store. BCrypt-hashed
 * passwords (delegating encoder, {bcrypt} prefix). This is the only service that
 * checks a password.
 */
@Service
public class AuthService {

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;

    public AuthService(AccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    public Optional<AccountEntity> authenticate(String userName, String rawPassword) {
        return accounts.findById(userName)
                .filter(a -> encoder.matches(rawPassword, a.getPassword()));
    }
}
