package com.petstore.catalog.repository.jpa;

import com.petstore.catalog.domain.Category;
import com.petstore.catalog.domain.Item;
import com.petstore.catalog.domain.Page;
import com.petstore.catalog.domain.Product;
import com.petstore.catalog.repository.CatalogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * JPA-backed <b>adapter</b> implementing the {@link CatalogRepository} port.
 *
 * <p>Replaces the legacy {@code CloudscapeCatalogDAO}/{@code GenericCatalogDAO}.
 * Maps the locale-split entities to framework-free domain objects and preserves
 * the legacy contract: not-found lookups return {@link Optional#empty()} /
 * {@link Page#EMPTY_PAGE} (never null, never an error).
 */
@Repository
public class JpaCatalogRepository implements CatalogRepository {

    private final CategoryDetailRepository categoryDetails;
    private final ProductDetailRepository productDetails;
    private final ItemBaseRepository itemBase;
    private final ItemDetailRepository itemDetails;

    JpaCatalogRepository(CategoryDetailRepository categoryDetails,
                         ProductDetailRepository productDetails,
                         ItemBaseRepository itemBase,
                         ItemDetailRepository itemDetails) {
        this.categoryDetails = categoryDetails;
        this.productDetails = productDetails;
        this.itemBase = itemBase;
        this.itemDetails = itemDetails;
    }

    private static String lang(Locale locale) {
        // Legacy locale keys look like "en_US"; match that column format.
        return locale == null ? "en_US" : locale.toString();
    }

    @Override
    public Optional<Category> getCategory(String categoryId, Locale locale) {
        return categoryDetails.findByCatidAndLocale(categoryId, lang(locale))
                .map(e -> new Category(e.catid, e.name, e.descn));
    }

    @Override
    public Page getCategories(int start, int count, Locale locale) {
        if (count <= 0 || start < 0) {
            return Page.EMPTY_PAGE;
        }
        int pageIndex = start / count;
        List<Category> cats = categoryDetails
                .findByLocaleOrderByCatid(lang(locale), PageRequest.of(pageIndex, count))
                .stream().map(e -> new Category(e.catid, e.name, e.descn)).toList();
        long total = categoryDetails.countByLocale(lang(locale));
        return new Page(cats, start, start + cats.size() < total);
    }

    @Override
    public Optional<Product> getProduct(String productId, Locale locale) {
        return productDetails.findByProductidAndLocale(productId, lang(locale))
                .map(e -> new Product(e.productid, e.name, e.descn));
    }

    @Override
    public Page getProducts(String categoryId, int start, int count, Locale locale) {
        if (count <= 0 || start < 0) {
            return Page.EMPTY_PAGE;
        }
        int pageIndex = start / count;
        List<Product> products = productDetails
                .findByCategory(categoryId, lang(locale), PageRequest.of(pageIndex, count))
                .stream().map(e -> new Product(e.productid, e.name, e.descn)).toList();
        return new Page(products, start, products.size() == count);
    }

    @Override
    public Optional<Item> getItem(String itemId, Locale locale) {
        return itemDetails.findByItemidAndLocale(itemId, lang(locale))
                .map(d -> toItem(d, locale));
    }

    @Override
    public Page getItems(String productId, int start, int size, Locale locale) {
        if (size <= 0 || start < 0) {
            return Page.EMPTY_PAGE;
        }
        int pageIndex = start / size;
        List<Item> items = itemDetails
                .findByProduct(productId, lang(locale), PageRequest.of(pageIndex, size))
                .stream().map(d -> toItem(d, locale)).toList();
        return new Page(items, start, items.size() == size);
    }

    @Override
    public Page searchItems(String query, int start, int size, Locale locale) {
        if (query == null || query.isBlank() || size <= 0 || start < 0) {
            return Page.EMPTY_PAGE;
        }
        int pageIndex = start / size;
        List<Item> items = itemDetails
                .search(query.trim(), lang(locale), PageRequest.of(pageIndex, size))
                .stream().map(d -> toItem(d, locale)).toList();
        return new Page(items, start, items.size() == size);
    }

    /** Assemble a domain Item, resolving its product membership + product name. */
    private Item toItem(ItemDetailEntity d, Locale locale) {
        String productId = itemBase.findById(d.itemid).map(b -> b.productid).orElse(null);
        String productName = productId == null ? null
                : productDetails.findByProductidAndLocale(productId, lang(locale))
                        .map(p -> p.name).orElse(null);
        return new Item(
                null,                       // category not carried on item_details (legacy left null here)
                productId,
                productName,
                d.attr1, d.attr2, d.attr3, d.attr4, d.attr5,
                d.itemid,
                d.descn,
                d.listprice == null ? 0d : d.listprice.doubleValue(),
                d.unitcost == null ? 0d : d.unitcost.doubleValue(),
                d.image);
    }
}
