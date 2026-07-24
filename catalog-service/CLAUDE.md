# catalog-service — Claude guide

Catalog / browse microservice for the migrated Java Pet Store. Owns the catalog
bounded context (category → product → item) and exposes a **read-only** JSON API
on **port 8083**. Package root: `com.petstore.catalog`. It replaces the legacy
in-process `CatalogEJB` / `CatalogDAO`; there are no writes, no JMS, and no auth
enforcement here — it is a pure read model.

Start with the shared repo skill (`../.claude/skills/petstore-dev/SKILL.md`)
for cross-module conventions (hexagonal layering, build/run, the parity rule).
This file is catalog-specific.

## Two-module layout (parent aggregator)

`catalog-service/pom.xml` is a `pom`-packaging aggregator over two children,
single-versioned at `1.0.0`:

| Module | Artifact | Role |
|--------|----------|------|
| `client/` | `catalog-service-client` (jar) | Importable SDK: endpoint constants + DTOs + `RestClient` wrapper. Thin — only `spring-web` + `spring-context`. Built **first**. |
| `app/` | `catalog-service` (Spring Boot) | The service. Depends on `catalog-service-client` so server and clients share one contract (server cannot drift from what callers see). |

The app uses non-standard source roots (`src`, `resources`, `test`, wired in
`app/pom.xml`), not the Maven default `src/main/java`. The client uses the
standard `src/main/java` layout.

## Package layout (app)

```
com.petstore.catalog
├── CatalogServiceApplication      # @SpringBootApplication entry point
├── domain/                        # framework-free value objects (no JPA/Jackson)
│   ├── Category, Product, Item    # plain immutable classes (legacy accessor quirks preserved)
│   └── Page                       # pagination VO; Page.EMPTY_PAGE canonical empty result
├── repository/
│   ├── CatalogRepository          # the PORT (interface) — 7 read methods
│   └── jpa/                       # the ADAPTER
│       ├── JpaCatalogRepository            # implements the port; entities → domain
│       ├── SpringDataCatalogRepositories   # Spring Data interfaces + ItemSearchRepository{,Impl}
│       ├── CategoryDetailEntity            # locale-split entities (@IdClass = (id, locale))
│       ├── ProductBaseEntity / ProductDetailEntity
│       └── ItemBaseEntity / ItemDetailEntity
├── service/CatalogService         # thin pass-through over the port (replaces CatalogEJB)
└── web/CatalogApiController       # JSON API; maps CatalogServiceEndpoints constants
```

Domain never leaks entities; DTOs (in the client) are the wire contract.
`CatalogService` is deliberately a pass-through — no business logic lives there.

## Build & test (this module only)

Java 21 required (`export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`).

- Build + install both children (client is a dependency, so **install**, not just package):
  `cd catalog-service && mvn -q clean install`
- Client only: `cd catalog-service/client && mvn -q clean install`
- App only (client already installed): `cd catalog-service/app && mvn -q clean package`
- Run: `cd catalog-service/app && mvn spring-boot:run` (H2 in-memory, seeded from
  `resources/schema.sql` + `resources/data.sql`; H2 console enabled).
- Tests (`app/test`): `CatalogCharacterizationTest` (pins legacy `CatalogService`
  behaviour) and `CatalogApiTest` (pins the REST/JSON contract). Both are
  characterization/parity tests — **do not weaken or disable them to go green.**

## Data model — locale-split tables

Every catalog entity is split into a **base** table (identity + structural FKs,
locale-independent) and a **`_details`** table keyed by `(id, locale)` holding
localized text/pricing. Seed data ships `en_US`, `ja_JP`, `zh_CN`.

- `category` (catid) / `category_details` (catid, locale → name, descn, image)
- `product` (productid, **catid**) / `product_details` (productid, locale → name, descn, image)
- `item` (itemid, **productid**) / `item_details` (itemid, locale → listprice, unitcost, descn, attr1..attr5, image)

