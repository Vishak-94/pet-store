package com.petstore.catalog.repository;

import com.petstore.catalog.domain.Category;
import com.petstore.catalog.domain.Item;
import com.petstore.catalog.domain.Page;
import com.petstore.catalog.domain.Product;

import java.util.Locale;
import java.util.Optional;

/**
 * Persistence <b>port</b> for the catalog — the seam between business logic and
 * storage (hexagonal / ports-and-adapters).
 *
 * <p>Reproduces the exact query contract of the legacy
 * {@code com.petstore.catalog.dao.CatalogDAO} so behaviour is
 * preserved:
 * <ul>
 *   <li>lookups that find nothing return {@link Optional#empty()} /
 *       {@link Page#EMPTY_PAGE} — never null, never an error;</li>
 *   <li>results are locale-specific (the {@code *_details} tables).</li>
 * </ul>
 *
 * <p>Dependency Inversion: {@code CatalogService} depends only on this
 * interface; the JPA adapter is injected by Spring. Interface Segregation: this
 * port covers <b>browse</b> reads only (category → product → item lookups and
 * listings); free-text keyword search lives on the separate {@link CatalogSearchPort}
 * so search can evolve (a dedicated engine, or its own service) without touching the
 * browse contract.
 */
public interface CatalogRepository {

    Optional<Category> getCategory(String categoryId, Locale locale);

    Page getCategories(int start, int count, Locale locale);

    Optional<Product> getProduct(String productId, Locale locale);

    Page getProducts(String categoryId, int start, int count, Locale locale);

    Optional<Item> getItem(String itemId, Locale locale);

    Page getItems(String productId, int start, int size, Locale locale);
}
