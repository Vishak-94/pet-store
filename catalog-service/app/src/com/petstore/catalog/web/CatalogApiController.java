package com.petstore.catalog.web;

import com.petstore.catalog.client.CatalogDtos.CategoryDto;
import com.petstore.catalog.client.CatalogDtos.CategoryPage;
import com.petstore.catalog.client.CatalogDtos.ItemDto;
import com.petstore.catalog.client.CatalogDtos.ItemPage;
import com.petstore.catalog.client.CatalogDtos.ProductDto;
import com.petstore.catalog.client.CatalogDtos.ProductPage;
import com.petstore.catalog.client.CatalogServiceEndpoints;
import com.petstore.catalog.domain.Category;
import com.petstore.catalog.domain.Item;
import com.petstore.catalog.domain.Page;
import com.petstore.catalog.domain.Product;
import com.petstore.catalog.service.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * JSON API for the catalog — the network boundary that replaces the in-process
 * {@code CatalogService} calls the monolith used to make. Paths are the shared
 * {@link CatalogServiceEndpoints} constants (single-sourced contract).
 *
 * <p>Behaviour preserved from the monolith:
 * <ul>
 *   <li>single-entity lookups return 404 when absent (client maps → Optional.empty);</li>
 *   <li>page lookups always return 200 with a (possibly empty) list;</li>
 *   <li>results are locale-specific ({@code ?lang=en_US}); default en_US.</li>
 * </ul>
 */
@RestController
public class CatalogApiController {

    private static final int DEFAULT_COUNT = 10;
    /** {@code @RequestParam} defaults must be compile-time String constants. */
    private static final String DEFAULT_START = "0";
    private static final String DEFAULT_COUNT_STR = "" + DEFAULT_COUNT;
    /** Legacy sentinel: {@code ?lang=default} means "use the JVM default locale". */
    private static final String LOCALE_DEFAULT_SENTINEL = "default";
    /** Separator in a {@code language_country_variant} locale key. */
    private static final String LOCALE_PART_SEPARATOR = "_";

    private final CatalogService catalog;

