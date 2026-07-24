package com.petstore.catalog.domain;

/**
 * Catalog product — framework-free domain value object.
 *
 * <p>Carried over from the legacy {@code catalog.model.Product} (id, name,
 * description). Plain immutable class (see {@link Category} for why not a record).
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
