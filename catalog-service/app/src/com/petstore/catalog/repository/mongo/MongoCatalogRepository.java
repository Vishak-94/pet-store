package com.petstore.catalog.repository.mongo;

import com.petstore.catalog.domain.Category;
import com.petstore.catalog.domain.Item;
import com.petstore.catalog.domain.Page;
import com.petstore.catalog.domain.Product;
import com.petstore.catalog.repository.CatalogRepository;
import com.petstore.catalog.repository.CatalogSearchPort;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * MongoDB adapter for the {@link CatalogRepository} port — the {@code mongo}-profile counterpart of
 * {@link com.petstore.catalog.repository.jpa.JpaCatalogRepository}. It maps between the framework-free
 * domain types and the {@code categories}/{@code products}/{@code items} documents, so the domain and
 * {@code CatalogService} never see MongoDB and persistence stays swappable behind the port.
 *
 * <p><b>Parity is preserved</b> (same contract the JPA adapter pins):
 * <ul>
 *   <li>locale-specific reads — every lookup reads the {@code details.<locale>} sub-document, keyed
 *       like the legacy {@code en_US} column ({@link #lang});</li>
 *   <li>a miss is {@link Optional#empty()} / {@link Page#EMPTY_PAGE}, never null / never an error;</li>
 *   <li>ordering: categories/products by localized {@code name} (M1), items/search by {@code itemId};</li>
 *   <li>pagination fetches {@code size + 1} to compute {@code hasNext} precisely (mirrors the JPA
 *       {@code Slice}, L5);</li>
 *   <li>search (H6): whitespace-tokenized, each token a case-insensitive substring match across
 *       product name + category id + item description, tokens OR-joined, attributes never searched.</li>
 * </ul>
 *
 * <p>Every read is a single-collection query — no {@code $lookup} — because {@code categoryId} and the
 * per-locale {@code productName} are denormalized onto the item document (see {@link ItemDocument}).
 */
@Repository
@Profile("mongo")
public class MongoCatalogRepository implements CatalogRepository, CatalogSearchPort {

    /** Default locale key when none is supplied — legacy columns are keyed like {@code en_US}. */
    private static final String DEFAULT_LOCALE_KEY = "en_US";

    private final MongoTemplate mongo;

    MongoCatalogRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    private static String lang(Locale locale) {
        // Legacy locale keys look like "en_US"; match that document sub-key format.
        return locale == null ? DEFAULT_LOCALE_KEY : locale.toString();
    }

    /** {@code details.<locale>} — the embedded sub-document path for a locale. */
    private static String detailsPath(String locale) {
        return MongoSchema.F_DETAILS + "." + locale;
    }

    @Override
    public Optional<Category> getCategory(String categoryId, Locale locale) {
        CategoryDocument doc = mongo.findById(categoryId, CategoryDocument.class);
        return Optional.ofNullable(doc)
                .map(d -> d.details.get(lang(locale)))
                .map(t -> new Category(categoryId, t.name, t.descn));
    }

    @Override
    public Page getCategories(int start, int count, Locale locale) {
        if (count <= 0 || start < 0) {
            return Page.EMPTY_PAGE;
        }
        String key = lang(locale);
        // Only categories that HAVE a row for this locale (legacy: where locale = ?), ordered by
        // localized name. Fetch count+1 to decide hasNext (mirrors the JPA Slice).
        Query q = new Query(Criteria.where(detailsPath(key)).exists(true))
                .with(Sort.by(Sort.Direction.ASC, detailsPath(key) + ".name"))
                .skip(start).limit(count + 1);
        List<CategoryDocument> rows = mongo.find(q, CategoryDocument.class);
        boolean hasNext = rows.size() > count;
        List<Category> cats = rows.stream().limit(count)
                .map(d -> new Category(d.catId, d.details.get(key).name, d.details.get(key).descn))
                .toList();
        return new Page(cats, start, hasNext);
    }

    @Override
    public Optional<Product> getProduct(String productId, Locale locale) {
        ProductDocument doc = mongo.findById(productId, ProductDocument.class);
        return Optional.ofNullable(doc)
                .map(d -> d.details.get(lang(locale)))
                .map(t -> new Product(productId, t.name, t.descn));
    }

    @Override
    public Page getProducts(String categoryId, int start, int count, Locale locale) {
        if (count <= 0 || start < 0) {
            return Page.EMPTY_PAGE;
        }
        String key = lang(locale);
        Query q = new Query(Criteria.where(MongoSchema.F_CAT_ID).is(categoryId)
                .and(detailsPath(key)).exists(true))
                .with(Sort.by(Sort.Direction.ASC, detailsPath(key) + ".name"))
                .skip(start).limit(count + 1);
        List<ProductDocument> rows = mongo.find(q, ProductDocument.class);
        boolean hasNext = rows.size() > count;
        List<Product> products = rows.stream().limit(count)
                .map(d -> new Product(d.productId, d.details.get(key).name, d.details.get(key).descn))
                .toList();
        return new Page(products, start, hasNext);
    }

    @Override
    public Optional<Item> getItem(String itemId, Locale locale) {
        ItemDocument doc = mongo.findById(itemId, ItemDocument.class);
        String key = lang(locale);
        return Optional.ofNullable(doc)
                .filter(d -> d.details.containsKey(key))
                .map(d -> toItem(d, key));
    }

    @Override
    public Page getItems(String productId, int start, int size, Locale locale) {
        if (size <= 0 || start < 0) {
            return Page.EMPTY_PAGE;
        }
        String key = lang(locale);
        // Items within a product that have a row for this locale, ordered by itemid (=_id).
        Query q = new Query(Criteria.where(MongoSchema.F_PRODUCT_ID).is(productId)
                .and(detailsPath(key)).exists(true))
                .with(Sort.by(Sort.Direction.ASC, "_id"))
                .skip(start).limit(size + 1);
        List<ItemDocument> rows = mongo.find(q, ItemDocument.class);
        boolean hasNext = rows.size() > size;
        List<Item> items = rows.stream().limit(size).map(d -> toItem(d, key)).toList();
        return new Page(items, start, hasNext);
    }

    @Override
    public Page searchItems(String query, int start, int size, Locale locale) {
        if (query == null || query.isBlank() || size <= 0 || start < 0) {
            return Page.EMPTY_PAGE;
        }
        // Legacy SEARCH_ITEMS: whitespace-tokenize; a whitespace-only query yields no tokens → empty.
        List<String> tokens = new ArrayList<>();
        for (String t : query.trim().split("\\s+")) {
            if (!t.isBlank()) {
                tokens.add(t);
            }
        }
        if (tokens.isEmpty()) {
            return Page.EMPTY_PAGE;
        }
        String key = lang(locale);
        String nameField = detailsPath(key) + ".productName";
        String descnField = detailsPath(key) + ".descn";
        // Each token ORs a case-insensitive substring match across product name + category id + item
        // descn (never attributes); tokens OR together. Pattern.quote emulates a literal LIKE %token%.
        List<Criteria> perToken = new ArrayList<>();
        for (String token : tokens) {
            String regex = ".*" + Pattern.quote(token) + ".*";
            perToken.add(new Criteria().orOperator(
                    Criteria.where(nameField).regex(regex, "i"),
                    Criteria.where(MongoSchema.F_CATEGORY_ID).regex(regex, "i"),
                    Criteria.where(descnField).regex(regex, "i")));
        }
        Criteria criteria = new Criteria().andOperator(
                Criteria.where(detailsPath(key)).exists(true),
                new Criteria().orOperator(perToken.toArray(new Criteria[0])));
        Query q = new Query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "_id"))
                .skip(start).limit(size + 1);
        List<ItemDocument> rows = mongo.find(q, ItemDocument.class);
        boolean hasNext = rows.size() > size;
        List<Item> items = rows.stream().limit(size).map(d -> toItem(d, key)).toList();
        return new Page(items, start, hasNext);
    }

    /** Assemble a domain Item from the document's locale sub-detail (all fields resolved in-doc). */
    private Item toItem(ItemDocument d, String localeKey) {
        ItemDocument.LocalizedItem t = d.details.get(localeKey);
        return new Item(
                d.categoryId,
                d.productId,
                t.productName,
                t.attr1, t.attr2, t.attr3, t.attr4, t.attr5,
                d.itemId,
                t.descn,
                t.listPrice,
                t.unitCost,
                t.image);
    }
}
