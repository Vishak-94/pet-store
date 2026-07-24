package com.petstore.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * customer-service — the identity/accounts microservice extracted from the
 * monolith. Owns the customer bounded context (app_user, customer tables),
 * and is the JWT ISSUER for the microservices platform: POST /auth/login
 * authenticates and returns a signed JWT that other services verify.
 */
@SpringBootApplication
public class CustomerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}
