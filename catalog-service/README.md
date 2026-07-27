# catalog-service — read-only, multi-locale catalog browse API

> Part of the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration. See the [repo README](../README.md).

**Port:** `8083` · **Package:** `com.petstore.catalog` · **Legacy origin:** the in-process `CatalogEJB` / `CatalogDAO` (catalog component)

## What it does

Owns the catalog bounded context (**category → product → item**) and exposes it as a
**read-only** JSON API. It is a pure read model: no writes, no JMS, no auth enforcement.
Every read is **locale-aware** — seed data ships `en_US`, `ja_JP`, `zh_CN`, and each lookup
resolves the localized text/pricing for the requested locale.

It replaces the calls the monolith storefront used to make in-process against `CatalogService`
with an HTTP boundary. Behaviour is preserved verbatim from the legacy catalog (search
tokenization, name/id ordering, pagination, locale key parsing) and pinned by characterization
tests.

## Layout

Two-module Maven build under a `pom`-packaging aggregator (`catalog-service/pom.xml`),
single-versioned at `1.0.0`:

| Module | Artifact | Role |
|--------|----------|------|
| `client/` | `catalog-service-client` (jar) | Importable SDK — endpoint constants + DTOs + `RestClient` wrapper. Built **first**. See [client/README.md](client/README.md). |
| `app/` | `catalog-service` (Spring Boot) | The service. Depends on `catalog-service-client` so server and callers share one contract. |

The app uses non-standard source roots (`src`, `resources`, `test`, wired in `app/pom.xml`),
not the Maven default `src/main/java`.

```
com.petstore.catalog
├── CatalogServiceApplication      # @SpringBootApplication entry point
├── domain/                        # framework-free value objects (Category, Product, Item, Page)
├── repository/
│   ├── CatalogRepository          # the PORT — read methods
│   ├── jpa/                       # DEFAULT adapter — @Profile("!mongo"), file H2 + locale-split tables
│   └── mongo/                     # OPT-IN adapter — @Profile("mongo"), MongoTemplate + embedded locale map
├── service/CatalogService         # thin pass-through over the port (replaces CatalogEJB)
└── web/CatalogApiController       # JSON API; maps CatalogServiceEndpoints constants
```

Domain never leaks entities; the client DTOs are the wire contract.

## Build & run

Java 21 required: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.

- Build + install both children (client is a dependency, so **install**):
  `cd catalog-service && mvn -q clean install`
