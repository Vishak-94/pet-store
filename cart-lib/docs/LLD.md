# cart-lib — Low-Level Design

The embeddable in-process shopping cart for the migrated Java Pet Store. It is a
**library** (no port, no `main`): the host application (petstore-app-v1) wires it as
beans and runs it in the same JVM. cart-lib is a faithful port of the legacy
`ShoppingCartLocalEJB` + `cart.model` behaviour.

> Repo-wide conventions live in the [`petstore-dev`](../../.claude/skills/petstore-dev/SKILL.md)
> skill; module-scoped conventions in [`../.claude/skills/cart-lib/SKILL.md`](../.claude/skills/cart-lib/SKILL.md);
> future-Claude guidance in [`../CLAUDE.md`](../CLAUDE.md). Parity baseline:
> [`../../docs/PARITY_AUDIT.md`](../../docs/PARITY_AUDIT.md); ADRs: [`../../DECISIONS.md`](../../DECISIONS.md).

## Overview

Three classes in package `com.petstore.cart`:

- **`CartStore`** — the state holder. A `ConcurrentHashMap<String, CartEntry>` keyed by
  `cartId`; each `CartEntry` is an insertion-ordered `itemId → quantity` map plus a
  last-access `Instant`. A daemon `ScheduledExecutorService` sweeps expired carts on a
  **sliding TTL** (default 15 min = legacy session timeout). Framework-free; `AutoCloseable`.
- **`CartOperations`** — the business logic. Keyed by `cartId`, it mutates state through
  `CartStore` and resolves item names/prices from catalog-service via `CatalogServiceClient`.
  A faithful port of the legacy shopping-cart EJB.
- **`CartDtos`** — wire records: `CartItemView` (one resolved line) and `CartView`
  (`items`, `subTotal`, `count`).

State (quantities) and presentation (resolved prices) are deliberately separated: the store
keeps only `itemId → qty`; prices/names are resolved lazily in `view()` so cart state never
goes stale against the catalog.

## Class diagram

```mermaid
classDiagram
    class CartOperations {
        -CartStore store
        -CatalogServiceClient catalog
        -String LOCALE = "en_US"
        +CartOperations(CartStore, CatalogServiceClient)
        +addItem(cartId, itemId, Integer qty) CartView
        +setQuantity(cartId, itemId, int qty) CartView
        +deleteItem(cartId, itemId) CartView
        +empty(cartId) void
        +view(cartId) CartView
    }

    class CartStore {
        -ConcurrentHashMap~String, CartEntry~ carts
        -Duration ttl
        -ScheduledExecutorService sweeper
        +CartStore()
        +CartStore(long ttlMinutes, long sweepIntervalSeconds)
        +withCart(cartId, Function op) T
        +snapshot(cartId) Map~String,Integer~
        +remove(cartId) void
        ~evictExpired() void
        +size() int
        +close() void
    }

    class CartEntry {
        +Map~String,Integer~ quantities
        +Instant lastAccess
    }

    class CartView {
        +List~CartItemView~ items
        +double subTotal
        +int count
        +empty()$ CartView
    }

    class CartItemView {
        +String itemId
        +String productId
        +String category
        +String productName
        +String attribute
        +int quantity
        +double unitCost
        +totalCost() double
    }

    class CatalogServiceClient {
        <<interface>>
        +getItem(itemId, locale) Optional~ItemDto~
    }

    CartOperations --> CartStore : mutates/reads state
    CartOperations --> CatalogServiceClient : resolves price/name
    CartOperations ..> CartView : returns
    CartStore *-- CartEntry : owns (per cartId)
    CartView o-- CartItemView : contains
```

`CartStore implements AutoCloseable`. `CartEntry` is a private static nested class of
`CartStore`; `CartItemView` and `CartView` are nested records of `CartDtos`.

## Sequence — add item / update quantity (incl. quantity 0 removal)

