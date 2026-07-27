# inventory-service — Claude guide

Fulfilment + inventory microservice for the migrated Java Pet Store. Migrated from the
legacy **supplier.ear** (`SupplierOrderMDB` + `OrderFulfillmentFacade` = receive approved
PO / fulfil / ship / invoice; `RcvrRequestProcessor` = restock "receiver" UI).

- **Port:** `8085`
- **Role:** `SUPPLIER` (ADMIN also allowed on inventory endpoints)
- **Java package:** `com.petstore.inventory`
- **Layout:** flat module (`src/`, `resources/`; no `test/` present yet)

Read the repo skill `.claude/skills/petstore-dev/SKILL.md` first for the fleet-wide picture
(module map, JMS contract, build/run, parity rule). This file is scoped to inventory-service.

## What this service does

1. Consumes **`OrderApprovedEvent`** from **`ApprovedOrderQueue`** (a queue) — an already-APPROVED
   order to fulfil. The approval decision lives in `order-processing-service`/`warehouse`; this
   service only fulfils.
2. Reserves stock per line under a **pessimistic row lock** and decides ship / no-ship
   **all-or-nothing**.
3. Publishes **`InvoiceEvent`** to **`InvoiceTopic`** (a topic → fans out to
   order-processing for COMPLETED + notification-service for the customer email).
4. Serves the supplier "receiver" **restock UI** (`/inventory`, Thymeleaf) and **JSON API**
   (`/api/inventory`, `/api/inventory/{itemId}/restock`). On restock, `RestockService` adds stock
   **and** publishes a **`RestockEvent`** to `RestockTopic` so order-processing re-drives backordered
   (APPROVED) orders — the migrated form of the legacy `processPendingPO`-on-restock (PARITY_AUDIT H2).

## Package layout (`src/com/petstore/inventory`)

| Package | Type | Responsibility |
|---------|------|----------------|
| `InventoryServiceApplication` | `@SpringBootApplication` | Boot entry; scans `com.petstore.inventory` + `com.petstore.messaging` |
| `service.FulfilmentService` | `@Service` | The fulfilment decision: check-under-lock then reserve, all-or-nothing; skips if `FulfilledOrderStore.isFulfilled(orderId)` (orderId dedup). Holds `BackorderException` |
| `service.RestockService` | `@Service` | Additive restock + publish `RestockEvent` to `RestockTopic`; shared by UI + JSON controllers |
| `messaging.OrderApprovedListener` | `@Component` `@JmsListener` | Inbound adapter: consume `OrderApprovedEvent`, call `fulfil`, publish `InvoiceEvent` |
| `repository.InventoryStore` | port (interface) | `quantityOf`, `tryReserve` (locked decrement), `addQuantity`, `all` |
| `repository.FulfilledOrderStore` | port (interface) | `isFulfilled(orderId)` / `markFulfilled(orderId)` — orderId-keyed dedup ledger (`fulfilled_order`), JPA adapter in `repository.jpa` |
| `repository.jpa.JpaInventoryStore` | adapter | Implements the port over Spring Data JPA |
| `repository.jpa.InventoryJpaRepository` | Spring Data | `findByIdForUpdate` (PESSIMISTIC_WRITE), `increment` (bulk `@Modifying`) |
| `repository.jpa.InventoryEntity` | `@Entity` | Maps the `inventory` table (`item_id`, `quantity`) |
| `web.InventoryUiController` | `@Controller` | `/inventory` view + `/inventory/restock` |
| `web.InventoryApiController` | `@RestController` | `/api/inventory`, `/api/inventory/{itemId}/restock` |
| `web.InventoryLoginController` | `@Controller` | `/inventory/login`, `/inventory/logout` — delegate to auth-service, set the `jwt-inventory` cookie |
| `security.SecurityConfig` | `@Configuration` | Verify-only RS256 (public key), role matchers, stateless |

`resources/`: `application.yml` (port 8085, **file-based** H2 `jdbc:h2:file:./data/inventory`
overridable via `INVENTORY_DB_PATH` — stock levels + the dedup ledger survive restarts;
shared Artemis `tcp://localhost:61616`), `schema.sql` (idempotent `CREATE TABLE IF NOT EXISTS`
for the `inventory` table + non-negative CHECK **and** the `order_id`-keyed `fulfilled_order` dedup
ledger — drops the legacy eventId-keyed `processed_event` if present),
`data.sql` (idempotent `MERGE` seed so re-boot on a populated file DB is safe; `EST-2` seeded
at qty 1 to exercise oversell), `templates/` (`inventory.html`, `login.html`).

## Events

| Direction | Destination | Kind | Event |
|-----------|-------------|------|-------|
| Consumes | `ApprovedOrderQueue` | queue | `OrderApprovedEvent` (orderId, userId, emailId, locale, lines[itemId, productId, categoryId, quantity, unitPrice]) |
| Produces | `InvoiceTopic` | topic | `InvoiceEvent` (orderId, userId, emailId, `shipped`, totalPrice) |
| Produces | `RestockTopic` | topic | `RestockEvent` (itemId, quantityAdded) — on restock, so OPC re-drives APPROVED backorders (H2) |

