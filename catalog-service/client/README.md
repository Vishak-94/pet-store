# catalog-service-client — catalog-service client SDK

> Importable SDK jar for catalog-service. Part of the Pet Store migration ([repo README](../../README.md)).

## What it provides

The complete HTTP contract for the read-only catalog API in one thin, importable jar:
endpoint path constants, wire DTOs, and a `RestClient`-based operations wrapper. Consumers
(e.g. the monolith storefront) construct the client with a base URL and call methods — no URLs
or JSON shapes leak into caller code. The server (`catalog-service`) depends on this same jar and
maps the same endpoint constants, so server and clients provably cannot drift.

Behaviour mirrors the legacy catalog contract: a lookup that finds nothing returns
`Optional.empty()` (a 404 is mapped back, never thrown); a page that finds nothing returns an
empty-list page (never null); results are locale-specific (legacy locale keys like `en_US`).

Dependencies are minimal — only `spring-web` (for `RestClient`) and `spring-context`; no Boot
starter.

## Maven coordinates

```xml
<dependency>
    <groupId>com.petstore</groupId>
    <artifactId>catalog-service-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

Standard `src/main/java` layout; Java 21. Install locally with the parent build
(`cd catalog-service && mvn -q clean install`) or on its own (`cd catalog-service/client && mvn -q clean install`).

## Key classes

All in package `com.petstore.catalog.client`:

- **`CatalogServiceClient`** — the `RestClient` wrapper. Methods: `getCategory`, `getCategories`,
  `getProduct`, `getProducts`, `getItem`, `getItems`, `searchItems`. Single lookups catch
  `HttpClientErrorException.NotFound` → `Optional.empty()`; page methods coalesce a null body to
  an empty page. Bounded timeouts (2s connect / 5s read) so a hung catalog-service can't block
  caller threads. Base URL is a constructor arg; default is `http://localhost:8083`.
- **`CatalogServiceEndpoints`** — path constants (single-sourced with the server) and query-param
  names. `DEFAULT_BASE_URL = http://localhost:8083`. Note the wire param is `count` even where a
  client method arg is named `size`, and search uses `keyword`.
- **`CatalogDtos`** — `CategoryDto`, `ProductDto`, `ItemDto` (records), plus the concrete per-type
  page records `CategoryPage` / `ProductPage` / `ItemPage` (`list`, `start`, `nextPageAvailable`).
  Concrete page records (not a generic `PageDto<T>`) so Jackson resolves the element type without
  a `ParameterizedTypeReference`. Framework-free (no JPA/Jackson annotations).

Keep DTOs and public method signatures backward compatible — adding a field is usually safe
(records map by component name); removing/renaming is a breaking change.

## Usage

```java
CatalogServiceClient catalog = new CatalogServiceClient();            // default http://localhost:8083
// or: new CatalogServiceClient("http://catalog-service:8083");

// Single lookup — 404 maps to Optional.empty()
Optional<CategoryDto> fish = catalog.getCategory("FISH", "en_US");

// Page lookup — never null; empty page when nothing matches
CategoryPage cats = catalog.getCategories(0, 10, "en_US");
ProductPage prods = catalog.getProducts("FISH", 0, 10, "en_US");
ItemPage items    = catalog.getItems("FI-SW-01", 0, 10, "en_US");

// Keyword search — whitespace-tokenized, OR-matched server-side
ItemPage hits = catalog.searchItems("angelfish", 0, 10, "en_US");
boolean more = hits.nextPageAvailable();
```

## See also

- [../README.md](../README.md) — the catalog-service module README (API surface, data, profiles).
- [../CLAUDE.md](../CLAUDE.md) — contract invariants and editing guidance.
- [../../README.md](../../README.md) — the repo overview.
