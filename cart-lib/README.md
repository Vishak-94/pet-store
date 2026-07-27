# cart-lib — embeddable in-process shopping cart

> Shared library for the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration (in-process jar, not a service). See the [repo README](../README.md).

**Package:** `com.petstore.cart` · **Legacy origin:** the stateful `ShoppingCartLocalEJB` / `cart.model` session bean (per-`HttpSession` cart state).

## What it provides

- An **in-process** shopping cart that holds per-shopper state (`cartId → itemId→quantity`) — runs inside the host JVM as plain beans, with **no port, no `main`, no Spring Boot app**.
- **Per-user (session-scoped) semantics — NOT a shared singleton.** Every operation is keyed by `cartId`, so one shopper's cart can never leak into another's.
- A **15-minute sliding TTL** that mirrors the legacy `HttpSession` timeout: any touch refreshes last-access, and idle carts are evicted by a daemon sweeper.
- Cart **business logic** (`addItem` / `setQuantity` / `deleteItem` / `empty` / `view`) with a well-tested **quantity cap of `MAX_QUANTITY` = 999** (over-cap requests are clamped, not rejected).
- On-demand resolution of item **names and prices** from `catalog-service` via its client SDK (the cart's only network dependency; the cart state itself is purely local/in-memory).
- Framework-free domain: no Spring / JPA / Jackson annotations — the host wires it up as beans.

## Maven coordinates

```
com.petstore:cart-lib:1.0.0
```

## Key types

| Type | Role |
|------|------|
| `CartStore` | In-memory state (`cartId → CartEntry`: insertion-ordered `itemId→qty` map + last-access `Instant`). Owns the daemon TTL sweeper; `AutoCloseable` (host registers `destroyMethod="close"`). |
| `CartOperations` | The business-logic port of the legacy cart: `addItem` / `setQuantity` / `deleteItem` / `empty` / `view`. Framework-free; constructed with a `CartStore` + a `CatalogServiceClient`. |
| `CartDtos` | Wire records: `CartItemView` (a resolved line) and `CartView` (`items`, `subTotal`, `count`). |

## Used by

- **`petstore-app-v1`** only (the storefront). It wires `CartStore` + `CartOperations` in `com.petstore.cart.config.CartConfig`; the host owns cart-id issuance (a cookie minted by `CartIdFilter`). cart-lib knows nothing about HTTP, cookies, or sessions.

## Build

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # Java 21 required
cd cart-lib
mvn -q clean install    # build + tests, then PUBLISH the jar to ~/.m2
```

It is a **library**: use `install` (not just `package`) — it must be installed to `~/.m2` **before** its dependent (`petstore-app-v1`) can resolve it. The repo `./build-all.sh` installs it as one of the shared libs. Flat layout: sources under `src/`, tests under `test/`.

## See also

- [`CLAUDE.md`](CLAUDE.md) — invariants, gotchas, package layout
- [`docs/LLD.md`](docs/LLD.md) — class + sequence diagrams
- [`../DECISIONS.md`](../DECISIONS.md) — ADRs (check before "restoring" anything)
- [`../docs/PARITY_AUDIT.md`](../docs/PARITY_AUDIT.md) — legacy behavioural baseline
