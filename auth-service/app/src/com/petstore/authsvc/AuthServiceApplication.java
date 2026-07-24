package com.petstore.authsvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * auth-service — the central identity provider (IdP). The single token issuer for
 * ALL users (customers + staff), and the only holder of the RS256 private key and
 * the credential store. Every other service verifies tokens with the public key
 * (via auth-client) and holds no credentials.
 */
@SpringBootApplication
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
