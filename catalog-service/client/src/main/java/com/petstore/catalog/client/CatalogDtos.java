package com.petstore.catalog.client;

import java.util.List;

/**
 * Wire DTOs for the catalog contract — plain records serialized as JSON. They
 * carry every field of the legacy catalog model so a consumer can reconstruct
 * its own view objects (with the legacy accessor quirks) without loss.
 *
 * <p>Kept framework-free (no JPA/Jackson annotations); Jackson maps records by
 * component name on the consuming side.
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    /** A catalog category (id + localized name/description). */
    public record CategoryDto(String id, String name, String description) {
    }

    /** A product within a category (id + localized name/description). */
    public record ProductDto(String id, String name, String description) {
    }

    /**
     * A purchasable item variant. Mirrors the legacy {@code catalog.model.Item}:
     * all five attributes, pricing (listPrice/unitCost), and image are preserved.
     * {@code category} is left null on item lookups, exactly as legacy did.
     */
    public record ItemDto(
            String category,
            String productId,
            String productName,
            String attribute1,
            String attribute2,
            String attribute3,
            String attribute4,
            String attribute5,
            String itemId,
            String description,
            double listPrice,
            double unitCost,
            String imageLocation) {
    }

    /**
     * A page of results — mirrors the legacy {@code catalog.model.Page}
     * pagination contract: the sublist plus the start offset and whether a next
     * page exists. Previous-page availability is derivable from {@code start}.
     *
     * <p>Concrete per-type page records (rather than a generic {@code PageDto<T>})
     * so Jackson deserializes the element type without a {@code ParameterizedTypeReference}.
     */
    public record CategoryPage(List<CategoryDto> list, int start, boolean nextPageAvailable) {
    }

    public record ProductPage(List<ProductDto> list, int start, boolean nextPageAvailable) {
    }

    public record ItemPage(List<ItemDto> list, int start, boolean nextPageAvailable) {
    }
}
