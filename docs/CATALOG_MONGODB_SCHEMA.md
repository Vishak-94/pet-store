# Catalog MongoDB Schema — category / product / item (as-built)

> The catalog-service read model is available on MongoDB as a **profile-selectable** alternative to
> the locale-split H2 schema. This documents the **document schema, every attribute, and indexes** as
> actually implemented in `com.petstore.catalog.repository.mongo`: `CategoryDocument`,
> `ProductDocument`, `ItemDocument` (each with an embedded per-locale `details` map),
> `MongoCatalogRepository` (the `CatalogRepository` port adapter), and `MongoCatalogSeeder`
> (the `data.sql` equivalent).
>
> **How to select it:** run the service with the `mongo` Spring profile
> (`SPRING_PROFILES_ACTIVE=mongo`). The **default** profile (no profile) keeps H2 + JPA + `data.sql`
> unchanged; both sit behind the same `CatalogRepository` port, and exactly one adapter is active per
> profile (the JPA adapter is `@Profile("!mongo")`, the Mongo adapter `@Profile("mongo")`). The domain,
> `CatalogService`, `CatalogApiController`, and the client SDK are **untouched** — the swap is invisible
> above the port. See `../DECISIONS.md` (ADR "Catalog Mongo adapter — data model").
>
> Runtime: MongoDB 7.0. Catalog is **read-only**, so a plain standalone `mongod` suffices — **no
> replica set needed** (unlike OPC, which needs `rs0` for multi-document transactions).
> Local (docker-compose `mongo` service): `mongodb://localhost:27018/petstore?directConnection=true`
> (host port 27018; browsable via `mongo-express` on :8971). Database name: **`petstore`**.

---

## Why the SQL model collapses into documents

The legacy SQL store splits every entity into a **base** table (identity + structural FK,
locale-independent) and a **`_details`** table keyed by `(id, locale)` holding localized text/pricing.
A single localized read is a join `base ⋈ details WHERE locale = ?`. In MongoDB each entity becomes
**one document** whose `details` field is a **map keyed by locale** (`en_US` / `ja_JP` / `zh_CN`), so a
locale read is `details.get("en_US")` — no join.

This is **Data Model Option C** (see `../DECISIONS.md`): three sibling collections, each entity
independently editable, every read single-collection. Chosen over a 1:1 table port (keeps join pain) and
over one giant nested category→product→item aggregate (unbounded growth, whole-tree rewrites on any edit).

Three collections in total:

| Collection | Replaces (SQL) | Purpose |
|---|---|---|
| `categories` | `category` + `category_details` | top-level catalog categories |
| `products` | `product` + `product_details` | products, each owned by a category (`catId`) |
| `items` | `item` + `item_details` | sellable items, each owned by a product (`productId`) |

### Two denormalizations onto the item (the key Option-C moves)

Legacy resolved two things via joins that MongoDB copies onto the `items` document so every item read
and search stays **single-collection** (no `$lookup`):

1. **`categoryId`** — legacy resolved an item's category via `item→product→catid` (parity item **M2**).
   Copied onto the item so `getItem` fills `Item.category` with no join.
2. **per-locale `productName`** — legacy keyword search matched over the *product* name (parity item
   **H6**). Copied into each locale's item detail so search is a single scan of `items`, not a
   `$lookup` into `products`.

Both source fields (a product's category, a product's name) are low-churn, so keeping the copy correct
is cheap. This is a deliberate denormalization, recorded in the ADR.

---

## 1. `categories` collection

One document = one category across all locales. `catId` becomes `_id` (natural key; free unique index).

```jsonc
{
  "_id": "FISH",                          // catid
  "details": {
    "en_US": { "name": "Fish", "descn": "Aquatic creatures", "image": "/images/fish_icon.gif" },
    "ja_JP": { "name": "魚",   "descn": "水生生物",           "image": "/images/fish_icon.gif" },
    "zh_CN": { "name": "鱼",   "descn": "水生动物",           "image": "/images/fish_icon.gif" }
  }
}
```

| Field | BSON type | Source (SQL) | Nullable | Detail |
|---|---|---|---|---|
| `_id` | String | `category.catid` (`@Id`) | no | The catid, e.g. `FISH`. Primary key ⇒ unique index automatic. |
| `details` | Object (map) | `category_details` rows | no | locale key → localized text. Key format matches the legacy locale column (`en_US`, `ja_JP`, `zh_CN`). |
| `details.<locale>.name` | String | `category_details.name` | no | Localized category name. `getCategories` orders by this. |
| `details.<locale>.descn` | String | `category_details.descn` | no | Localized description. |
| `details.<locale>.image` | String | `category_details.image` | no | Icon path (locale-invariant in the seed, but stored per-locale to stay faithful to the table shape). |

Mapped by `CategoryDocument` + nested `LocalizedText`.

---

## 2. `products` collection

One document = one product. `productId` becomes `_id`; `catId` is the product→category membership FK,
kept as a **plain indexed field** so `getProducts(categoryId)` is a single `find({ catId: ... })`.

