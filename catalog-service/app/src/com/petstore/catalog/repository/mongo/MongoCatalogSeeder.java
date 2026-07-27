package com.petstore.catalog.repository.mongo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds the MongoDB catalog collections on first startup under the {@code mongo} profile — the
 * document equivalent of the H2 profile's {@code data.sql}. The relational profile is seeded by
 * Spring's {@code spring.sql.init} from {@code data.sql}; MongoDB has no such hook, so this
 * component loads the SAME data (category/product/item in {@code en_US}, {@code ja_JP},
 * {@code zh_CN}) into the {@code categories}/{@code products}/{@code items} collections.
 *
 * <p><b>Idempotent.</b> It runs on {@link ApplicationReadyEvent} and is a no-op if the
 * {@code categories} collection already holds documents, so restarts against a persistent volume
 * (docker-compose {@code petstore-mongo-data}) don't duplicate or overwrite edited data — the
 * mongo-express UI edits survive a restart.
 *
 * <p>The values mirror {@code resources/data.sql} exactly (same ids, prices, translations). The
 * per-locale {@code productName} on each item is denormalized from its product (see
 * {@link ItemDocument}) so keyword search stays single-collection.
 */
@Component
@Profile("mongo")
class MongoCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(MongoCatalogSeeder.class);

    private static final String EN = "en_US";
    private static final String JA = "ja_JP";
    private static final String ZH = "zh_CN";

    private final MongoTemplate mongo;

    MongoCatalogSeeder(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seedIfEmpty() {
        long existing = mongo.getCollection(MongoSchema.CATEGORIES).countDocuments();
        if (existing > 0) {
            log.info("Mongo catalog already seeded ({} categories) — skipping seed", existing);
            return;
        }
        log.info("Seeding Mongo catalog (categories/products/items, 3 locales)");
        seedCategories();
        seedProducts();
        seedItems();
        log.info("Mongo catalog seed complete: {} categories, {} products, {} items",
                mongo.getCollection(MongoSchema.CATEGORIES).countDocuments(),
                mongo.getCollection(MongoSchema.PRODUCTS).countDocuments(),
                mongo.getCollection(MongoSchema.ITEMS).countDocuments());
    }

    // ── categories ───────────────────────────────────────────────────────────────────

    private void seedCategories() {
        category("FISH",
                text("Fish", "Aquatic creatures", "/images/fish_icon.gif"),
                text("魚", "水生生物", "/images/fish_icon.gif"),
                text("鱼", "水生动物", "/images/fish_icon.gif"));
        category("DOGS",
                text("Dogs", "Loyal companions", "/images/dogs_icon.gif"),
                text("犬", "忠実な仲間", "/images/dogs_icon.gif"),
                text("狗", "忠诚的伙伴", "/images/dogs_icon.gif"));
        category("CATS",
                text("Cats", "Independent felines", "/images/cats_icon.gif"),
                text("猫", "自立した猫", "/images/cats_icon.gif"),
                text("猫", "独立的猫", "/images/cats_icon.gif"));
        category("BIRDS",
                text("Birds", "Feathered friends", "/images/birds_icon.gif"),
                text("鳥", "羽のある友達", "/images/birds_icon.gif"),
                text("鸟", "有羽毛的朋友", "/images/birds_icon.gif"));
    }

    private void category(String catId, CategoryDocument.LocalizedText en,
                          CategoryDocument.LocalizedText ja, CategoryDocument.LocalizedText zh) {
        CategoryDocument d = new CategoryDocument();
        d.catId = catId;
        d.details = locales(en, ja, zh);
        mongo.save(d);
    }

    // ── products ─────────────────────────────────────────────────────────────────────

    private void seedProducts() {
        product("FI-SW-01", "FISH",
                text("Angelfish", "Saltwater fish from Australia", "/images/fish1.gif"),
                text("エンゼルフィッシュ", "オーストラリア産の海水魚", "/images/fish1.gif"),
                text("神仙鱼", "来自澳大利亚的海水鱼", "/images/fish1.gif"));
        product("FI-FW-01", "FISH",
                text("Koi", "Freshwater fish from Japan", "/images/fish2.gif"),
                text("鯉", "日本産の淡水魚", "/images/fish2.gif"),
                text("锦鲤", "来自日本的淡水鱼", "/images/fish2.gif"));
        product("K9-BD-01", "DOGS",
                text("Bulldog", "Friendly dog from England", "/images/dog1.gif"),
                text("ブルドッグ", "イングランド産の親しみやすい犬", "/images/dog1.gif"),
                text("斗牛犬", "来自英格兰的友好犬", "/images/dog1.gif"));
        product("K9-PO-02", "DOGS",
                text("Poodle", "Cuddly poodle", "/images/dog2.gif"),
                text("プードル", "愛らしいプードル", "/images/dog2.gif"),
                text("贵宾犬", "可爱的贵宾犬", "/images/dog2.gif"));
        product("FL-DSH-01", "CATS",
                text("Manx", "Great for a apartment", "/images/cat1.gif"),
                text("マンクス", "アパートに最適", "/images/cat1.gif"),
                text("曼岛猫", "非常适合公寓", "/images/cat1.gif"));
        product("AV-CB-01", "BIRDS",
                text("Amazon Parrot", "Great companion for years", "/images/bird1.gif"),
                text("アマゾンオウム", "長年の良き伴侶", "/images/bird1.gif"),
                text("亚马逊鹦鹉", "多年的好伴侣", "/images/bird1.gif"));
    }

    private void product(String productId, String catId, CategoryDocument.LocalizedText en,
                         CategoryDocument.LocalizedText ja, CategoryDocument.LocalizedText zh) {
        ProductDocument d = new ProductDocument();
        d.productId = productId;
        d.catId = catId;
        d.details = locales(en, ja, zh);
        mongo.save(d);
    }

    // ── items ────────────────────────────────────────────────────────────────────────

    private void seedItems() {
        item("EST-1", "FI-SW-01", "FISH", 16.50, 10.00, "/images/fish1.gif",
                itemText("Large Angelfish", "Angelfish", "Large"),
                itemText("大きいエンゼルフィッシュ", "エンゼルフィッシュ", "大"),
                itemText("大神仙鱼", "神仙鱼", "大"));
        item("EST-2", "FI-SW-01", "FISH", 16.50, 10.00, "/images/fish1.gif",
                itemText("Small Angelfish", "Angelfish", "Small"),
                itemText("小さいエンゼルフィッシュ", "エンゼルフィッシュ", "小"),
                itemText("小神仙鱼", "神仙鱼", "小"));
        item("EST-5", "K9-BD-01", "DOGS", 18.50, 12.00, "/images/dog1.gif",
                itemText("Female Puppy Bulldog", "Bulldog", "Female Puppy"),
                itemText("メスの子犬ブルドッグ", "ブルドッグ", "メスの子犬"),
                itemText("雌性幼犬斗牛犬", "斗牛犬", "雌性幼犬"));
        item("EST-10", "FL-DSH-01", "CATS", 58.50, 12.00, "/images/cat1.gif",
                itemText("Tailless Manx", "Manx", "Tailless"),
                itemText("尾のないマンクス", "マンクス", "尾なし"),
                itemText("无尾曼岛猫", "曼岛猫", "无尾"));
        item("EST-18", "AV-CB-01", "BIRDS", 193.50, 92.00, "/images/bird1.gif",
                itemText("Adult Male Amazon Parrot", "Amazon Parrot", "Adult Male"),
                itemText("成体オスのアマゾンオウム", "アマゾンオウム", "成体オス"),
                itemText("成年雄性亚马逊鹦鹉", "亚马逊鹦鹉", "成年雄性"));
    }

    private void item(String itemId, String productId, String categoryId,
                      double listPrice, double unitCost, String image,
                      ItemDocument.LocalizedItem en, ItemDocument.LocalizedItem ja,
                      ItemDocument.LocalizedItem zh) {
        // Apply the shared price/image to each locale detail (data.sql keeps them locale-invariant).
        for (ItemDocument.LocalizedItem li : List.of(en, ja, zh)) {
            li.listPrice = listPrice;
            li.unitCost = unitCost;
            li.image = image;
        }
        ItemDocument d = new ItemDocument();
        d.itemId = itemId;
        d.productId = productId;
        d.categoryId = categoryId;
        Map<String, ItemDocument.LocalizedItem> m = new LinkedHashMap<>();
        m.put(EN, en);
        m.put(JA, ja);
        m.put(ZH, zh);
        d.details = m;
        mongo.save(d);
    }

    // ── builders ─────────────────────────────────────────────────────────────────────

    private static CategoryDocument.LocalizedText text(String name, String descn, String image) {
        CategoryDocument.LocalizedText t = new CategoryDocument.LocalizedText();
        t.name = name;
        t.descn = descn;
        t.image = image;
        return t;
    }

    private static ItemDocument.LocalizedItem itemText(String descn, String productName, String attr1) {
        ItemDocument.LocalizedItem li = new ItemDocument.LocalizedItem();
        li.descn = descn;
        li.productName = productName;
        li.attr1 = attr1;
        return li;
    }

    private static Map<String, CategoryDocument.LocalizedText> locales(
            CategoryDocument.LocalizedText en, CategoryDocument.LocalizedText ja,
            CategoryDocument.LocalizedText zh) {
        Map<String, CategoryDocument.LocalizedText> m = new LinkedHashMap<>();
        m.put(EN, en);
        m.put(JA, ja);
        m.put(ZH, zh);
        return m;
    }
}
