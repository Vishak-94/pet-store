package com.petstore.catalog.domain;

/**
 * A catalog category — framework-free value object.
 *
 * <p>A plain immutable class (not a record) to keep the domain free of any
 * framework coupling and mirror the legacy {@code catalog.model.Category}.
 */
public final class Category {

    private final String id;
    private final String name;
    private final String description;

    public Category(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
