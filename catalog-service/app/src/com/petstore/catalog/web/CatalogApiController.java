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

    private final CatalogService catalog;

    public CatalogApiController(CatalogService catalog) {
        this.catalog = catalog;
    }

    private static Locale locale(String lang) {
        if (lang == null || lang.isBlank()) {
            return Locale.US;
        }
        String[] parts = lang.split("_");
        return parts.length == 2 ? new Locale(parts[0], parts[1]) : new Locale(lang);
    }

    // ---- categories ----

    @GetMapping(CatalogServiceEndpoints.CATEGORIES)
    public CategoryPage categories(@RequestParam(defaultValue = "0") int start,
                                   @RequestParam(defaultValue = "" + DEFAULT_COUNT) int count,
                                   @RequestParam(value = "lang", required = false) String lang) {
        Page page = catalog.getCategories(start, count, locale(lang));
        return new CategoryPage(mapCategories(page.getList()), start, page.isNextPageAvailable());
    }

    @GetMapping(CatalogServiceEndpoints.CATEGORY_BY_ID)
    public ResponseEntity<CategoryDto> category(@PathVariable String id,
                                                @RequestParam(value = "lang", required = false) String lang) {
        return catalog.getCategory(id, locale(lang))
                .map(c -> ResponseEntity.ok(toDto(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(CatalogServiceEndpoints.PRODUCTS_IN_CATEGORY)
    public ProductPage productsInCategory(@PathVariable String id,
                                          @RequestParam(defaultValue = "0") int start,
                                          @RequestParam(defaultValue = "" + DEFAULT_COUNT) int count,
                                          @RequestParam(value = "lang", required = false) String lang) {
        Page page = catalog.getProducts(id, start, count, locale(lang));
        return new ProductPage(mapProducts(page.getList()), start, page.isNextPageAvailable());
    }

    // ---- products ----

    @GetMapping(CatalogServiceEndpoints.PRODUCT_BY_ID)
    public ResponseEntity<ProductDto> product(@PathVariable String id,
                                              @RequestParam(value = "lang", required = false) String lang) {
        return catalog.getProduct(id, locale(lang))
                .map(p -> ResponseEntity.ok(toDto(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(CatalogServiceEndpoints.ITEMS_IN_PRODUCT)
    public ItemPage itemsInProduct(@PathVariable String id,
                                   @RequestParam(defaultValue = "0") int start,
                                   @RequestParam(defaultValue = "" + DEFAULT_COUNT) int count,
                                   @RequestParam(value = "lang", required = false) String lang) {
        Page page = catalog.getItems(id, start, count, locale(lang));
        return new ItemPage(mapItems(page.getList()), start, page.isNextPageAvailable());
    }

    // ---- items ----

    @GetMapping(CatalogServiceEndpoints.ITEM_BY_ID)
    public ResponseEntity<ItemDto> item(@PathVariable String id,
                                        @RequestParam(value = "lang", required = false) String lang) {
        return catalog.getItem(id, locale(lang))
                .map(i -> ResponseEntity.ok(toDto(i)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(CatalogServiceEndpoints.ITEMS_SEARCH)
    public ItemPage search(@RequestParam(defaultValue = "") String keyword,
                           @RequestParam(defaultValue = "0") int start,
                           @RequestParam(defaultValue = "" + DEFAULT_COUNT) int count,
                           @RequestParam(value = "lang", required = false) String lang) {
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
