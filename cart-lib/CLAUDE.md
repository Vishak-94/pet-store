# cart-lib — Claude Code guide

The **embeddable in-process shopping cart** of the migrated Java Pet Store. Holds
per-shopper cart state (item ids → quantities) with a **15-minute sliding TTL** that
mirrors the legacy `HttpSession` timeout, and resolves item names/prices from
catalog-service on demand. Java package root: `com.petstore.cart`.

This is a **LIBRARY, not a service** — it has **no port**, no `main`, no Spring Boot
app. It runs inside the host JVM (petstore-app-v1) as plain beans. A faithful port of
the legacy `ShoppingCartLocalEJB` / `cart.model` behaviour.

> Repo-wide conventions (Java 21, hexagonal rules, parity rule, build/run scripts) live
> in the **`petstore-dev`** skill — read it first and don't duplicate it here. This file
> is only what's specific to cart-lib. See also:
> [`docs/LLD.md`](docs/LLD.md) (class + sequence diagrams),
> [`.claude/skills/cart-lib/SKILL.md`](.claude/skills/cart-lib/SKILL.md) (scoped skill),
> the repo root [`DECISIONS.md`](../DECISIONS.md) (ADRs — check before "restoring" anything),
> and [`docs/PARITY_AUDIT.md`](../docs/PARITY_AUDIT.md) (parity baseline).

## Package layout (`src/com/petstore/cart/`)

| File | Holds |
|------|-------|
| `CartStore.java` | In-memory state: `cartId → CartEntry` (insertion-ordered `itemId→qty` map + last-access `Instant`). Owns the daemon TTL sweeper. `AutoCloseable`. |
| `CartOperations.java` | The business logic (legacy port): `addItem` / `setQuantity` / `deleteItem` / `empty` / `view`. Framework-free; constructed with a `CartStore` + `CatalogServiceClient`. |
| `CartDtos.java` | Wire records: `CartItemView` (a resolved line) and `CartView` (`items`, `subTotal`, `count`). |

`test/com/petstore/cart/CartOperationsTest.java` — characterization tests pinning the
legacy contract (offline; catalog client mocked with seed items EST-1/EST-2 @16.50, EST-5 @18.50).

## Build / test (THIS module)

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # Java 21 required
cd cart-lib
mvn -q clean install    # build + tests, then PUBLISH the jar to ~/.m2 so petstore-app-v1 can resolve it
mvn -q test             # tests only
```

It's a **library**: use `install` (not just `package`) — petstore-app-v1 depends on the
jar being in `~/.m2`. The repo `./build-all.sh` installs it as one of the libs. Flat
layout: sources under `src/`, tests under `test/` (declared in `pom.xml`).

Its one runtime dependency is `catalog-service-client` (item price/name resolution — a
genuinely remote call). The cart state itself is purely local/in-memory.

## Invariants (do NOT break)

1. **Per-user session-scoped state, NEVER a shared singleton.** Every operation is keyed
   by `cartId`; carts are isolated (pinned by `carts_areIsolatedByCartId`). One shopper's
   cart must never leak into another's. The host issues the id (a cookie in petstore-app-v1),
   `CartStore` just partitions state by it. Do not add global/static cart state.
2. **15-minute sliding TTL = legacy session timeout.** A cart idle longer than the TTL is
   evicted, exactly like an `HttpSession` timing out. The TTL *slides*: any touch
   (`withCart`/`snapshot`) refreshes `lastAccess`. Default is 15 min, swept every 60s
   (`new CartStore()`); the host makes it configurable via `cart.ttl-minutes` /
   `cart.sweep-interval-seconds`. Keep 15 min as the default — it is the parity value.
3. **Quantity 0 (or negative) removes the item.** `setQuantity(cartId, itemId, qty)` with
   `qty <= 0` silently deletes the line (no error) — legacy behaviour, pinned by
   `setQuantity_zeroOrNegative_silentlyDeletes`. `setQuantity` sets an ABSOLUTE quantity.
4. **`addItem` RESETS quantity to 1 when qty is null** — it does not increment (pinned by
   `addItem_again_RESETS_quantityToOne_notIncrement`). A non-null qty sets that absolute value.
5. **`view` skips items no longer in the catalog** (no error) — legacy caught
   `CatalogException` and dropped the entry. But `count` = number of DISTINCT lines in the
   raw cart (legacy `getCount == cart.size()`), so a dangling id still counts as a line even
   though it is absent from `items`/`subTotal`.
6. **`unitCost` is the catalog LIST price** (legacy `CartItem` quirk); `subTotal` = Σ(listPrice × qty)
   over resolvable items only.
7. **Framework-free / minimal-Spring domain.** No Spring/JPA/Jackson annotations in this
   library. It is wired as beans by the host (`CartConfig`), not by this module. The TTL
   sweeper is a plain daemon `ScheduledExecutorService`; the host registers
   `destroyMethod = "close"` so it stops on shutdown.

## Who consumes it

**Only `petstore-app-v1`** (the :8080 storefront). It wires two beans in
`com.petstore.cart.config.CartConfig` — `CartStore` (with `destroyMethod="close"`) and
`CartOperations` — and its `CartService` reads the per-request `cartId` (minted by
`CartIdFilter` as an HttpOnly cookie) and delegates to `CartOperations`. cart-lib knows
nothing about HTTP, cookies, or sessions — the host owns cart-id issuance.

## Gotchas

- `empty(cartId)` calls `store.remove` (drops the whole entry) so an emptied cart doesn't
  linger; the other ops mutate the entry in place and refresh the TTL.
- `evictExpired()` is package-visible so tests can trigger a sweep deterministically
  (see `ttlSweep_evictsIdleCarts`, which uses a 0-minute TTL).
- `withCart` synchronizes per `CartEntry`, so mutations of one cart are atomic; the
  `carts` map itself is a `ConcurrentHashMap`.
- `LOCALE` is hardcoded to `"en_US"` for catalog resolution inside `CartOperations.view`.
