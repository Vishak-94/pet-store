package com.petstore.catalog.web;

import com.petstore.catalog.CatalogViewMapper;
import com.petstore.catalog.client.CatalogServiceClient;
import com.petstore.catalog.domain.Item;
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

    private final CatalogServiceClient catalog;
    private final com.petstore.cart.service.CartService cart;

    public CatalogController(CatalogServiceClient catalog, com.petstore.cart.service.CartService cart) {
        this.catalog = catalog;
        this.cart = cart;
    }

    /** The active locale as catalog-service's key (e.g. "en_US", "ja_JP", "zh_CN"). */
    private static String currentLocale() {
        return LocaleContextHolder.getLocale().toString();
    }

    @GetMapping("/")
    public String main(Model model) {
        var categories = catalog.getCategories(0, PAGE_SIZE, currentLocale()).list()
                .stream().map(CatalogViewMapper::toCategory).toList();
        model.addAttribute(ATTR_CATEGORIES, categories);
        return VIEW_MAIN;
    }

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

    @GetMapping("/item")
    public String item(@RequestParam(PARAM_ID) String itemId, Model model) {
        model.addAttribute(ATTR_ITEM, catalog.getItem(itemId, currentLocale())
                .map(CatalogViewMapper::toItem).orElse(null));
        model.addAttribute(ATTR_ITEM_QTY, cart.quantityOf(itemId));
        return VIEW_ITEM;
    }

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
