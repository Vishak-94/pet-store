package com.petstore.catalog.domain;

/**
 * Catalog category — framework-free domain value object.
 *
 * <p>Carried over verbatim from the legacy
 * {@code com.petstore.catalog.model.Category} (id, name, description).
 * A plain immutable class (not a record) so view templates can read it via
 * standard JavaBean getters — Spring's expression security blocks reflective
 * access to record components. Still free of Spring/JPA/Jackson annotations.
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
