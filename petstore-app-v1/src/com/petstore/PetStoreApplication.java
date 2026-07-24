package com.petstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.jms.annotation.EnableJms;

/**
 * Entry point for the migrated Java Pet Store.
 *
 * <p>The legacy system was four WAR/EAR modules deployed to the Sun J2EE 1.3
 * reference server. This is a single self-contained Spring Boot application on
 * Java 21 — embedded Tomcat, embedded H2, and an embedded ActiveMQ Artemis
 * broker (JMS is preserved) — runnable with {@code mvn spring-boot:run}.
 *
 * <p>Migration proceeds bounded-context by bounded-context (Catalog first).
 */
@SpringBootApplication
@EnableJms
@ConfigurationPropertiesScan
public class PetStoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(PetStoreApplication.class, args);
    }
}
