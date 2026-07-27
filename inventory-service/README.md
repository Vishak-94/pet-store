# inventory-service — fulfilment + inventory (supplier/receiver)

> Part of the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration. See the [repo README](../README.md).

**Port:** `8085` · **Package:** `com.petstore.inventory` · **Legacy origin:** `supplier.ear` (`SupplierOrderMDB` + `OrderFulfillmentFacade` for receive-PO/fulfil/ship/invoice; `RcvrRequestProcessor` for the restock "receiver" UI)

## What it does

Fulfilment + inventory microservice, role **SUPPLIER** (ADMIN also allowed on the inventory
surface). It:

1. Consumes **`OrderApprovedEvent`** from **`ApprovedOrderQueue`** — an already-APPROVED order to
   fulfil. The approval decision lives upstream (order-processing / warehouse); this service only
   fulfils.
2. Reserves stock per line under a **pessimistic row lock** and decides ship / no-ship
   **all-or-nothing** (if any line is short, nothing is reserved and the order stays APPROVED).
3. Publishes **`InvoiceEvent`** to **`InvoiceTopic`** — always, even on short stock
   (`shipped=false`) so downstream can send a backorder/delay notice.
4. Serves the supplier "receiver" **restock UI** (`/inventory`, Thymeleaf) and a **JSON API**
   (`/api/inventory`). On restock, stock is added and a **`RestockEvent`** is published to
   `RestockTopic` so order-processing re-drives its backordered (APPROVED) orders.

## Layout

Two-module Maven aggregator (root `pom.xml`, `packaging=pom`, single-versioned `1.0.0`):

- **`client/`** — the importable **`inventory-service-client`** SDK jar (plain jar, no Spring Boot):
  `InventoryServiceEndpoints` (the shared HTTP contract), `InventoryClient` (thin `RestClient`
  wrapper), and `SingleFlightStockCache`. See [client/README.md](client/README.md).
- **`app/`** — the Spring Boot service (`src/`, `resources/`, `test/`); depends on the client jar so
  the server maps the same `InventoryServiceEndpoints` paths its callers use.

Server packages under `src/com/petstore/inventory`:

| Package | Responsibility |
|---------|----------------|
| `InventoryServiceApplication` | `@SpringBootApplication`; scans `com.petstore.inventory` + `com.petstore.messaging` |
| `service.FulfilmentService` | Fulfilment decision: check-under-lock then reserve, all-or-nothing; skips if already fulfilled (orderId dedup). Holds `BackorderException` |
| `service.RestockService` | Additive restock + publish `RestockEvent`; shared by UI + JSON controllers |
| `messaging.OrderApprovedListener` | `@JmsListener` inbound adapter: consume `OrderApprovedEvent`, fulfil, publish `InvoiceEvent` |
| `repository.InventoryStore` / `FulfilledOrderStore` | Ports; JPA adapters in `repository.jpa` (`findByIdForUpdate` PESSIMISTIC_WRITE, `increment` bulk) |
| `web.InventoryApiController` / `InventoryUiController` / `InventoryLoginController` | JSON API, Thymeleaf receiver UI, login/logout |
| `security.SecurityConfig` | Verify-only RS256, role matchers, stateless |

## Build & run

Java 21 required (`export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`). Depends on the installed
libs `petstore-messaging` and `auth-client` — build those first (`./build-all.sh` from repo root
installs them, then packages this module).

```bash
cd inventory-service && mvn -q clean install     # builds+installs BOTH modules (client jar + app)
```

`install` (not just `package`) because the storefront depends on the published
`inventory-service-client` jar. Runnable jar: `app/target/inventory-service-1.0.0.jar`; SDK:
`client/target/inventory-service-client-1.0.0.jar`. Running needs the shared Artemis broker (hosted
by `petstore-app-v1`) up on `:61616` — prefer `./run-all.sh` from the repo root.

## API surface

