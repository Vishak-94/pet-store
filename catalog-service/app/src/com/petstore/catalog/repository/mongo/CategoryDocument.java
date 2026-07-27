package com.petstore.catalog.repository.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MongoDB document form of a catalog category — the {@code mongo}-profile counterpart of the
 * JPA {@code category} + {@code category_details} tables, collapsed into ONE document.
 *
 * <p>The legacy locale split (a {@code category_details} row per {@code (catid, locale)}) becomes
 * an embedded {@link #details} map keyed by locale ({@code "en_US"} → its localized text), so a
 * locale read is {@code details.get("en_US")} with no join. {@code _id} is the catid (natural key).
 *
 * <p>Mapped fields are framework-annotated here only; the domain {@code Category} stays
 * framework-free and the mapping lives entirely in {@link MongoCatalogRepository}.
 */
@Document(collection = MongoSchema.CATEGORIES)
class CategoryDocument {

    @Id
    String catId;

    /** locale key (e.g. {@code "en_US"}) → localized name/description/image. */
    @Field(MongoSchema.F_DETAILS)
    Map<String, LocalizedText> details = new LinkedHashMap<>();

    /** Embedded localized text (name + description + image) — shared shape with products. */
    static class LocalizedText {
        String name;
        String descn;
        String image;
    }
}