Both records + destinations + `MessagePublisher` come from the shared `petstore-messaging` lib.
Do not hand-roll JMS config — the `queueFactory` and JSON converter are supplied by
`com.petstore.messaging.MessagingConfig` (scanned via `scanBasePackages`).

## Invariants — DO NOT "fix" without checking DECISIONS.md

- **All-or-nothing fulfilment is INTENTIONAL** (DECISIONS.md; PARITY_AUDIT H1). If any line is
  short, **nothing** is reserved or decremented and `InvoiceEvent.shipped=false`; the order stays
  APPROVED. There is no partial shipment and **no `OrderStatus.SHIPPED_PART`** — the dead legacy
  state was removed and `FulfilmentService`'s javadoc corrected to describe the real behaviour.
  This intentionally diverges from legacy `OrderFulfillmentFacadeEJB.processAnOrder`.
- **Backorder retry-on-restock IS wired** (PARITY_AUDIT H2, event-driven). Restock is still additive
  `addQuantity` (no persisted supplier PO — M8 kept), but `RestockService` now also **publishes a
  `RestockEvent` to `RestockTopic`** so order-processing re-drives its APPROVED backorders through
  fulfilment. This service owns no order read-model — it only announces the restock; OPC decides which
  orders to retry. Do not add order-querying or re-fulfilment logic here.
- **Dedup ledger is `order_id`-keyed** (`fulfilled_order`, replacing the old eventId-keyed
  `processed_event`). An order ships at most once, so keying on orderId stops BOTH a plain JMS redelivery
  AND a re-driven `OrderApprovedEvent` (fresh eventId, one per APPROVED order per restock) from
  double-decrementing an already-shipped order. `FulfilmentService.fulfil` checks `isFulfilled(orderId)`
  first and records `markFulfilled(orderId)` in the same flow as the decrement; a short-stock delivery
  marks nothing so it retries after restock. `schema.sql` drops `processed_event` if present.
- **Pessimistic lock prevents oversell.** `tryReserve` issues `SELECT … FOR UPDATE`
  (`@Lock(PESSIMISTIC_WRITE)`) then check-and-decrement inside one `@Transactional`. Verified with a
  20-thread test (5 stock → 5 succeed, never negative). The DB CHECK `quantity >= 0` is the floor.
  `fulfil` has a benign first-pass unlocked read (TOCTOU) — the **authoritative** decision is the
  locked second pass, which throws `BackorderException` to roll back if a line loses the race.
- **Idempotent consumer.** JMS is at-least-once; terminal-state ownership lives in
  order-processing (skip-if-COMPLETED/DENIED). Keep `fulfil` safe to retry — never assume exactly-once.
- **Always publishes `InvoiceEvent`.** Even on short stock (`shipped=false`) — this enables
  backorder/delay notification (PARITY_AUDIT L6; differs from legacy `if(invoice!=null)`).
- **Verify-only auth.** SUPPLIER/ADMIN on `/inventory/**` + `/api/inventory/**`; login delegates to
  auth-service and drops the RS256 token in a **`jwt-inventory`** cookie (service-specific name +
  `/inventory` path, so it can't collide with the warehouse console when both are open on
  `localhost`; see the login controller/SecurityConfig javadoc). CSRF is currently **disabled** for
  this console (local-demo tradeoff — the tokened version rotated the XSRF cookie per request under
  STATELESS + per-request re-auth → stale-token 403; `SameSite=Strict` on the JWT cookie blocks the
  cross-site POST in its place; re-enable a stable token before a non-local deploy). This service
  holds no private key and no credential store.

## Build & test (this module)

Java 21 required (`export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`). Depends on the installed
libs `petstore-messaging` and `auth-client` — build those first (`./build-all.sh` from repo root
installs them, then packages this module).

```bash
cd inventory-service && mvn -q clean package     # build + tests (test dir is optional)
```

Run needs the shared Artemis broker (hosted by `petstore-app-v1`) up on `:61616`; prefer
`./run-all.sh` from the repo root. New tests go in `test/` and must not import legacy EJB types;
pin the fulfilment decision (all-or-nothing) and the locked-reserve concurrency behaviour.

## See also

- Low-level design + diagrams: [docs/LLD.md](docs/LLD.md)
- Scoped skill: [.claude/skills/inventory-service/SKILL.md](.claude/skills/inventory-service/SKILL.md)
- Repo skill: [../.claude/skills/petstore-dev/SKILL.md](../.claude/skills/petstore-dev/SKILL.md)
- Architecture rationale (ADRs 10, 27, 29, 30, 32): [../DECISIONS.md](../DECISIONS.md)
- Parity baseline (H1, H2, L6, M8): [../docs/PARITY_AUDIT.md](../docs/PARITY_AUDIT.md)