| Method & path | Auth | Purpose |
|---------------|------|---------|
| `GET /api/inventory` | SUPPLIER/ADMIN | Full stock table: `{ "EST-1": 42, ... }` |
| `GET /api/inventory/{itemId}/availability` | **public** | Single-item on-hand: `{ "itemId": "...", "quantity": N }` (0 for unknown item, not 404) — feeds the storefront stock badge |
| `POST /api/inventory/{itemId}/restock?qty=` | SUPPLIER/ADMIN | Additive restock + publish `RestockEvent`; 400 if `qty <= 0` |
| `GET /inventory`, `GET/POST /inventory/login`, `POST /inventory/logout`, `POST /inventory/restock` | UI (SUPPLIER/ADMIN) | Thymeleaf receiver console + login flow |

## Events (JMS)

| Direction | Destination | Kind | Event |
|-----------|-------------|------|-------|
| Consumes | `ApprovedOrderQueue` | queue | `OrderApprovedEvent` (orderId, userId, emailId, locale, lines[itemId, productId, categoryId, quantity, unitPrice]) |
| Produces | `InvoiceTopic` | topic | `InvoiceEvent` (orderId, userId, emailId, `shipped`, totalPrice) — always published, even on short stock |
| Produces | `RestockTopic` | topic | `RestockEvent` (itemId, quantityAdded) — on restock, so order-processing re-drives APPROVED backorders |

Records, destinations, and `MessagePublisher` come from the shared `petstore-messaging` lib; the
`queueFactory` and JSON converter are supplied by `com.petstore.messaging.MessagingConfig` (do not
hand-roll JMS config).

## Auth / security

Verify-only: tokens are minted by auth-service and verified here with the bundled **RS256 public
key** (auth-client `AuthJwtFilter`) — this service holds no private key and no credential store.
`/inventory/**` and `/api/inventory/**` require **SUPPLIER** or **ADMIN**; `GET .../availability`
and the actuator/login paths are public. Login delegates to auth-service and drops the token in a
service-specific **`jwt-inventory`** cookie (path `/inventory`, `SameSite=Strict`) so it can't
collide with the warehouse console on `localhost`. Session policy is STATELESS. CSRF is currently
**disabled** for this console (local-demo tradeoff; `SameSite=Strict` blocks the cross-site POST —
re-enable a stable token before a non-local deploy).

## Data

File-based H2 (`jdbc:h2:file:./data/inventory`, overridable via `INVENTORY_DB_PATH`) so stock levels
and the dedup ledger survive restarts. `schema.sql` is idempotent (`CREATE TABLE IF NOT EXISTS`) for
the `inventory` table (with a non-negative `quantity >= 0` CHECK — the oversell floor) and the
`order_id`-keyed `fulfilled_order` dedup ledger; `data.sql` uses idempotent `MERGE` seeds
(`EST-2` seeded at qty 1 to exercise oversell).

- **Pessimistic row lock prevents oversell:** `tryReserve` issues `SELECT … FOR UPDATE`
  (`@Lock(PESSIMISTIC_WRITE)`) then check-and-decrement in one `@Transactional`. The authoritative
  decision is the locked pass; a line that loses the race throws `BackorderException` and rolls back.
- **`order_id`-keyed dedup ledger** (`fulfilled_order`): an order ships at most once, so keying on
  orderId stops both a plain JMS redelivery and a re-driven `OrderApprovedEvent` from
  double-decrementing. `fulfil` checks `isFulfilled(orderId)` first and records `markFulfilled` in
  the same flow as the decrement; a short-stock delivery marks nothing, so it retries after restock.

## See also

- Low-level design + diagrams: [docs/LLD.md](docs/LLD.md)
- Client SDK: [client/README.md](client/README.md)
- Claude guide: [CLAUDE.md](CLAUDE.md)
- Scoped skill: [.claude/skills/inventory-service/SKILL.md](.claude/skills/inventory-service/SKILL.md)
- Repo skill: [../.claude/skills/petstore-dev/SKILL.md](../.claude/skills/petstore-dev/SKILL.md)
- Architecture rationale (ADRs 10, 27, 29, 30, 32): [../DECISIONS.md](../DECISIONS.md)
- Parity baseline (H1, H2, L6, M8): [../docs/PARITY_AUDIT.md](../docs/PARITY_AUDIT.md)
