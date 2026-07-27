package com.petstore.catalog.repository.mongo;

/**
 * Single source of truth for the catalog MongoDB collection and field names — the
 * {@code mongo}-profile counterpart of the SQL table/column names. Keeping them here
 * (rather than scattering string literals) means the documents, the adapter queries,
 * the seeder and the tests all agree, and a rename is a one-line change.
 *
 * <p><b>Model (Option C — see DECISIONS.md).</b> Three collections mirror the three
 * catalog entities; each document collapses the legacy base + {@code _details} split into
 * an embedded {@code details} map keyed by locale ({@code "en_US"} → localized text). The
 * item document additionally <b>denormalizes</b> its {@code categoryId} and per-locale
 * {@code productName} so {@code getItem} and search never join another collection.
 */
final class MongoSchema {

    private MongoSchema() {
    }

    /** Categories collection ({@code _id} = catid, e.g. {@code "FISH"}). */
    static final String CATEGORIES = "categories";
    /** Products collection ({@code _id} = productid, e.g. {@code "FI-SW-01"}). */
    static final String PRODUCTS = "products";
    /** Items collection ({@code _id} = itemid, e.g. {@code "EST-1"}). */
    static final String ITEMS = "items";

    /** Product→category membership field (product doc). */
    static final String F_CAT_ID = "catId";
    /** Item→product membership field (item doc). */
    static final String F_PRODUCT_ID = "productId";
    /** Item→category membership, denormalized onto the item so getItem needs no join. */
    static final String F_CATEGORY_ID = "categoryId";

    /** Embedded per-locale text map: {@code details.<locale>.<field>}. */
    static final String F_DETAILS = "details";
}