    public CatalogApiController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * Parse a legacy {@code language_country_variant} locale key (e.g. {@code en_US}) into a
     * {@link Locale}. Mirrors the legacy {@code getLocaleFromString}: blank/null → {@link Locale#US},
     * the sentinel {@code "default"} → the JVM default, otherwise a 1/2/3-part split on {@code _}.
     */
    private static Locale locale(String lang) {
        if (lang == null || lang.isBlank()) {
            return Locale.US;
        }
        // Legacy getLocaleFromString: "default" → the JVM default locale.
        if (lang.equalsIgnoreCase(LOCALE_DEFAULT_SENTINEL)) {
            return Locale.getDefault();
        }
        // Legacy handled language, language_country and language_country_variant.
        String[] parts = lang.split(LOCALE_PART_SEPARATOR);
        return switch (parts.length) {
            case 3 -> new Locale(parts[0], parts[1], parts[2]);
            case 2 -> new Locale(parts[0], parts[1]);
            default -> new Locale(lang);
        };
    }

    // ---- categories ----

    /**
     * Top-level category listing, one page ({@code start}/{@code count}), localized.
     * Always 200 with a (possibly empty) page — a miss is an empty list, never a 404.
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/categories?start=0&count=10&lang=en_US
     * }</pre>
     *
     * <p>Example response (200):
     * <pre>{@code
     * {
     *   "list": [
     *     {"id": "FISH", "name": "Fish", "description": "Fish"},
     *     {"id": "DOGS", "name": "Dogs", "description": "Dogs"}
     *   ],
     *   "start": 0,
     *   "nextPageAvailable": true
     * }
     * }</pre>
     */
    @GetMapping(CatalogServiceEndpoints.CATEGORIES)
    public CategoryPage categories(@RequestParam(value = CatalogServiceEndpoints.PARAM_START, defaultValue = DEFAULT_START) int start,
                                   @RequestParam(value = CatalogServiceEndpoints.PARAM_COUNT, defaultValue = DEFAULT_COUNT_STR) int count,
                                   @RequestParam(value = CatalogServiceEndpoints.PARAM_LANG, required = false) String lang) {
        Page page = catalog.getCategories(start, count, locale(lang));
        return new CategoryPage(mapCategories(page.getList()), start, page.isNextPageAvailable());
    }

    /**
     * Single category by id (localized); 404 when the category/locale row is absent
     * (the client maps that 404 back to {@code Optional.empty()}).
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/categories/FISH?lang=en_US
     * }</pre>
     *
     * <p>Example response (200):
     * <pre>{@code
     * {"id": "FISH", "name": "Fish", "description": "Fish"}
     * }</pre>
     *
     * <p>Not found → {@code 404 Not Found} with an empty body.
     */
    @GetMapping(CatalogServiceEndpoints.CATEGORY_BY_ID)
    public ResponseEntity<CategoryDto> category(@PathVariable String id,
                                                @RequestParam(value = CatalogServiceEndpoints.PARAM_LANG, required = false) String lang) {
        return catalog.getCategory(id, locale(lang))
                .map(c -> ResponseEntity.ok(toDto(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * One page of products within a category (localized, ordered by name).
     * Always 200 with a (possibly empty) page — an unknown category yields an empty list.
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/categories/FISH/products?start=0&count=10&lang=en_US
     * }</pre>
     *
     * <p>Example response (200):
     * <pre>{@code
     * {
     *   "list": [
     *     {"id": "FI-SW-01", "name": "Angelfish", "description": "Salt Water fish from Australia"}
     *   ],
     *   "start": 0,
     *   "nextPageAvailable": false
     * }
     * }</pre>
     */
    @GetMapping(CatalogServiceEndpoints.PRODUCTS_IN_CATEGORY)
    public ProductPage productsInCategory(@PathVariable String id,
                                          @RequestParam(value = CatalogServiceEndpoints.PARAM_START, defaultValue = DEFAULT_START) int start,
                                          @RequestParam(value = CatalogServiceEndpoints.PARAM_COUNT, defaultValue = DEFAULT_COUNT_STR) int count,
                                          @RequestParam(value = CatalogServiceEndpoints.PARAM_LANG, required = false) String lang) {
        Page page = catalog.getProducts(id, start, count, locale(lang));
        return new ProductPage(mapProducts(page.getList()), start, page.isNextPageAvailable());
    }

    // ---- products ----

    /**
     * Single product by id (localized); 404 when the product/locale row is absent
     * (the client maps that 404 back to {@code Optional.empty()}).
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/products/FI-SW-01?lang=en_US
     * }</pre>
     *
     * <p>Example response (200):
     * <pre>{@code
     * {"id": "FI-SW-01", "name": "Angelfish", "description": "Salt Water fish from Australia"}
     * }</pre>
     *
     * <p>Not found → {@code 404 Not Found} with an empty body.
     */
    @GetMapping(CatalogServiceEndpoints.PRODUCT_BY_ID)
    public ResponseEntity<ProductDto> product(@PathVariable String id,
                                              @RequestParam(value = CatalogServiceEndpoints.PARAM_LANG, required = false) String lang) {
        return catalog.getProduct(id, locale(lang))
                .map(p -> ResponseEntity.ok(toDto(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * One page of items (purchasable variants) within a product (localized, ordered by itemid).
     * Always 200 with a (possibly empty) page.
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/products/FI-SW-01/items?start=0&count=10&lang=en_US
     * }</pre>
     *
     * <p>Example response (200):
     * <pre>{@code
     * {
     *   "list": [
     *     {
     *       "category": "FISH", "productId": "FI-SW-01", "productName": "Angelfish",
     *       "attribute1": "Large", "attribute2": null, "attribute3": null,
     *       "attribute4": null, "attribute5": null,
     *       "itemId": "EST-1", "description": "Large Angelfish",
     *       "listPrice": 16.50, "unitCost": 10.00, "imageLocation": "fish1.gif"
     *     }
     *   ],
     *   "start": 0,
     *   "nextPageAvailable": false
     * }
     * }</pre>
     */
    @GetMapping(CatalogServiceEndpoints.ITEMS_IN_PRODUCT)
    public ItemPage itemsInProduct(@PathVariable String id,
                                   @RequestParam(value = CatalogServiceEndpoints.PARAM_START, defaultValue = DEFAULT_START) int start,
                                   @RequestParam(value = CatalogServiceEndpoints.PARAM_COUNT, defaultValue = DEFAULT_COUNT_STR) int count,
                                   @RequestParam(value = CatalogServiceEndpoints.PARAM_LANG, required = false) String lang) {
        Page page = catalog.getItems(id, start, count, locale(lang));
        return new ItemPage(mapItems(page.getList()), start, page.isNextPageAvailable());
    }

    // ---- items ----

    /**
     * Single item by id (localized, with {@code category} resolved from the item's
     * product catid); 404 when absent (client maps 404 → {@code Optional.empty()}).
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/items/EST-1?lang=en_US
     * }</pre>
     *
     * <p>Example response (200):
     * <pre>{@code
     * {
     *   "category": "FISH", "productId": "FI-SW-01", "productName": "Angelfish",
     *   "attribute1": "Large", "attribute2": null, "attribute3": null,
     *   "attribute4": null, "attribute5": null,
     *   "itemId": "EST-1", "description": "Large Angelfish",
     *   "listPrice": 16.50, "unitCost": 10.00, "imageLocation": "fish1.gif"
     * }
     * }</pre>
     *
     * <p>Not found → {@code 404 Not Found} with an empty body.
     */
    @GetMapping(CatalogServiceEndpoints.ITEM_BY_ID)
    public ResponseEntity<ItemDto> item(@PathVariable String id,
                                        @RequestParam(value = CatalogServiceEndpoints.PARAM_LANG, required = false) String lang) {
        return catalog.getItem(id, locale(lang))
                .map(i -> ResponseEntity.ok(toDto(i)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Keyword item search (one page); blank keyword → empty page. Legacy-faithful: the query is
     * whitespace-tokenized and each token is OR-matched (case-insensitive {@code LIKE %token%})
     * across product name, category catid, and item description (attributes are NOT searched).
     * Always 200 with a (possibly empty) page.
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/items?keyword=angelfish&start=0&count=10&lang=en_US
     * }</pre>
     *
     * <p>Example response (200):
     * <pre>{@code
     * {
     *   "list": [
     *     {
     *       "category": "FISH", "productId": "FI-SW-01", "productName": "Angelfish",
     *       "attribute1": "Large", "attribute2": null, "attribute3": null,
     *       "attribute4": null, "attribute5": null,
     *       "itemId": "EST-1", "description": "Large Angelfish",
     *       "listPrice": 16.50, "unitCost": 10.00, "imageLocation": "fish1.gif"
     *     }
     *   ],
     *   "start": 0,
     *   "nextPageAvailable": false
     * }
     * }</pre>
     */
    @GetMapping(CatalogServiceEndpoints.ITEMS_SEARCH)
    public ItemPage search(@RequestParam(value = CatalogServiceEndpoints.PARAM_KEYWORD, defaultValue = "") String keyword,
                           @RequestParam(value = CatalogServiceEndpoints.PARAM_START, defaultValue = DEFAULT_START) int start,
                           @RequestParam(value = CatalogServiceEndpoints.PARAM_COUNT, defaultValue = DEFAULT_COUNT_STR) int count,
                           @RequestParam(value = CatalogServiceEndpoints.PARAM_LANG, required = false) String lang) {
        Page page = catalog.searchItems(keyword, start, count, locale(lang));
        return new ItemPage(mapItems(page.getList()), start, page.isNextPageAvailable());
    }

    // ---- domain → DTO mapping ----

    private static CategoryDto toDto(Category c) {
        return new CategoryDto(c.getId(), c.getName(), c.getDescription());
    }

    private static ProductDto toDto(Product p) {
        return new ProductDto(p.getId(), p.getName(), p.getDescription());
    }

    private static ItemDto toDto(Item i) {
        return new ItemDto(i.getCategory(), i.getProductId(), i.getProductName(),
                i.getAttribute1(), i.getAttribute2(), i.getAttribute3(),
                i.getAttribute4(), i.getAttribute5(), i.getItemId(), i.getDescription(),
                i.getListPrice(), i.getUnitCost(), i.getImageLocation());
    }

    private static List<CategoryDto> mapCategories(List<?> list) {
        return list.stream().map(o -> toDto((Category) o)).toList();
    }

    private static List<ProductDto> mapProducts(List<?> list) {
        return list.stream().map(o -> toDto((Product) o)).toList();
    }

    private static List<ItemDto> mapItems(List<?> list) {
        return list.stream().map(o -> toDto((Item) o)).toList();
    }
}
