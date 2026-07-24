package com.petstore.catalog.domain;

/**
 * A catalog product (a family of item variants) — framework-free value object.
 * Mirrors the legacy {@code catalog.model.Product}.
 */
public final class Product {

    private final String id;
    private final String name;
    private final String description;

    public Product(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
