---
name: inventory-service
description: Conventions for the migrated Pet Store inventory-service (:8085, SUPPLIER role, package com.petstore.inventory) — fulfilment + inventory stock. Use when working on order fulfilment, the all-or-nothing ship decision, stock reservation / pessimistic locking (SELECT ... FOR UPDATE), the ApprovedOrderQueue JMS listener, publishing InvoiceEvent to InvoiceTopic, restock / receiver UI, backorder / oversell behaviour, or the inventory table. Triggers: fulfil, fulfilment, FulfilmentService, inventory, stock, reserve, tryReserve, pessimistic lock, oversell, backorder, all-or-nothing, ApprovedOrderQueue, OrderApprovedEvent, InvoiceTopic, InvoiceEvent, restock, receiver, SUPPLIER.
---

# inventory-service — scoped skill

Fulfilment + inventory service (legacy **supplier.ear**). Port **8085**, role **SUPPLIER**
(ADMIN also allowed), package `com.petstore.inventory`, flat module (`src/`, `resources/`).
Consumes `OrderApprovedEvent` from `ApprovedOrderQueue`; publishes `InvoiceEvent` to `InvoiceTopic`.

Read first for full detail:
- Module guide: [../../../CLAUDE.md](../../../CLAUDE.md)
- Low-level design + diagrams: [../../../docs/LLD.md](../../../docs/LLD.md)
- Repo-wide skill: `petstore-dev` (`../../../../.claude/skills/petstore-dev/SKILL.md`)

## Conventions

### Fulfilment decision rule — all-or-nothing (INTENTIONAL)
`FulfilmentService.fulfil` ships an order **only if every line has sufficient stock**. If any
line is short, **nothing is reserved or decremented** and the invoice reports `shipped=false`;
the order stays APPROVED. There is **no partial shipment** and **no `OrderStatus.SHIPPED_PART`**
(that legacy state was removed as dead code; the javadoc was corrected to match reality). This
diverges from legacy `OrderFulfillmentFacadeEJB` **on purpose** — recorded in DECISIONS.md and
PARITY_AUDIT H1. Do not "restore" partial shipment or backorder-retry without checking those.

### Locking pattern — pessimistic, race-safe decrement
Stock is decremented only via `InventoryStore.tryReserve`, implemented by `JpaInventoryStore`
using `InventoryJpaRepository.findByIdForUpdate` = `@Lock(PESSIMISTIC_WRITE)` → `SELECT … FOR
UPDATE`, then check-and-decrement inside one `@Transactional`. This prevents oversell (verified
20-thread test; DB `CHECK (quantity >= 0)` is the floor). `fulfil`'s first pass is an unlocked
read for a fast abort (benign TOCTOU); the **locked second pass is authoritative** and throws
`BackorderException` to roll back all prior decrements if a line loses the race. Keep any new
stock mutation behind the port and behind the lock — never `EntityManager` from the service.

### Listener + publisher wiring (shared messaging contract)
`OrderApprovedListener` (`@JmsListener(destination = "ApprovedOrderQueue",
containerFactory = "queueFactory")`) calls `fulfil`, then publishes via the shared
`MessagePublisher` to `Destinations.INVOICE` (`InvoiceTopic`). Use the shared `petstore-messaging`
lib — `Destinations`, `Events.meta(...)`, event records, `MessagePublisher`, and the
`queueFactory` + JSON converter from `MessagingConfig` (picked up via `scanBasePackages`
`{"com.petstore.inventory", "com.petstore.messaging"}`). Never hand-roll JMS config or destination
names here.

### Always publish InvoiceEvent
Publish an `InvoiceEvent` on **both** ship and short-stock — the `shipped` boolean carries the
outcome (downstream completes the order or notifies a delay). Do not gate publishing on ship
success (PARITY_AUDIT L6). Carry `userId` + `emailId` through so notification-service can email.

### Idempotency
The queue is at-least-once — the listener may be redelivered. Keep `fulfil` retry-safe; terminal
ownership (skip-if-COMPLETED/DENIED) lives in order-processing (ADR 29). Never assume exactly-once.

### Restock semantics (receiver)
`/inventory/restock` (UI) and `/api/inventory/{itemId}/restock` (JSON) add stock via
`addQuantity` (bulk `@Modifying` UPDATE, flush+clear). Reject `qty <= 0`. Restock is purely
additive and **does not re-trigger backordered orders** — no persisted supplier PO exists
(PARITY_AUDIT H2/M8).

### Security
Verify-only RS256: `SecurityConfig` verifies tokens with the bundled public key
(`auth-client` `AuthJwtFilter`); login delegates to auth-service and sets a `jwt` cookie. No
local credential store, no token minting. `/inventory/**` + `/api/inventory/**` require SUPPLIER
or ADMIN; `/actuator/**` and login/logout are public. API paths get JSON 401/403; UI paths redirect.

## Build / run
Java 21. Needs installed libs `petstore-messaging` + `auth-client` (`./build-all.sh` installs them
then packages this module). One module: `cd inventory-service && mvn -q clean package`. Running
needs the shared Artemis broker (in `petstore-app-v1`) on `:61616` — use `./run-all.sh`. Tests go
in `test/`, must not import legacy EJB types, and should pin the all-or-nothing decision + locked
reserve concurrency.
