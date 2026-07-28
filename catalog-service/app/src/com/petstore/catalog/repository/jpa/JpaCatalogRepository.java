package com.petstore.catalog.repository.jpa;

import com.petstore.catalog.domain.Category;
import com.petstore.catalog.domain.Item;
import com.petstore.catalog.domain.Page;
import com.petstore.catalog.domain.Product;
import com.petstore.catalog.repository.CatalogRepository;
import com.petstore.catalog.repository.CatalogSearchPort;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
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
 *
 * <p>Active on the <b>default</b> profile only ({@code @Profile("!mongo")}); under the
 * {@code mongo} profile the {@code repository.mongo} adapter serves the same ports instead.
 *
 * <p>Implements both the browse port ({@link CatalogRepository}) and the segregated
 * keyword-search port ({@link CatalogSearchPort}); the H2/JPA store backs both today.
 */
@Repository
@Profile("!mongo")
public class JpaCatalogRepository implements CatalogRepository, CatalogSearchPort {

    private final CategoryDetailRepository categoryDetails;
    private final ProductBaseRepository productBase;
    private final ProductDetailRepository productDetails;
    private final ItemBaseRepository itemBase;
    private final ItemDetailRepository itemDetails;

    JpaCatalogRepository(CategoryDetailRepository categoryDetails,
                         ProductBaseRepository productBase,
                         ProductDetailRepository productDetails,
                         ItemBaseRepository itemBase,
                         ItemDetailRepository itemDetails) {
        this.categoryDetails = categoryDetails;
        this.productBase = productBase;
        this.productDetails = productDetails;
        this.itemBase = itemBase;
        this.itemDetails = itemDetails;
    }

    /** Default locale key when none is supplied — legacy columns are keyed like {@code en_US}. */
    private static final String DEFAULT_LOCALE_KEY = "en_US";

    private static String lang(Locale locale) {
        // Legacy locale keys look like "en_US"; match that column format.
        return locale == null ? DEFAULT_LOCALE_KEY : locale.toString();
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
                .findByLocaleOrderByName(lang(locale), PageRequest.of(pageIndex, count))
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
        Slice<ProductDetailEntity> slice = productDetails
                .findByCategory(categoryId, lang(locale), PageRequest.of(pageIndex, count));
        List<Product> products = slice.getContent()
                .stream().map(e -> new Product(e.productid, e.name, e.descn)).toList();
        return new Page(products, start, slice.hasNext());
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
        Slice<ItemDetailEntity> slice = itemDetails
                .findByProduct(productId, lang(locale), PageRequest.of(pageIndex, size));
        List<Item> items = slice.getContent()
                .stream().map(d -> toItem(d, locale)).toList();
        return new Page(items, start, slice.hasNext());
    }

    @Override
    public Page searchItems(String query, int start, int size, Locale locale) {
        if (query == null || query.isBlank() || size <= 0 || start < 0) {
            return Page.EMPTY_PAGE;
        }
        // Legacy SEARCH_ITEMS tokenizes the query on whitespace; a query that is
        // only whitespace yields no keywords → empty page.
        List<String> tokens = Arrays.stream(query.trim().split("\\s+"))
                .filter(t -> !t.isBlank()).toList();
        if (tokens.isEmpty()) {
            return Page.EMPTY_PAGE;
        }
        // Fetch one row past the requested count so hasNext is precise (legacy
        // read one row past `count` to decide); return only the first `size`.
        List<ItemDetailEntity> rows = itemDetails.search(tokens, lang(locale), start, size + 1);
        boolean hasNext = rows.size() > size;
        List<Item> items = rows.stream().limit(size).map(d -> toItem(d, locale)).toList();
        return new Page(items, start, hasNext);
    }

    /** Assemble a domain Item, resolving its product membership + product name. */
    private Item toItem(ItemDetailEntity d, Locale locale) {
        String productId = itemBase.findById(d.itemid).map(b -> b.productid).orElse(null);
        String productName = productId == null ? null
                : productDetails.findByProductidAndLocale(productId, lang(locale))
                        .map(p -> p.name).orElse(null);
        // Legacy GET_ITEM selected `catid` into Item.category; resolve it from the
        // product's category membership (product.catid).
        String category = productId == null ? null
                : productBase.findById(productId).map(b -> b.catid).orElse(null);
        return new Item(
                category,
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