- App only (client already installed): `cd catalog-service/app && mvn -q clean package`
- Run (default = H2/JPA): `cd catalog-service/app && mvn spring-boot:run`
- Run on MongoDB: `SPRING_PROFILES_ACTIVE=mongo mvn spring-boot:run` (see [Data](#data)).

Tests (`app/test`): `CatalogCharacterizationTest` (pins legacy `CatalogService` behaviour),
`CatalogApiTest` (pins the REST/JSON contract), and `MongoCatalogRepositoryTest` (Mongo adapter
parity via Testcontainers `mongo:7.0`; skips when Docker is absent).

## API surface

All endpoints are `GET`. Paths are single-sourced in `CatalogServiceEndpoints` (the server maps
the same constants the client calls). Query params: `start`, `count`, `lang`, `keyword`.

| Method & path | Purpose | Miss behaviour |
|---|---|---|
| `GET /api/categories?start=&count=&lang=` | Page of top-level categories (ordered by name) | 200 + empty page |
| `GET /api/categories/{id}?lang=` | One category | 404 |
| `GET /api/categories/{id}/products?start=&count=&lang=` | Page of products in a category (ordered by name) | 200 + empty page |
| `GET /api/products/{id}?lang=` | One product | 404 |
| `GET /api/products/{id}/items?start=&count=&lang=` | Page of items in a product (ordered by itemId) | 200 + empty page |
| `GET /api/items/{id}?lang=` | One item (with `category` resolved from product catid) | 404 |
| `GET /api/items?keyword=&start=&count=&lang=` | Keyword item search | 200 + empty page |

Contract invariants (parity-critical — do not change without a parity decision):

- **Miss ≠ error.** Single-entity misses → 404 (client maps back to `Optional.empty()`). Page
  misses → 200 with an empty list. Never null, never a thrown error.
- **Search (legacy-faithful).** The query is whitespace-tokenized; each token is OR-matched
  (case-insensitive `LIKE %token%`) across **product name + category catid + item description**.
  Attributes (attr1..attr5) are **not** searched. Blank query → empty page.
- **Locale parsing.** `?lang=default` → JVM default locale; blank/null → `Locale.US`; otherwise a
  1/2/3-part `language_country_variant` split on `_`.

## Auth / security

**None enforced here.** The service is a pure read model with no `spring-boot-starter-security`
on the classpath and no security config — it is meant to sit behind the gateway/storefront and
serve public catalog data. It does not read or require JWT / XSRF cookies.

## Data

The `CatalogRepository` port has **two adapters**; exactly one is active per Spring profile.
`application.yml` carries two profile documents so the inactive store's autoconfig is excluded.

| Profile | Adapter | Store | Selected by |
|---|---|---|---|
| default (none / `h2`) | `jpa.JpaCatalogRepository` `@Profile("!mongo")` | file H2 + `data.sql` | nothing (default) |
| `mongo` | `mongo.MongoCatalogRepository` `@Profile("mongo")` | MongoDB 7.0 | `SPRING_PROFILES_ACTIVE=mongo` |

**H2 (default).** File-based H2 at `jdbc:h2:file:${CATALOG_DB_PATH:./data/catalog}` (survives
restarts; `AUTO_SERVER=TRUE` so the H2 console can attach). Seeded by `resources/schema.sql` +
idempotent `resources/data.sql`; H2 console enabled. Data is **locale-split**: every entity has a
locale-independent **base** table (identity + structural FKs) and a **`_details`** table keyed by
`(id, locale)` for localized text/pricing:

- `category` / `category_details`
- `product` (with `catid`) / `product_details`
- `item` (with `productid`) / `item_details`

**MongoDB (`@Profile("mongo")`, implemented stretch goal).** Swaps the read model to MongoDB
behind the same port — the only production-code change above `repository/mongo/` is the
`@Profile("!mongo")` on `JpaCatalogRepository`; domain, `CatalogService`, controller and client
SDK are untouched. Uses Option C: three collections (`categories` / `products` / `items`) with a
per-locale `details` map embedded on each document, and `categoryId` + per-locale `productName`
denormalized onto items so every read is a single-collection query (no `$lookup`). Connection:
`spring.data.mongodb.uri` = `${CATALOG_MONGODB_URI:mongodb://localhost:27018/petstore?directConnection=true}`
(host port 27018 matches the repo `docker-compose` `mongo` service; browse via mongo-express on
:8971). Seeded on first startup by `MongoCatalogSeeder` (idempotent). Catalog is read-only, so
unlike OPC no replica set / transactions are required.

To switch: bring the compose Mongo up (`docker compose up -d mongo mongo-express`) and run with
`SPRING_PROFILES_ACTIVE=mongo`. Full attribute-by-attribute schema: [../docs/CATALOG_MONGODB_SCHEMA.md](../docs/CATALOG_MONGODB_SCHEMA.md).

## See also

- [client/README.md](client/README.md) — the `catalog-service-client` SDK.
- [CLAUDE.md](CLAUDE.md) — invariants, parity notes, editing guidance.
- [docs/LLD.md](docs/LLD.md) — class + sequence diagrams, endpoint table, design decisions.
- [../docs/CATALOG_MONGODB_SCHEMA.md](../docs/CATALOG_MONGODB_SCHEMA.md) — MongoDB schema.
- `.claude/skills/catalog-service/SKILL.md` — scoped how-to skill.
- [../README.md](../README.md) — the repo overview.
