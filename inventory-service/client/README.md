# inventory-service-client — inventory-service client SDK

> Importable SDK jar for inventory-service. Part of the Pet Store migration ([repo README](../../README.md)).

## What it provides

A thin, importable client for inventory-service's **public** per-item availability read
(`GET /api/inventory/{itemId}/availability`). Consumers just `new InventoryClient(baseUrl)` and call
`stockFor(itemId)` — no URLs or JSON shapes leak into caller code, because the path is single-sourced
in `InventoryServiceEndpoints`, which the server also maps (so the server provably can't drift from
what callers expect). Plain jar — `spring-web` (`RestClient`) + `spring-context` only, no Spring Boot
starter. The bare constructors build a `RestClient` with bounded connect (2s) / read (5s) timeouts so
a hung inventory-service can't block caller threads; an advanced constructor accepts a preconfigured
`RestClient` (e.g. with a circuit breaker / retry).

**`SingleFlightStockCache`** — reads are cached in-process behind this cache:

- **TTL freshness** (default 1 hour): a fresh entry is served straight from a `ConcurrentHashMap`
  with no lock and no backend call.
- **Single-flight refresh** (cache-stampede protection): when an entry is missing/expired and several
  threads ask for the same item at once, exactly one thread acquires that item's lock and hits the
  backend; the others wait, then double-check the map and return the winner's value — one backend hit
  per refresh, not one per reader. Locking is per-item, so refreshing item A never blocks item B.
- **Failures and blanks are not cached:** a transport error / breaker-open propagates unchanged (the
  caller degrades, e.g. hides the badge) and nothing is stored, so the next call retries and the badge
  self-heals. An empty (no-quantity) load is likewise not cached.

This is for the storefront's **cosmetic** stock badge / stepper cap, which tolerates being up to a
TTL stale — it is **never** an oversell guard (the authoritative all-or-nothing stock check stays at
fulfilment in inventory-service, under a pessimistic row lock). Because the cache lives in the SDK,
every consumer of this jar gets it for free.

## Maven coordinates

```xml
<dependency>
    <groupId>com.petstore</groupId>
    <artifactId>inventory-service-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Key classes

| Class | Role |
|-------|------|
| `InventoryServiceEndpoints` | HTTP contract: path constants (`AVAILABILITY`, `ALL_INVENTORY`, `RESTOCK`), `DEFAULT_BASE_URL` (`http://localhost:8085`), `KEY_QUANTITY`. Shared by server (maps them) and clients (call them) |
| `InventoryClient` | Thin `RestClient` wrapper; `stockFor(itemId)` → `Optional<Integer>` (never negative), served through the cache |
| `SingleFlightStockCache` | In-process TTL + single-flight stock cache in front of the remote read (package-private; used by `InventoryClient`) |

## Usage

```java
// Default base URL (http://localhost:8085) and default 1-hour TTL:
InventoryClient inventory = new InventoryClient();

// Or a specific host/port per environment:
InventoryClient inventory = new InventoryClient("http://inventory.internal:8085");

Optional<Integer> onHand = inventory.stockFor("EST-2");
onHand.ifPresentOrElse(
        qty -> renderBadge(qty),      // "in stock" / "only N left" / "out of stock"
        ()  -> hideBadge());          // couldn't determine (transport error / no quantity)

// Advanced: supply a preconfigured RestClient (circuit breaker / retry / TLS) and a custom TTL:
InventoryClient resilient = new InventoryClient(myResilientRestClient, Duration.ofMinutes(5));
```

A `stockFor` call surfaces transport failures as `RestClientException` (not swallowed, not cached) so
the caller decides how to degrade.

## See also

- Service README: [../README.md](../README.md)
- Low-level design: [../docs/LLD.md](../docs/LLD.md)
- Repo README: [../../README.md](../../README.md)