```jsonc
{
  "_id": "FI-SW-01",                      // productid
  "catId": "FISH",                        // product.catid (indexed)
  "details": {
    "en_US": { "name": "Angelfish",        "descn": "Saltwater fish from Australia", "image": "/images/fish1.gif" },
    "ja_JP": { "name": "エンゼルフィッシュ", "descn": "オーストラリア産の海水魚",        "image": "/images/fish1.gif" },
    "zh_CN": { "name": "神仙鱼",           "descn": "来自澳大利亚的海水鱼",          "image": "/images/fish1.gif" }
  }
}
```

| Field | BSON type | Source (SQL) | Nullable | Detail |
|---|---|---|---|---|
| `_id` | String | `product.productid` (`@Id`) | no | The productid, e.g. `FI-SW-01`. |
| `catId` | String | `product.catid` | no | Owning category id. **Indexed** (`@Indexed`) — backs the by-category listing. |
| `details` | Object (map) | `product_details` rows | no | locale key → localized text (same `LocalizedText` shape as category). |
| `details.<locale>.name` | String | `product_details.name` | no | Localized product name. `getProducts` orders by this; also **denormalized onto items** for search. |
| `details.<locale>.descn` | String | `product_details.descn` | no | Localized description. |
| `details.<locale>.image` | String | `product_details.image` | no | Product image path. |

Mapped by `ProductDocument` (reuses `CategoryDocument.LocalizedText`).

---

## 3. `items` collection

One document = one sellable item. `itemId` becomes `_id`. Carries the two denormalizations
(`categoryId`, per-locale `productName`) plus the FK to its product.

```jsonc
{
  "_id": "EST-1",                         // itemid
  "productId": "FI-SW-01",                // item.productid (indexed)
  "categoryId": "FISH",                   // denormalized (legacy: product.catid) — parity M2
  "details": {
    "en_US": {
      "descn": "Large Angelfish", "productName": "Angelfish",   // productName denormalized — parity H6
      "listPrice": 16.50, "unitCost": 10.00, "image": "/images/fish1.gif",
      "attr1": "Large", "attr2": null, "attr3": null, "attr4": null, "attr5": null
    },
    "ja_JP": { "descn": "大きいエンゼルフィッシュ", "productName": "エンゼルフィッシュ", "listPrice": 16.50, "unitCost": 10.00, "image": "/images/fish1.gif", "attr1": "大", ... },
    "zh_CN": { "descn": "大神仙鱼",                "productName": "神仙鱼",            "listPrice": 16.50, "unitCost": 10.00, "image": "/images/fish1.gif", "attr1": "大", ... }
  }
}
```

### 3.1 Top-level fields

| Field | BSON type | Source (SQL) | Nullable | Detail |
|---|---|---|---|---|
| `_id` | String | `item.itemid` (`@Id`) | no | The itemid, e.g. `EST-1`. `getItems` / `searchItems` order by this. |
| `productId` | String | `item.productid` | no | Owning product id. **Indexed** (`@Indexed`) — backs `getItems(productId)`. |
| `categoryId` | String | *derived* `product.catid` | no | **Denormalized** owning category (legacy resolved via `item→product→catid`). Fills `Item.category` with no join (parity M2); also searched as the "category catid" field (H6). |
| `details` | Object (map) | `item_details` rows | no | locale key → localized pricing/description/attributes (+ denormalized `productName`). |

### 3.2 `details.<locale>` embedded item detail

| Field | BSON type | Source (SQL) | Nullable | Detail |
|---|---|---|---|---|
| `descn` | String | `item_details.descn` | no | Localized item description. **Searched** by keyword (H6). |
| `productName` | String | *derived* `product_details.name` | no | **Denormalized** per-locale product name so search is single-collection (H6). Product names are low-churn. |
| `listPrice` | Double | `item_details.listprice` | no | List price. Stored as `double` (the domain `Item` exposes double; money-as-BigDecimal is deferred repo-wide). Locale-invariant in the seed. |
| `unitCost` | Double | `item_details.unitcost` | no | Unit cost. Same double note. |
| `image` | String | `item_details.image` | no | Item image path. |
| `attr1`..`attr5` | String | `item_details.attr1..attr5` | yes | Product attributes (e.g. `attr1 = "Large"`). **NEVER searched** (parity H6) — only `descn`, denormalized `productName`, and `categoryId` are. `attr1` surfaces as `Item.getAttribute()`. |

Mapped by `ItemDocument` + nested `LocalizedItem`.

---

## 4. Indexes

| Collection | Index | Backs |
|---|---|---|
| `categories` | `_id` (automatic) | `getCategory(catId)` key lookup |
| `products` | `_id` (automatic) | `getProduct(productId)` key lookup |
| `products` | `{ catId: 1 }` (`@Indexed`) | `getProducts(categoryId)` by-category listing |
| `items` | `_id` (automatic) | `getItem(itemId)` key lookup; also the sort key for `getItems`/`searchItems` |
| `items` | `{ productId: 1 }` (`@Indexed`) | `getItems(productId)` by-product listing |

