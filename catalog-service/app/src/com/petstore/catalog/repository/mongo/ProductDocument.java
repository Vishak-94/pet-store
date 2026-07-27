package com.petstore.catalog.repository.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MongoDB document form of a catalog product — the {@code mongo}-profile counterpart of the
 * JPA {@code product} + {@code product_details} tables, collapsed into ONE document.
 *
 * <p>{@link #catId} is the product→category membership FK, kept as a plain indexed field so
 * {@code getProducts(categoryId)} is a single {@code find({catId: ...})}. The locale split becomes
 * the embedded {@link #details} map (same {@link CategoryDocument.LocalizedText} shape). {@code _id}
 * is the productid.
 */
@Document(collection = MongoSchema.PRODUCTS)
class ProductDocument {

    @Id
    String productId;

    /** Owning category id (product.catid) — indexed for the by-category listing. */
    @Indexed
    @Field(MongoSchema.F_CAT_ID)
    String catId;

    /** locale key → localized name/description/image. */
    @Field(MongoSchema.F_DETAILS)
    Map<String, CategoryDocument.LocalizedText> details = new LinkedHashMap<>();
}
