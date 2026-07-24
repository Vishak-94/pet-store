package com.petstore.catalog.client;

/**
 * The catalog-service HTTP contract — path constants shared by the server
 * (which maps them) and every client (which calls them). Single-sourcing the
 * paths here means the server provably cannot drift from what clients expect.
 *
 * <p>Only paths live here; the base URL is environment-specific and supplied to
 * {@link CatalogServiceClient} at construction time (never hardcoded).
 */
public final class CatalogServiceEndpoints {

    private CatalogServiceEndpoints() {
    }

    /** Default base URL for local development (catalog-service runs on :8083). */
    public static final String DEFAULT_BASE_URL = "http://localhost:8083";

    /** GET — categories page: ?start=&count=&lang= */
    public static final String CATEGORIES = "/api/categories";

    /** GET — one category: /api/categories/{id}?lang= */
    public static final String CATEGORY_BY_ID = "/api/categories/{id}";

    /** GET — products in a category: /api/categories/{id}/products?start=&count=&lang= */
    public static final String PRODUCTS_IN_CATEGORY = "/api/categories/{id}/products";

    /** GET — one product: /api/products/{id}?lang= */
    public static final String PRODUCT_BY_ID = "/api/products/{id}";

    /** GET — items in a product: /api/products/{id}/items?start=&count=&lang= */
    public static final String ITEMS_IN_PRODUCT = "/api/products/{id}/items";

    /** GET — one item: /api/items/{id}?lang= */
    public static final String ITEM_BY_ID = "/api/items/{id}";

    /** GET — keyword search over items: ?keyword=&start=&count=&lang= */
    public static final String ITEMS_SEARCH = "/api/items";
}