```mermaid
sequenceDiagram
    participant Host as petstore-app-v1 (CartService)
    participant Ops as CartOperations
    participant Store as CartStore
    participant Cat as CatalogServiceClient

    Note over Host,Ops: addItem(cartId, itemId, qty)
    Host->>Ops: addItem(cartId, "EST-1", qty)
    Ops->>Store: withCart(cartId, q -> q.put(itemId, qty==null ? 1 : qty))
    Note right of Store: computeIfAbsent creates entry;<br/>lastAccess = now (sliding TTL);<br/>null qty RESETS to 1 (not increment)
    Store-->>Ops: (mutated)
    Ops->>Ops: view(cartId)

    Note over Host,Ops: setQuantity(cartId, itemId, qty)
    Host->>Ops: setQuantity(cartId, "EST-1", qty)
    Ops->>Store: withCart(cartId, q -> { q.remove(itemId); if qty>0 q.put(itemId, qty) })
    alt qty > 0
        Note right of Store: absolute quantity set
    else qty <= 0
        Note right of Store: line SILENTLY removed (legacy behaviour)
    end
    Store-->>Ops: (mutated)

    Note over Ops,Cat: view(cartId) — resolve for display
    Ops->>Store: snapshot(cartId)
    Store-->>Ops: ordered {itemId -> qty} (TTL refreshed)
    loop each entry
        Ops->>Cat: getItem(itemId, "en_US")
        alt item present
            Cat-->>Ops: ItemDto(listPrice, ...)
            Note right of Ops: line added; subTotal += listPrice * qty
        else item missing (dangling)
            Cat-->>Ops: Optional.empty()
            Note right of Ops: SKIPPED — no error (but still counts toward count)
        end
    end
    Ops-->>Host: CartView(items, subTotal, count=distinct lines)
```

## Sequence — TTL expiry / eviction

```mermaid
sequenceDiagram
    participant Sweeper as cart-ttl-sweeper (daemon)
    participant Store as CartStore
    participant Entry as CartEntry(s)

    Note over Sweeper: scheduleWithFixedDelay every sweepIntervalSeconds (default 60s)
    Sweeper->>Store: evictExpired()
    Store->>Store: cutoff = now - ttl (default 15 min)
    Store->>Entry: removeIf(lastAccess.isBefore(cutoff))
    Note right of Entry: idle carts dropped,<br/>= HttpSession timing out
    Store-->>Sweeper: (map shrunk)

    Note over Store: any withCart()/snapshot() before cutoff<br/>refreshes lastAccess -> TTL slides, cart survives
    Note over Store: close() -> sweeper.shutdownNow() (host destroyMethod)
```

## Cart operations

| Operation | Signature | Semantics |
|-----------|-----------|-----------|
| Add item | `addItem(cartId, itemId, Integer qty)` | Sets quantity to `qty`, or **1 when `qty` is null** — RESETS, never increments. Returns the resolved `CartView`. |
| Set quantity | `setQuantity(cartId, itemId, int qty)` | Sets an **absolute** quantity. `qty <= 0` silently **removes** the line (no error). |
| Delete line | `deleteItem(cartId, itemId)` | Removes one line by item id. |
| Empty | `empty(cartId)` | Drops the whole cart (`store.remove`) so it doesn't linger; returns void. |
| View | `view(cartId)` | Resolves lines against catalog; **skips items missing from the catalog** (no error). `subTotal` = Σ(listPrice × qty) over resolvable items; `count` = number of DISTINCT lines in the raw cart (dangling ids still count). |

Store-level helpers: `withCart` (atomic per-cart mutation + TTL refresh), `snapshot`
(ordered copy + TTL refresh), `remove`, `evictExpired` (package-visible for tests),
`size`, `close`.

## Design decisions / invariants

- **Session-scoped, not a singleton.** All state is partitioned by `cartId`; carts are
  isolated. cart-lib never keys on username (logged-out shoppers have carts) and holds no
  global cart state. The host issues the id (HttpOnly cookie in petstore-app-v1); the store
  only partitions by it.
- **15-minute sliding TTL = legacy session timeout.** Any touch refreshes `lastAccess`; the
  daemon sweeper evicts carts idle past the TTL, mirroring `HttpSession` expiry. 15 min is
  the parity default; the host exposes it as config but keeps 15 as the default.
- **Quantity semantics are legacy-faithful and pinned by tests:** null-qty add resets to 1;
  `setQuantity <= 0` removes; `setQuantity` is absolute.
- **Resolve late, skip dangling.** State stores only ids/quantities; prices/names resolve in
  `view()`. Items absent from the catalog are silently skipped from `items`/`subTotal` but
  still counted in `count` (legacy `getCount == cart.size()`). `unitCost` is the catalog
  **list price** (legacy `CartItem` quirk).
- **Framework-free domain.** No Spring/JPA/Jackson in the library; the host wires the beans.
  The sweeper is a plain daemon thread stopped via `close()` (host `destroyMethod`).
- **Concurrency.** `carts` is a `ConcurrentHashMap`; mutations synchronize on the per-cart
  `CartEntry`, so each cart's updates are atomic.