**No search index** — the keyword search uses a leading-wildcard, case-insensitive `$regex`
(`.*token.*`, `"i"` flag), which a Mongo B-tree index cannot accelerate (same reason a `LIKE '%token%'`
defeats an SQL B-tree). A Mongo `$text` index would help but changes substring→tokenized semantics and
diverges from parity item H6. See `../DECISIONS.md` ("Search query & why an index won't speed it up").

---

## 5. How each port method maps (`MongoCatalogRepository`)

The locale key is `locale.toString()` (e.g. `en_US`), default `en_US` when null (`lang(Locale)`),
matching the legacy column key. `detailsPath(locale)` = `"details." + locale`.

| Port method | Mongo query |
|---|---|
| `getCategory(id, locale)` | `findById(id)` → `details.get(locale)` → `Optional`/empty |
| `getCategories(start, count, locale)` | `find({ "details.<locale>": {$exists:true} })` sort `details.<locale>.name` asc, `skip/limit(count+1)` |
| `getProduct(id, locale)` | `findById(id)` → `details.get(locale)` |
| `getProducts(catId, start, count, locale)` | `find({ catId, "details.<locale>": {$exists:true} })` sort by localized name, `count+1` |
| `getItem(id, locale)` | `findById(id)` filtered to locales present → `toItem` (category from denormalized `categoryId`) |
| `getItems(productId, start, size, locale)` | `find({ productId, "details.<locale>": {$exists:true} })` sort `_id` asc, `size+1` |
| `searchItems(keyword, start, size, locale)` | per token: `$or` of `$regex` on `details.<locale>.productName`, `categoryId`, `details.<locale>.descn` (`"i"`, `Pattern.quote`); tokens `$or`-joined; sort `_id`, `size+1` |

**Pagination (parity L5):** every paged method fetches `count + 1` documents and sets
`hasNext = rows.size() > count`, then trims to `count` — mirrors the JPA `Slice`, so a full final page
never over-reports a phantom next page.

**Ordering (parity M1):** categories/products by localized `name`; items/search by `_id` (= itemid).

**Miss ≠ error (parity):** single lookups → `Optional.empty()` (→ 404); page misses → `Page.EMPTY_PAGE`
(→ 200 empty list). Blank/whitespace search → `EMPTY_PAGE`.

---

## 6. Seeding (`MongoCatalogSeeder`)

MongoDB has no `spring.sql.init` / `data.sql` hook, so this `@Profile("mongo")` component loads the same
seed on `ApplicationReadyEvent`: **4 categories, 6 products, 5 items**, each in all three locales
(`en_US`/`ja_JP`/`zh_CN`), with values mirroring `resources/data.sql` exactly (ids, translations, and
prices — EST-1/EST-2 = 16.50/10.00, EST-5 = 18.50/12.00, EST-10 = 58.50/12.00, EST-18 = 193.50/92.00).
Each item's per-locale `productName` is denormalized from its product.

**Idempotent:** no-op if the `categories` collection already holds documents, so restarts against the
persistent volume (docker-compose `petstore-mongo-data`) don't duplicate or overwrite edits — mongo-express
UI edits survive a restart.

---

## 7. Parity checklist (verified after the move)

Boxes marked ☑ are pinned by a passing Mongo integration test (`MongoCatalogRepositoryTest`, 16 cases, run
against a Testcontainers `mongo:7.0`); the same suite skips cleanly via `assumeTrue(dockerAvailable)` when
Docker is absent (the JPA `CatalogCharacterizationTest` still covers the default profile).

- [x] **Locale-split reads** — every lookup reads `details.<locale>`; ja_JP/zh_CN return localized text.
      *(`reads_areLocaleSpecific`.)*
- [x] **Miss ≠ error** — unknown id → `Optional.empty()`; unknown category/blank search → `EMPTY_PAGE`.
      *(`category_unknownId_returnsEmpty`, `products_unknownCategory_returnsEmptyPage`, `search_blankQuery_returnsEmptyPage`.)*
- [x] **Ordering by name (M1)** — categories alphabetical by localized name. *(`categories_orderedByLocalizedName`.)*
- [x] **Items/search ordered by itemid.** *(`items_orderedByItemId`.)*
- [x] **Pagination hasNext precise (L5)** — full final page reports no phantom next page. *(`pagination_hasNext_isPrecise`.)*
- [x] **`getItem.category` (M2)** — resolved from the denormalized `categoryId`, not null. *(`getItem_category_resolvedFromDenormalizedCategoryId`.)*
- [x] **Search over productName + categoryId + descn (H6)**, case-insensitive, tokens OR-joined; catid match works. *(`search_matchesDescription`, `search_matchesCategoryId_caseInsensitive`.)*
- [x] **Attributes NOT searched (H6)** — a token present only in an attribute misses. *(`search_doesNotMatchAttributes`.)*

Also **live-smoke verified** on `SPRING_PROFILES_ACTIVE=mongo` against the docker-compose `mongo` service:
category/product/item reads in all three locales, `getItem.category` = `FISH`, name ordering, search
(`Angelfish` → EST-1/EST-2; `fish` catid match; blank → empty; `count=1` → `hasNext=true`), and
mongo-express reachable on :8971.
