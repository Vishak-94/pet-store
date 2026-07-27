package com.petstore.catalog.web;

import com.petstore.catalog.CatalogViewMapper;
import com.petstore.catalog.client.CatalogServiceClient;
import com.petstore.catalog.domain.Item;
import com.petstore.inventory.client.InventoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Catalog browse UI — replaces the legacy WAF {@code *.screen} flow + JSPs.
 * Thin controller: it orchestrates the {@link CatalogServiceClient} (the
 * catalog-service SDK) and selects a Thymeleaf view (Single Responsibility).
 *
 * <p>Locale: the store is multi-lingual (en/ja/zh). The active locale is resolved
 * from the {@code lang} cookie (persisted across pages, switched via {@code ?lang=})
 * by Spring's LocaleResolver — see WebConfig. Catalog content is fetched from
 * catalog-service for that locale (its locale-split tables).
 */
@Controller
public class CatalogController {

    private static final int PAGE_SIZE = 10;

    /** Thymeleaf view names for the browse screens. */
    private static final String VIEW_MAIN = "main";
    private static final String VIEW_CATEGORY = "category";
    private static final String VIEW_PRODUCT = "product";
    private static final String VIEW_ITEM = "item";
    private static final String VIEW_SEARCH = "search";

    /** Request params on the browse routes. */
    private static final String PARAM_ID = "id";
    private static final String PARAM_START = "start";
    private static final String PARAM_KEYWORD = "keyword";
    private static final String DEFAULT_START = "0";

    /** Model attribute keys consumed by the browse templates. */
    private static final String ATTR_CATEGORIES = "categories";
    private static final String ATTR_CATEGORY = "category";
    private static final String ATTR_PRODUCTS = "products";
    private static final String ATTR_PRODUCT = "product";
    private static final String ATTR_ITEMS = "items";
    private static final String ATTR_ITEM = "item";
    private static final String ATTR_KEYWORD = "keyword";
    private static final String ATTR_CART_QTY = "cartQty";
    private static final String ATTR_ITEM_QTY = "itemQty";
    /** On-hand stock for the item page's live badge; {@code null} → template hides the badge. */
    private static final String ATTR_STOCK = "stock";

    private static final Logger log = LoggerFactory.getLogger(CatalogController.class);

    private final CatalogServiceClient catalog;
    private final com.petstore.cart.service.CartService cart;
    private final InventoryClient inventory;

    public CatalogController(CatalogServiceClient catalog, com.petstore.cart.service.CartService cart,
                             InventoryClient inventory) {
        this.catalog = catalog;
        this.cart = cart;
        this.inventory = inventory;
    }

    /** The active locale as catalog-service's key (e.g. "en_US", "ja_JP", "zh_CN"). */
    private static String currentLocale() {
        return LocaleContextHolder.getLocale().toString();
    }

    /**
     * Store home page — lists the top categories for the active locale.
     *
     * <pre>{@code
     * GET /
     * (locale from the `lang` cookie, e.g. en_US)
     *
     * 200 OK  renders main.html
     *   model: categories = [{id:"FISH", name:"Fish"}, {id:"DOGS", name:"Dogs"}, ...]
     * }</pre>
     */
    @GetMapping("/")
    public String main(Model model) {
        var categories = catalog.getCategories(0, PAGE_SIZE, currentLocale()).list()
                .stream().map(CatalogViewMapper::toCategory).toList();
        model.addAttribute(ATTR_CATEGORIES, categories);
        return VIEW_MAIN;
    }

    /**
     * Category page — the products in a category, paged by {@code start} (page size 10).
     *
     * <pre>{@code
     * GET /category?id=FISH&start=0
     *
     * 200 OK  renders category.html
     *   model: category = {id:"FISH", name:"Fish"}   // null if the id is unknown
     *          products = [{id:"FI-SW-01", name:"Angelfish"}, ...]   // up to 10
     * }</pre>
     *
     * <p>An unknown {@code id} still renders the page with {@code category=null} and an empty
     * product list (no 404); a missing {@code id} param is a 400 (required request param).
     */
    @GetMapping("/category")
    public String category(@RequestParam(PARAM_ID) String categoryId,
                           @RequestParam(value = PARAM_START, defaultValue = DEFAULT_START) int start,
                           Model model) {
        String loc = currentLocale();
        model.addAttribute(ATTR_CATEGORY, catalog.getCategory(categoryId, loc)
                .map(CatalogViewMapper::toCategory).orElse(null));
        model.addAttribute(ATTR_PRODUCTS, catalog.getProducts(categoryId, start, PAGE_SIZE, loc).list()
                .stream().map(CatalogViewMapper::toProduct).toList());
        return VIEW_CATEGORY;
    }

