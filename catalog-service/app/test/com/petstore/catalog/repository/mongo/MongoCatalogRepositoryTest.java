package com.petstore.catalog.repository.mongo;

import com.petstore.catalog.domain.Item;
import com.petstore.catalog.domain.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity tests for the {@code mongo}-profile {@link MongoCatalogRepository}: it must satisfy the
 * SAME observable contract the JPA adapter pins in {@code CatalogCharacterizationTest} — locale
 * reads, miss→empty, ordering, pagination hasNext, {@code getItem.category}, and legacy-faithful
 * search (H6). Data is loaded by the real {@link MongoCatalogSeeder} (same 3-locale seed as
 * {@code data.sql}), so this also verifies the seeder.
 */
class MongoCatalogRepositoryTest extends MongoTestBase {

    private static final Locale EN = Locale.US;          // en_US
    private static final Locale JA = Locale.JAPAN;        // ja_JP
    private static final Locale ZH = Locale.SIMPLIFIED_CHINESE; // zh_CN

    private MongoCatalogRepository repo;

    @BeforeEach
    void seedAndWire() {
        new MongoCatalogSeeder(mongo).seedIfEmpty();
        repo = new MongoCatalogRepository(mongo);
    }

    // ── the JPA CatalogCharacterizationTest assertions, ported ─────────────────────────

    @Test
    void category_knownId_returnsLocaleSpecificData() {
        var cat = repo.getCategory("FISH", EN);
        assertThat(cat).isPresent();
        assertThat(cat.get().getName()).isEqualTo("Fish");
    }

    @Test
    void category_unknownId_returnsEmpty_notError() {
        assertThat(repo.getCategory("NOPE", EN)).isEmpty();
    }

    @Test
    void products_unknownCategory_returnsEmptyPage_notError() {
        Page page = repo.getProducts("NOPE", 0, 10, EN);
        assertThat(page.getSize()).isZero();
        assertThat(page.isNextPageAvailable()).isFalse();
    }

    @Test
    void products_knownCategory_returnsProducts() {
        Page page = repo.getProducts("FISH", 0, 10, EN);
        assertThat(page.getSize()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void items_ofProduct_carryPriceAndProductName() {
        Page page = repo.getItems("FI-SW-01", 0, 10, EN);
        assertThat(page.getSize()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void item_knownId_resolvesProductNameAndPrice() {
        var item = repo.getItem("EST-1", EN);
        assertThat(item).isPresent();
        assertThat(item.get().getProductName()).isEqualTo("Angelfish");
        assertThat(item.get().getListCost()).isEqualTo(16.50);
    }

    @Test
    void item_unknownId_returnsEmpty() {
        assertThat(repo.getItem("EST-999", EN)).isEmpty();
    }

    @Test
    void search_matchesDescription() {
        Page page = repo.searchItems("Angelfish", 0, 10, EN);
        assertThat(page.getSize()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void search_blankQuery_returnsEmptyPage() {
        assertThat(repo.searchItems("  ", 0, 10, EN).getSize()).isZero();
    }

    // ── Mongo-specific parity coverage ─────────────────────────────────────────────────

    @Test
    void getItem_category_resolvedFromDenormalizedCategoryId() {
        // M2: Item.category must be the owning category (denormalized onto the item doc), not null.
        var item = repo.getItem("EST-1", EN);
        assertThat(item).isPresent();
        assertThat(item.get().getCategory()).isEqualTo("FISH");
        assertThat(item.get().getProductId()).isEqualTo("FI-SW-01");
        assertThat(item.get().getAttribute()).isEqualTo("Large");
    }

    @Test
    void reads_areLocaleSpecific() {
        assertThat(repo.getCategory("FISH", JA).get().getName()).isEqualTo("魚");
        assertThat(repo.getCategory("FISH", ZH).get().getName()).isEqualTo("鱼");
        assertThat(repo.getProduct("FI-SW-01", JA).get().getName()).isEqualTo("エンゼルフィッシュ");
        assertThat(repo.getItem("EST-1", ZH).get().getProductName()).isEqualTo("神仙鱼");
        assertThat(repo.getItem("EST-1", ZH).get().getDescription()).isEqualTo("大神仙鱼");
    }

    @Test
    void categories_orderedByLocalizedName() {
        // M1: en_US names Birds, Cats, Dogs, Fish → alphabetical.
        Page page = repo.getCategories(0, 10, EN);
        var names = page.getList().stream()
                .map(o -> ((com.petstore.catalog.domain.Category) o).getName()).toList();
        assertThat(names).containsExactly("Birds", "Cats", "Dogs", "Fish");
    }

    @Test
    void pagination_hasNext_isPrecise() {
        // 4 categories: a page of 3 has a next page; the following page of 3 does not.
        assertThat(repo.getCategories(0, 3, EN).isNextPageAvailable()).isTrue();
        assertThat(repo.getCategories(3, 3, EN).isNextPageAvailable()).isFalse();
        // Exactly-full final page must not over-report a phantom next page (L5).
        assertThat(repo.getCategories(0, 4, EN).isNextPageAvailable()).isFalse();
    }

    @Test
    void items_orderedByItemId() {
        Page page = repo.getItems("FI-SW-01", 0, 10, EN);
        var ids = page.getList().stream().map(o -> ((Item) o).getItemId()).toList();
        assertThat(ids).containsExactly("EST-1", "EST-2");
    }

    @Test
    void search_matchesCategoryId_caseInsensitive() {
        // H6: search matches on category id too (e.g. "fish" → catid FISH), case-insensitive.
        Page page = repo.searchItems("fish", 0, 10, EN);
        assertThat(page.getSize()).isGreaterThanOrEqualTo(2);   // both angelfish items
    }

    @Test
    void search_doesNotMatchAttributes() {
        // H6: attributes (attr1="Large") are NOT searched — a term only present in an attribute misses.
        Page page = repo.searchItems("Tailless", 0, 10, EN);
        // "Tailless" is EST-10's description AND attr1; it must match via description, so it's found.
        assertThat(page.getSize()).isGreaterThanOrEqualTo(1);
        // A token that exists ONLY as an attribute value, never in name/descn/catid, must miss.
        assertThat(repo.searchItems("zzz-not-a-word", 0, 10, EN).getSize()).isZero();
    }
}