Category membership of a product lives on `product.catid`; product membership of
an item lives on `item.productid`. Localized rows never carry membership.

## Invariants (do not break without a parity decision)

1. **Locale-split reads.** Every lookup takes a `Locale` and reads the matching
   `_details` row. `JpaCatalogRepository.lang(locale)` formats it as the legacy
   `en_US` key (`locale.toString()`, default `en_US`).
2. **Miss ≠ error.** Single-entity misses → `Optional.empty()` → HTTP 404 (client
   maps 404 back to `Optional.empty()`). Page misses → `Page.EMPTY_PAGE` → HTTP
   200 with an empty list. Never null, never a thrown error.
3. **Search semantics (H6 — legacy-faithful).** `searchItems` whitespace-tokenizes
   the query; for **each token** it ORs a case-insensitive `LIKE %token%` across
   **product name + category catid + item descn**; tokens are combined with OR.
   **Attributes (attr1..attr5) are NOT searched.** Blank / whitespace-only query →
   `EMPTY_PAGE`. The token count is dynamic, so the JPQL is assembled at runtime in
   `ItemSearchRepositoryImpl`.
4. **Ordering by name (M1).** `getCategories` and `getProducts` order by localized
   `name` (`findByLocaleOrderByName`, `findByCategory ... order by pd.name`).
   `getItems`/`searchItems` order by `itemid`. Ordering affects which rows land on
   which page — changing it is a parity change.
5. **Pagination via `Slice.hasNext()` (L5).** `getProducts`/`getItems` use a
   `Slice` (Spring fetches count+1) so the final full page does not over-report a
   phantom next page. `searchItems` mirrors this manually by fetching `size + 1`
   rows. `getCategories` uses a `countByLocale` total instead.
6. **`getItem.category` from product catid (M2).** `getItem` resolves the item's
   `category` from `item.productid → product.catid`. It is **not** hardcoded null.
   (Note: on **page/search** item results the `category` field is left as resolved
   too via `toItem`; the client `ItemDto` javadoc noting "null on lookups" reflects
   the older behaviour — trust the code path in `JpaCatalogRepository.toItem`.)

## The client contract (`catalog-service-client`)

- `CatalogServiceEndpoints` — path constants (single-sourced; the server `@GetMapping`s
  reference these same constants). `DEFAULT_BASE_URL = http://localhost:8083`.
- `CatalogDtos` — `CategoryDto`, `ProductDto`, `ItemDto`, and per-type page records
  `CategoryPage` / `ProductPage` / `ItemPage` (`list`, `start`, `nextPageAvailable`).
  Concrete page records (not a generic `PageDto<T>`) so Jackson resolves the element
  type without a `ParameterizedTypeReference`.
- `CatalogServiceClient` — `RestClient` wrapper. `getX` single lookups catch
  `HttpClientErrorException.NotFound` → `Optional.empty()`; page methods coalesce a
  null body to an empty page. Note the **query param is `count`** on the wire even
  where the client method arg is named `size`, and search uses `keyword`.
- **Keep DTOs and public method signatures backward compatible.** Adding a field is
  usually safe (records map by component name); removing/renaming is a breaking change.

## When editing

- Changing search fields/tokenization, ordering, or pagination = behavioural parity
  change → check `docs/PARITY_AUDIT.md` (H6, M1, M2, L4, L5) and `../DECISIONS.md`
  **before** editing, and update the characterization tests deliberately.
- Locale param handling lives only in `CatalogApiController.locale(String)`:
  `"default"` → `Locale.getDefault()`, then 1/2/3-part `language_country_variant`
  split on `_`, blank/null → `Locale.US` (L4).

## See also

- `docs/LLD.md` — class + sequence diagrams, endpoint table, design decisions.
- `.claude/skills/catalog-service/SKILL.md` — scoped how-to skill.
- `../.claude/skills/petstore-dev/SKILL.md` — shared repo conventions.
- `../DECISIONS.md`, `../docs/PARITY_AUDIT.md` — rationale + parity baseline.
