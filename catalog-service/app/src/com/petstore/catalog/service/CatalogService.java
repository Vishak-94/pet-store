package com.petstore.catalog.service;

import com.petstore.catalog.domain.Category;
import com.petstore.catalog.domain.Item;
import com.petstore.catalog.domain.Page;
import com.petstore.catalog.domain.Product;
import com.petstore.catalog.repository.CatalogRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * Catalog business logic — replaces the legacy {@code @Stateless CatalogEJB}.
 *
 * <p>Depends only on the {@link CatalogRepository} port (Dependency Inversion);
 * it neither knows nor cares that the adapter is JPA/H2. Behaviour is a
 * pass-through preserving the legacy contract (locale-specific reads, empty
 * results instead of errors), so no business logic changes here.
 */
@Service
public class CatalogService {

    private final CatalogRepository repository;

    public CatalogService(CatalogRepository repository) {
        this.repository = repository;
    }

    /**
     * Look up one category in the given locale.
     *
     * @param categoryId category id (e.g. {@code "FISH"})
     * @param locale     locale whose {@code _details} row to read
     * @return the localized category, or {@link Optional#empty()} if the id/locale row is absent
     *         (a miss is never an error). Read-only.
     */
    public Optional<Category> getCategory(String categoryId, Locale locale) {
        return repository.getCategory(categoryId, locale);
    }

    /**
     * One page of top-level categories, ordered by localized name.
     *
     * @param start  zero-based offset of the first row
     * @param count  page size
     * @param locale locale whose {@code _details} rows to read
     * @return a {@link Page} of {@link Category} (never null; empty list when nothing matches).
     *         Read-only.
     */
    public Page getCategories(int start, int count, Locale locale) {
        return repository.getCategories(start, count, locale);
    }

    /**
     * Look up one product in the given locale.
     *
     * @param productId product id (e.g. {@code "FI-SW-01"})
     * @param locale    locale whose {@code _details} row to read
     * @return the localized product, or {@link Optional#empty()} if absent. Read-only.
     */
    public Optional<Product> getProduct(String productId, Locale locale) {
        return repository.getProduct(productId, locale);
    }

    /**
     * One page of products within a category, ordered by localized name.
     *
     * @param categoryId owning category id
     * @param start      zero-based offset of the first row
     * @param count      page size
     * @param locale     locale whose {@code _details} rows to read
     * @return a {@link Page} of {@link Product} (never null; empty for an unknown category).
     *         Read-only.
     */
    public Page getProducts(String categoryId, int start, int count, Locale locale) {
        return repository.getProducts(categoryId, start, count, locale);
    }

    /**
     * Look up one item in the given locale, with its {@code category} resolved from the
     * item's product catid.
     *
     * @param itemId item id (e.g. {@code "EST-1"})
     * @param locale locale whose {@code _details} row to read
     * @return the localized item, or {@link Optional#empty()} if absent. Read-only.
     */
    public Optional<Item> getItem(String itemId, Locale locale) {
        return repository.getItem(itemId, locale);
    }

    /**
     * One page of items (purchasable variants) within a product, ordered by itemid.
     *
     * @param productId owning product id
     * @param start     zero-based offset of the first row
     * @param size      page size (sent on the wire as {@code count})
     * @param locale    locale whose {@code _details} rows to read
     * @return a {@link Page} of {@link Item} (never null; empty for an unknown product).
     *         Read-only.
     */
    public Page getItems(String productId, int start, int size, Locale locale) {
        return repository.getItems(productId, start, size, locale);
    }

    /**
     * Legacy-faithful keyword search over items: the query is whitespace-tokenized and each
     * token is OR-matched (case-insensitive {@code LIKE %token%}) across product name, category
     * catid, and item description (attributes are not searched).
     *
     * @param query  free-text query; blank/whitespace-only → an empty page
     * @param start  zero-based offset of the first row
     * @param size   page size (sent on the wire as {@code count})
     * @param locale locale whose {@code _details} rows to read
     * @return a {@link Page} of matching {@link Item} (never null). Read-only.
     */
    public Page searchItems(String query, int start, int size, Locale locale) {
        return repository.searchItems(query, start, size, locale);
    }
}
