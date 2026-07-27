package com.petstore.catalog.repository.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MongoDB document form of a catalog item — the {@code mongo}-profile counterpart of the JPA
 * {@code item} + {@code item_details} tables, collapsed into ONE document.
 *
 * <p>Two <b>denormalizations</b> (Option C, DECISIONS.md) keep every item read single-collection:
 * <ul>
 *   <li>{@link #categoryId} is copied onto the item (legacy resolved it via
 *       {@code item→product→catid}), so {@code getItem} fills the domain {@code Item.category}
 *       with no join;</li>
 *   <li>each locale's {@link LocalizedItem#productName} is copied in, so keyword search —
 *       which legacy matched over the product name — is a single scan of this collection rather
 *       than a {@code $lookup} into {@code products}. Product names change rarely, so the copy is
 *       cheap to keep correct.</li>
 * </ul>
 *
 * <p>{@code _id} is the itemid. {@link #productId} is the item→product FK (indexed for
 * {@code getItems(productId)}); {@link #details} is the embedded per-locale pricing/text map.
 */
@Document(collection = MongoSchema.ITEMS)
class ItemDocument {

    @Id
    String itemId;

    /** Owning product id (item.productid) — indexed for the by-product listing. */
    @Indexed
    @Field(MongoSchema.F_PRODUCT_ID)
    String productId;

    /** Owning category id — denormalized (legacy resolved it via product.catid) so getItem needs no join. */
    @Field(MongoSchema.F_CATEGORY_ID)
    String categoryId;

    /** locale key → localized pricing/description/attributes (+ denormalized product name). */
    @Field(MongoSchema.F_DETAILS)
    Map<String, LocalizedItem> details = new LinkedHashMap<>();

    /**
     * Embedded per-locale item detail — mirrors the legacy {@code item_details} row plus the
     * denormalized {@code productName} used by search. Prices are stored as {@code double}
     * (the domain {@code Item} exposes double; money-as-BigDecimal is deferred repo-wide).
     */
    static class LocalizedItem {
        String descn;
        String image;
        double listPrice;
        double unitCost;
        /** Denormalized product name for this locale — searched by keyword (legacy SEARCH_ITEMS). */
        String productName;
        String attr1;
        String attr2;
        String attr3;
        String attr4;
        String attr5;
    }
}
