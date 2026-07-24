---
name: catalog-service
description: Conventions and how-to for the Pet Store catalog-service (port 8083, package com.petstore.catalog) — the read-only catalog/browse microservice. Use when working on category/product/item browse, keyword search over items, pagination, locale-split tables and the lang param, or the catalog-service-client SDK. Trigger terms: catalog, category, product, item, browse, searchItems / keyword search, tokenized search, locale / lang / i18n / en_US / ja_JP / zh_CN, locale-split _details tables, Slice.hasNext pagination, CatalogRepository, JpaCatalogRepository, CatalogApiController, CatalogServiceClient, CatalogDtos, port 8083.
---

# catalog-service — scoped skill

Read-only catalog microservice migrated from the legacy `CatalogEJB`/`CatalogDAO`.
Port **8083**, package `com.petstore.catalog`, two Maven modules (`client` built
first, then `app`). No writes, no JMS, no auth here — a pure read model. Start
from the shared repo skill `petstore-dev`
(`../../../../.claude/skills/petstore-dev/SKILL.md`) for build/run and the parity
rule; this skill is catalog-specific.

Full detail:
- Design + diagrams: `../../../docs/LLD.md`
- Future-Claude guide + invariants: `../../../CLAUDE.md`

## Layering (hexagonal — keep it)

`web (CatalogApiController) → service (CatalogService) → port (CatalogRepository)
→ adapter (JpaCatalogRepository + Spring Data + entities)`.

- **Domain** (`domain/`): `Category`, `Product`, `Item`, `Page` — framework-free
  value objects (no JPA/Jackson). Preserve legacy accessor quirks (`Item.getAttribute()`
  = attr1, `getListCost()` = listPrice). Don't add annotations here.
- **Port** (`repository/CatalogRepository`): the only seam the service knows.
  `CatalogService` is a pure pass-through — put no business logic in it.
- **Adapter** (`repository/jpa/`): `JpaCatalogRepository` maps entities → domain;
  Spring Data interfaces + the search fragment live in `SpringDataCatalogRepositories`.
  Entities are package-private and never leak.
- **Web** (`web/CatalogApiController`): maps domain → `CatalogDtos`, uses
  `CatalogServiceEndpoints` path constants. 404 on single-entity miss, 200-empty
  page otherwise.

## Locale-split tables — how they work

Each entity = a locale-independent **base** table (identity + FK membership) + a
`(id, locale)`-keyed **`_details`** table (localized `name`/`descn`/`image`, and
for items `listprice`/`unitcost`/`attr1..5`). Schema in `app/resources/schema.sql`,
seed (`en_US`, `ja_JP`, `zh_CN`) in `app/resources/data.sql`.

- Membership is on base tables: `product.catid`, `item.productid`. Localized rows
  never carry membership.
- The adapter formats the `Locale` into the `en_US`-style column value via
  `JpaCatalogRepository.lang(locale)` (`locale.toString()`, default `en_US`).
- The web `lang` param is parsed once in `CatalogApiController.locale(String)`:
  blank/null → `Locale.US`; `"default"` → `Locale.getDefault()`; else 1/2/3-part
  `language_country_variant` split on `_`.
- Adding a locale = add `_details` rows (idempotent H2 `MERGE`) in `data.sql`. No
  code change needed.

## Changing search or ordering safely (PARITY constraint)

Search and ordering are **behavioural parity** items (`../../../../docs/PARITY_AUDIT.md`
H6, M1, M2, L4, L5) — read that + `../../../../DECISIONS.md` before touching them,
and update the characterization tests deliberately (never weaken/disable to go green).

- **Search (`ItemSearchRepositoryImpl.search`):** whitespace-tokenize the query;
  for each token OR a case-insensitive `LIKE %token%` across **product name +
  category catid + item descn**; tokens OR together. **Attributes attr1..5 are NOT
  searched** — do not add them. Blank/whitespace query → `Page.EMPTY_PAGE`. Token
  count is dynamic → JPQL is assembled at runtime (not a derived query).
- **Ordering:** `getCategories`/`getProducts` order by localized `name`;
  `getItems`/`searchItems` order by `itemid`. Ordering changes which rows land on
  which page — treat as a parity change.
- **`getItem.category`:** resolved from `item.productid → product.catid` in
  `toItem`; must not be hardcoded null.

## Pagination via Slice

`getProducts`/`getItems` return a Spring Data `Slice` (fetches count+1) and set
`Page.hasNextPage = slice.hasNext()`. `searchItems` mirrors this manually: fetch
`size + 1` rows, `hasNext = rows.size() > size`, then `limit(size)`.
`getCategories` instead uses `countByLocale` and `start + size < total`. This
avoids the legacy phantom empty final page (L5). `Page.EMPTY_PAGE` is the
canonical empty result for guard failures (`count<=0`, `start<0`).

## Client / DTO contract (`catalog-service-client`)

Thin `RestClient` SDK. `app` depends on it, so server + clients share one contract.

- `CatalogServiceEndpoints`: path constants + `DEFAULT_BASE_URL=http://localhost:8083`.
  Server `@GetMapping`s reference these — never hardcode a divergent path.
- `CatalogDtos`: `CategoryDto`/`ProductDto`/`ItemDto` + concrete page records
  `CategoryPage`/`ProductPage`/`ItemPage` (`list`, `start`, `nextPageAvailable`).
  Records map by component name — adding a field is usually safe; renaming/removing
  is breaking. Keep public method signatures stable.
- `CatalogServiceClient`: single lookups catch `HttpClientErrorException.NotFound`
  → `Optional.empty()`; page methods coalesce a null body to an empty page. On the
  wire the page-size param is **`count`** (client arg may be named `size`), search
  uses **`keyword`**, locale is **`lang`**.

## Build & test

Java 21. `cd catalog-service && mvn -q clean install` (client is a dependency, so
install). App-only after that: `cd catalog-service/app && mvn -q clean package`;
run with `mvn spring-boot:run`. Tests in `app/test`:
`CatalogCharacterizationTest` (service contract) + `CatalogApiTest` (REST/JSON) —
both are parity tests, keep them green honestly.
