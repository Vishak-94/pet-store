package com.petstore.catalog;

import com.petstore.catalog.domain.Page;
import com.petstore.catalog.service.CatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests pinning the LEGACY catalog behaviour, carried over from
 * the monolith unchanged. They assert the observable contract of the old
 * CatalogEJB / CatalogDAO — they must stay green in catalog-service.
 */
@SpringBootTest
class CatalogCharacterizationTest {

    private static final Locale EN = Locale.US;

    @Autowired
    CatalogService catalog;

    @Test
    void category_knownId_returnsLocaleSpecificData() {
        var cat = catalog.getCategory("FISH", EN);
        assertThat(cat).isPresent();
        assertThat(cat.get().getName()).isEqualTo("Fish");
    }

    @Test
    void category_unknownId_returnsEmpty_notError() {
        // Legacy contract: a miss is Optional.empty() — never null, never a 404.
        assertThat(catalog.getCategory("NOPE", EN)).isEmpty();
    }

    @Test
    void products_unknownCategory_returnsEmptyPage_notError() {
        Page page = catalog.getProducts("NOPE", 0, 10, EN);
        assertThat(page.getSize()).isZero();
        assertThat(page.isNextPageAvailable()).isFalse();
    }

    @Test
    void products_knownCategory_returnsProducts() {
        Page page = catalog.getProducts("FISH", 0, 10, EN);
        assertThat(page.getSize()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void items_ofProduct_carryPriceAndProductName() {
        Page page = catalog.getItems("FI-SW-01", 0, 10, EN);
        assertThat(page.getSize()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void item_knownId_resolvesProductNameAndPrice() {
        var item = catalog.getItem("EST-1", EN);
        assertThat(item).isPresent();
        assertThat(item.get().getProductName()).isEqualTo("Angelfish");
        assertThat(item.get().getListCost()).isEqualTo(16.50);
    }

    @Test
    void item_unknownId_returnsEmpty() {
        assertThat(catalog.getItem("EST-999", EN)).isEmpty();
    }

    @Test
    void search_matchesDescription() {
        Page page = catalog.searchItems("Angelfish", 0, 10, EN);
        assertThat(page.getSize()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void search_blankQuery_returnsEmptyPage() {
        assertThat(catalog.searchItems("  ", 0, 10, EN).getSize()).isZero();
    }
}
