package com.petstore.catalog.client;

import com.petstore.catalog.client.CatalogDtos.CategoryDto;
import com.petstore.catalog.client.CatalogDtos.CategoryPage;
import com.petstore.catalog.client.CatalogDtos.ItemDto;
import com.petstore.catalog.client.CatalogDtos.ItemPage;
import com.petstore.catalog.client.CatalogDtos.ProductDto;
import com.petstore.catalog.client.CatalogDtos.ProductPage;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Importable client SDK for the catalog-service microservice.
 *
 * <p>Owns the API contract: endpoint paths ({@link CatalogServiceEndpoints}),
 * operations, and DTOs ({@link CatalogDtos}). Consumers (e.g. the monolith
 * storefront) just {@code new CatalogServiceClient(baseUrl)} and call methods;
 * no URLs or JSON shapes leak into caller code.
 *
 * <p>Behaviour mirrors the legacy catalog contract exactly:
 * <ul>
 *   <li>a lookup that finds nothing returns {@link Optional#empty()} (never an error);</li>
 *   <li>a page that finds nothing returns an empty-list page;</li>
 *   <li>results are locale-specific (legacy locale keys like {@code en_US}).</li>
 * </ul>
 *
 * <p>Base URL is a constructor arg (environment-specific); endpoint paths are
 * hardcoded constants (the contract).
 */
public class CatalogServiceClient {

    /** Bounded timeouts so a hung/slow catalog-service can't block caller threads indefinitely. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient http;

    /** Use the default base URL ({@code http://localhost:8083}). */
    public CatalogServiceClient() {
        this(CatalogServiceEndpoints.DEFAULT_BASE_URL);
    }

    /** Use a specific base URL (host/port per environment). */
    public CatalogServiceClient(String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build();
    }

    /** Advanced: supply a preconfigured RestClient (e.g. with interceptors/TLS/timeouts). */
    public CatalogServiceClient(RestClient restClient) {
        this.http = restClient;
    }

    /**
     * A request factory with bounded connect/read timeouts. Catalog is on the critical
     * browse/cart path (every page resolves item prices), so without these one unresponsive
     * catalog-service would hang the whole storefront. Callers needing different values can
     * pass their own preconfigured {@link RestClient} via the other constructor.
     */
    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        f.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return f;
    }

    // ---- categories ----

    public Optional<CategoryDto> getCategory(String categoryId, String locale) {
        try {
            return Optional.ofNullable(http.get()
                    .uri(uri -> uri.path(CatalogServiceEndpoints.CATEGORY_BY_ID)
                            .queryParam(CatalogServiceEndpoints.PARAM_LANG, locale).build(categoryId))
                    .retrieve().body(CategoryDto.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public CategoryPage getCategories(int start, int count, String locale) {
        CategoryPage page = http.get()
                .uri(uri -> uri.path(CatalogServiceEndpoints.CATEGORIES)
                        .queryParam(CatalogServiceEndpoints.PARAM_START, start)
                        .queryParam(CatalogServiceEndpoints.PARAM_COUNT, count)
                        .queryParam(CatalogServiceEndpoints.PARAM_LANG, locale).build())
                .retrieve().body(CategoryPage.class);
        return page == null ? emptyCategoryPage(start) : page;
    }

    // ---- products ----

    public Optional<ProductDto> getProduct(String productId, String locale) {
        try {
            return Optional.ofNullable(http.get()
                    .uri(uri -> uri.path(CatalogServiceEndpoints.PRODUCT_BY_ID)
                            .queryParam(CatalogServiceEndpoints.PARAM_LANG, locale).build(productId))
                    .retrieve().body(ProductDto.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public ProductPage getProducts(String categoryId, int start, int count, String locale) {
        ProductPage page = http.get()
                .uri(uri -> uri.path(CatalogServiceEndpoints.PRODUCTS_IN_CATEGORY)
                        .queryParam(CatalogServiceEndpoints.PARAM_START, start)
                        .queryParam(CatalogServiceEndpoints.PARAM_COUNT, count)
                        .queryParam(CatalogServiceEndpoints.PARAM_LANG, locale).build(categoryId))
                .retrieve().body(ProductPage.class);
        return page == null ? emptyProductPage(start) : page;
    }

    // ---- items ----

    public Optional<ItemDto> getItem(String itemId, String locale) {
        try {
            return Optional.ofNullable(http.get()
                    .uri(uri -> uri.path(CatalogServiceEndpoints.ITEM_BY_ID)
                            .queryParam(CatalogServiceEndpoints.PARAM_LANG, locale).build(itemId))
                    .retrieve().body(ItemDto.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public ItemPage getItems(String productId, int start, int size, String locale) {
        ItemPage page = http.get()
                .uri(uri -> uri.path(CatalogServiceEndpoints.ITEMS_IN_PRODUCT)
                        .queryParam(CatalogServiceEndpoints.PARAM_START, start)
                        .queryParam(CatalogServiceEndpoints.PARAM_COUNT, size)
                        .queryParam(CatalogServiceEndpoints.PARAM_LANG, locale).build(productId))
                .retrieve().body(ItemPage.class);
        return page == null ? emptyItemPage(start) : page;
    }

    public ItemPage searchItems(String keyword, int start, int size, String locale) {
        ItemPage page = http.get()
                .uri(uri -> uri.path(CatalogServiceEndpoints.ITEMS_SEARCH)
                        .queryParam(CatalogServiceEndpoints.PARAM_KEYWORD, keyword == null ? "" : keyword)
                        .queryParam(CatalogServiceEndpoints.PARAM_START, start)
                        .queryParam(CatalogServiceEndpoints.PARAM_COUNT, size)
                        .queryParam(CatalogServiceEndpoints.PARAM_LANG, locale).build())
                .retrieve().body(ItemPage.class);
        return page == null ? emptyItemPage(start) : page;
    }

    private static CategoryPage emptyCategoryPage(int start) {
        return new CategoryPage(List.of(), start, false);
    }

    private static ProductPage emptyProductPage(int start) {
        return new ProductPage(List.of(), start, false);
    }

    private static ItemPage emptyItemPage(int start) {
        return new ItemPage(List.of(), start, false);
    }
}