    /**
     * Product page — the items (SKUs) under a product, paged by {@code start} (page size 10).
     * Also seeds each item's current cart quantity so the in-page stepper renders its count.
     *
     * <pre>{@code
     * GET /product?id=FI-SW-01&start=0
     *
     * 200 OK  renders product.html
     *   model: product  = {id:"FI-SW-01", name:"Angelfish"}   // null if unknown
     *          items    = [{itemId:"EST-1", attribute:"Large", listPrice:16.50}, ...]
     *          cartQty  = {"EST-1":2, ...}   // per-item quantity already in the cart
     * }</pre>
     *
     * <p>Missing {@code id} → 400; unknown {@code id} renders with {@code product=null}.
     */
    @GetMapping("/product")
    public String product(@RequestParam(PARAM_ID) String productId,
                          @RequestParam(value = PARAM_START, defaultValue = DEFAULT_START) int start,
                          Model model) {
        String loc = currentLocale();
        List<Item> items = catalog.getItems(productId, start, PAGE_SIZE, loc).list()
                .stream().map(CatalogViewMapper::toItem).toList();
        model.addAttribute(ATTR_PRODUCT, catalog.getProduct(productId, loc)
                .map(CatalogViewMapper::toProduct).orElse(null));
        model.addAttribute(ATTR_ITEMS, items);
        model.addAttribute(ATTR_CART_QTY, cartQuantities(items));
        return VIEW_PRODUCT;
    }

    /**
     * Item (SKU) detail page, with the item's current cart quantity for the stepper.
     *
     * <pre>{@code
     * GET /item?id=EST-1
     *
     * 200 OK  renders item.html
     *   model: item    = {itemId:"EST-1", attribute:"Large", listPrice:16.50}   // null if unknown
     *          itemQty = 2   // how many of this item are in the cart (0 if none)
     *          stock   = 7   // on-hand from inventory-service; null when unavailable (badge hidden)
     * }</pre>
     *
     * <p>Missing {@code id} → 400; unknown {@code id} renders with {@code item=null}.
     *
     * <p>The {@code stock} attribute is composed at read time from inventory-service (read-time
     * API composition); a slow/down inventory-service degrades to {@code null} (badge hidden) and
     * never blocks browsing — see {@link #resolveStock(String)}.
     */
    @GetMapping("/item")
    public String item(@RequestParam(PARAM_ID) String itemId, Model model) {
        model.addAttribute(ATTR_ITEM, catalog.getItem(itemId, currentLocale())
                .map(CatalogViewMapper::toItem).orElse(null));
        model.addAttribute(ATTR_ITEM_QTY, cart.quantityOf(itemId));
        model.addAttribute(ATTR_STOCK, resolveStock(itemId));
        return VIEW_ITEM;
    }

    /**
     * Live on-hand stock for the item-page badge, or {@code null} if it can't be determined.
     * Cosmetic and fully degradable: any failure (breaker open, timeout, inventory-service down)
     * is swallowed and returns {@code null} so the template simply hides the badge — the page
     * still renders. Mirrors the safe-swallow pattern in {@code GlobalModelAdvice.cartCount}.
     */
    private Integer resolveStock(String itemId) {
        try {
            return inventory.stockFor(itemId).orElse(null);
        } catch (RuntimeException e) {
            log.debug("stock unavailable for {}, hiding badge: {}", itemId, e.getMessage());
            return null;
        }
    }

    /**
     * Keyword search over items for the active locale (first page, size 10). Seeds cart
     * quantities for the results so the stepper renders correctly.
     *
     * <pre>{@code
     * GET /search?keyword=dog
     *
     * 200 OK  renders search.html
     *   model: keyword = "dog"
     *          items   = [{itemId:"K9-BD-01", attribute:"Male Adult", ...}, ...]
     *          cartQty = {"K9-BD-01":0, ...}
     * }</pre>
     *
     * <p>{@code keyword} defaults to empty (no 400), so {@code GET /search} with no param
     * renders the page with whatever the empty-keyword search returns.
     */
    @GetMapping("/search")
    public String search(@RequestParam(value = PARAM_KEYWORD, defaultValue = "") String keyword,
                         Model model) {
        List<Item> items = catalog.searchItems(keyword, 0, PAGE_SIZE, currentLocale()).list()
                .stream().map(CatalogViewMapper::toItem).toList();
        model.addAttribute(ATTR_KEYWORD, keyword);
        model.addAttribute(ATTR_ITEMS, items);
        model.addAttribute(ATTR_CART_QTY, cartQuantities(items));
        return VIEW_SEARCH;
    }

    /** Current cart quantity per item id, to seed the in-page stepper. */
    private java.util.Map<String, Integer> cartQuantities(java.util.List<Item> items) {
        java.util.Map<String, Integer> qty = new java.util.HashMap<>();
        for (Item it : items) {
            qty.put(it.getItemId(), cart.quantityOf(it.getItemId()));
        }
        return qty;
    }
}
