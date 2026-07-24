package com.petstore.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * catalog-service — the catalog/browse microservice extracted from the monolith.
 * Owns the catalog bounded context (category/product/item tables, locale-split)
 * and exposes a read-only JSON API consumed via catalog-service-client.
 */
@SpringBootApplication
public class CatalogServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
