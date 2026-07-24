package com.petstore.catalog;

import com.petstore.catalog.client.CatalogDtos.CategoryDto;
import com.petstore.catalog.client.CatalogDtos.ItemDto;
import com.petstore.catalog.client.CatalogDtos.ProductDto;
import com.petstore.catalog.domain.Category;
import com.petstore.catalog.domain.Item;
import com.petstore.catalog.domain.Product;

/**
 * Maps catalog-service SDK DTOs to the monolith's framework-free view models
 * ({@link Category}/{@link Product}/{@link Item}). The view models are kept
 * because the Thymeleaf templates read them via legacy JavaBean getters
 * ({@code i.listCost}, {@code item.attribute}, …) — records aren't usable from
 * SpringEL here — and because CartService depends on the {@link Item} shape.
 *
 * <p>The mapping is 1:1 and preserves the legacy accessor quirks verbatim, so
 * observable behaviour is unchanged by the split.
 */
public final class CatalogViewMapper {

    private CatalogViewMapper() {
    }

    public static Category toCategory(CategoryDto d) {
        return new Category(d.id(), d.name(), d.description());
    }

    public static Product toProduct(ProductDto d) {
        return new Product(d.id(), d.name(), d.description());
    }

    public static Item toItem(ItemDto d) {
        return new Item(
                d.category(), d.productId(), d.productName(),
                d.attribute1(), d.attribute2(), d.attribute3(), d.attribute4(), d.attribute5(),
                d.itemId(), d.description(), d.listPrice(), d.unitCost(), d.imageLocation());
    }
}
