---
name: cart-lib
description: >-
  Conventions for the cart-lib module — the embeddable, in-process shopping cart of the
  migrated Java Pet Store (package com.petstore.cart, a LIBRARY with no port). Use when
  working on shopping cart state, cart line items, add/remove/update quantity math, absolute
  quantity vs increment, quantity-0 removal, distinct-line count, subtotal from catalog list
  price, dangling-item skipping, or the 15-minute sliding session TTL / cart eviction. Trigger
  terms: cart, CartStore, CartOperations, CartView, CartItemView, cartId, setQuantity, addItem,
  session TTL, sliding timeout, cart eviction.
---

# cart-lib — module skill

cart-lib is the **in-process shopping cart** library (`com.petstore.cart`). It is a LIBRARY,
not a service: **no port, no `main`**. It is embedded by petstore-app-v1 and runs in that JVM.
A faithful port of the legacy `ShoppingCartLocalEJB` / `cart.model`.

Read the repo-wide [`petstore-dev`](../../../../.claude/skills/petstore-dev/SKILL.md) skill
first for build/run, hexagonal rules, and the parity rule. This skill is only cart-lib
specifics. Deep design: [`../../../docs/LLD.md`](../../../docs/LLD.md); future-Claude guide:
[`../../../CLAUDE.md`](../../../CLAUDE.md).

## Conventions & invariants

- **Session-scoped, NEVER a singleton.** Every operation is keyed by `cartId`; carts are
  isolated per shopper. No static/global cart state; never key on username (logged-out
  shoppers have carts). The host issues the id — cart-lib only partitions state by it.
- **Quantity semantics (legacy-faithful, pinned by tests):**
  - `addItem(cartId, itemId, null)` sets quantity to **1** — RESETS, does NOT increment.
  - `addItem(cartId, itemId, n)` sets absolute `n`.
  - `setQuantity(cartId, itemId, qty)` sets an **absolute** quantity; `qty <= 0` **silently
    removes** the line (no error).
  - `deleteItem` removes one line; `empty` drops the whole cart.
- **15-minute sliding TTL = legacy `HttpSession` timeout.** Any touch (`withCart`/`snapshot`)
  refreshes `lastAccess`; a daemon sweeper evicts carts idle past the TTL. Default 15 min,
  swept every 60s (`new CartStore()`). Keep 15 min as the parity default even though the host
  makes it configurable (`cart.ttl-minutes`, `cart.sweep-interval-seconds`).
- **`view()` resolves late and skips dangling items.** Prices/names come from
  `CatalogServiceClient` at view time; items missing from the catalog are silently skipped
  from `items`/`subTotal` (no error) but still count toward `count`. `count` = number of
  DISTINCT lines in the raw cart (legacy `getCount == cart.size()`). `unitCost` = catalog
  **list price**; `subTotal` = Σ(listPrice × qty) over resolvable items.
- **Framework-free.** No Spring/JPA/Jackson annotations in this library. `CartOperations` and
  `CartStore` are plain classes constructed with dependencies; the host wires them as beans.
  The sweeper is a daemon `ScheduledExecutorService` stopped via `close()`.

## How petstore-app-v1 embeds it

- `com.petstore.cart.config.CartConfig` declares two beans: `CartStore`
  (`@Bean(destroyMethod = "close")`, TTL/sweep from config) and `CartOperations`.
- `CartIdFilter` mints/reads an HttpOnly `cartId` cookie (128-bit SecureRandom); `CartService`
  reads that id per request and delegates to `CartOperations`. cart-lib knows nothing about
  HTTP, cookies, or sessions.

## Build / test

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd cart-lib
mvn -q clean install    # LIBRARY: install to ~/.m2 so petstore-app-v1 resolves it
mvn -q test             # tests only
```

Flat layout: `src/` + `test/`. Only runtime dep is `catalog-service-client` (price/name
resolution). Tests are offline (catalog client mocked). Do not weaken characterization tests
to go green — they pin the legacy contract.
