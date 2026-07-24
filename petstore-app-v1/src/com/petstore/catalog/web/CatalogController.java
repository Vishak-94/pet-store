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
        model.addAttribute("categories", categories);
        return "main";
    }

    @GetMapping("/category")
    public String category(@RequestParam("id") String categoryId,
                           @RequestParam(value = "start", defaultValue = "0") int start,
                           Model model) {
        String loc = currentLocale();
        model.addAttribute("category", catalog.getCategory(categoryId, loc)
                .map(CatalogViewMapper::toCategory).orElse(null));
        model.addAttribute("products", catalog.getProducts(categoryId, start, PAGE_SIZE, loc).list()
                .stream().map(CatalogViewMapper::toProduct).toList());
        return "category";
    }

    @GetMapping("/product")
    public String product(@RequestParam("id") String productId,
                          @RequestParam(value = "start", defaultValue = "0") int start,
                          Model model) {
        String loc = currentLocale();
        List<Item> items = catalog.getItems(productId, start, PAGE_SIZE, loc).list()
                .stream().map(CatalogViewMapper::toItem).toList();
        model.addAttribute("product", catalog.getProduct(productId, loc)
                .map(CatalogViewMapper::toProduct).orElse(null));
        model.addAttribute("items", items);
        model.addAttribute("cartQty", cartQuantities(items));
        return "product";
    }

    @GetMapping("/item")
    public String item(@RequestParam("id") String itemId, Model model) {
        model.addAttribute("item", catalog.getItem(itemId, currentLocale())
                .map(CatalogViewMapper::toItem).orElse(null));
        model.addAttribute("itemQty", cart.quantityOf(itemId));
        return "item";
    }

    @GetMapping("/search")
    public String search(@RequestParam(value = "keyword", defaultValue = "") String keyword,
                         Model model) {
        List<Item> items = catalog.searchItems(keyword, 0, PAGE_SIZE, currentLocale()).list()
                .stream().map(CatalogViewMapper::toItem).toList();
        model.addAttribute("keyword", keyword);
        model.addAttribute("items", items);
        model.addAttribute("cartQty", cartQuantities(items));
        return "search";
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
