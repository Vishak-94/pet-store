# order-processing-service — Claude guide

The **Order Processing Center (OPC)** — the modern form of the legacy `opc.ear`. It is the
**authoritative owner of orders and their workflow status** for the whole system. Runs on
**port 8088**, package root `com.petstore.opc`.

Read the repo-wide skill first: [`.claude/skills/petstore-dev/SKILL.md`](../.claude/skills/petstore-dev/SKILL.md)
(module map, JMS contract, hexagonal rules, order workflow, build/run). This file only covers
what is **specific to this module** — do not duplicate the shared content.

- Design detail + diagrams: [`docs/LLD.md`](docs/LLD.md)
- Per-app skill: [`.claude/skills/order-processing-service/SKILL.md`](.claude/skills/order-processing-service/SKILL.md)
- Repo rationale (~30 ADRs): [`../DECISIONS.md`](../DECISIONS.md) — **check before "restoring" any legacy behaviour**
- Parity baseline: [`../docs/PARITY_AUDIT.md`](../docs/PARITY_AUDIT.md)

## What it does

- Consumes **`PurchaseOrderQueue`** (`PurchaseOrderEvent`, storefront checkout) → persists the order,
  then **auto-approves** under the locale threshold or leaves it **PENDING** for a human admin.
- Consumes **`InvoiceTopic`** (`InvoiceEvent`, inventory ship) → moves the order to **COMPLETED**.
- Publishes **`OrderApprovedEvent`** to **`ApprovedOrderQueue`** (→ inventory-service fulfils) on approval.
- Publishes **`OrderStatusEvent`** to **`OrderStatusTopic`** (→ notification emails) on APPROVED/DENIED/COMPLETED.
- Exposes the **admin facade API** (`/api/orders/**`, `/api/sales`) that admin-office-service calls —
  the legacy `OPCAdminFacade`. OPC owns NO admin UI; it owns the data and the workflow.

## app / client split

- **`app/`** — the Spring Boot service (`com.petstore.opc`): domain, service, messaging listeners,
  repository port + JPA adapter, web controller, security. This is what runs on :8088.
- **`client/`** — `order-processing-client` (`com.petstore.opc.client`): thin typed `OrderProcessingClient`
  + `OrderDtos` + `OrderProcessingEndpoints` (path constants). Imported by **admin-office-service** and
  by `app` itself (the controller reuses the SDK DTOs/endpoint constants so the contract is single-sourced).

## Package layout (app/src/com/petstore/opc)

| Package | Classes |
|---------|---------|
| `domain` | `WarehouseOrder`, `OrderStatus`, `ContactInfo`, `OrderLine`, `OrderStatusChange`, `SalesReport` (+`SalesBucket`), `ApprovalPolicy` |
| `service` | `FulfilmentService` (intake+auto-approve), `AdminService` (approve/deny/batch/sales), `ApprovalGateway`, `OrderStatusGateway` |
| `messaging` | `OrderListener` (PurchaseOrderQueue), `InvoiceListener` (InvoiceTopic) |
| `repository` | `OrderStore` (port) |
| `repository.jpa` | `JpaOrderStore` (adapter), `WarehouseOrderEntity`, `WarehouseLineEntity`, `ContactInfoEmbeddable`, `WarehouseOrderJpaRepository` (`SpringDataRepositories.java`) |
| `web` | `OrderProcessingApiController` |
| `security` | `SecurityConfig` |

## Build & test THIS module

**Not in `build-all.sh`** — build it on its own. Java 21 required.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd order-processing-service && mvn -q clean install   # installs client to ~/.m2, then app
```

`install` (not just `package`) because `app` depends on `order-processing-client` and admin-office-service
depends on it too. Tests: `AdminServiceTest` (batch approval + sales delegation, Mockito) and
`JpaOrderStoreSalesTest` (`@DataJpaTest`, real GROUP BY aggregation). Runtime store is in-memory H2
(`schema.sql`/`data.sql`); the broker is the shared Artemis in petstore-app-v1 — use `./run-all.sh`.

## Invariants — do not break

1. **Authoritative order store.** OPC is the single writer of order status. No other service persists
   order status; they react to its events. Do not add a second writer.
2. **`OrderStatus` = PENDING / APPROVED / DENIED / COMPLETED only.** Transitions live in
   `OrderStatus.canGoTo`: PENDING→{APPROVED,DENIED}, APPROVED→COMPLETED, DENIED/COMPLETED terminal.
   **`SHIPPED_PART` was removed on purpose (all-or-nothing fulfilment) — do NOT reintroduce it.** See DECISIONS.md.
3. **After-commit publish.** `ApprovalGateway` and `OrderStatusGateway` register a
   `TransactionSynchronization` and publish in `afterCommit`, so a rolled-back transaction never emits.
   Any new outbound event MUST follow this pattern.
4. **Idempotent JMS consumers** (JMS is at-least-once). `OrderListener` skips an order it already has;
   `InvoiceListener` no-ops if already COMPLETED. Keep new handlers idempotent.
5. **`ApprovalPolicy` thresholds:** auto-approve US `< 500`, JAPAN `< 50000`, else PENDING. Legacy-faithful
   (`PurchaseOrderMDB.canIApprove`). Change only with a parity note.
6. **`WarehouseOrder` is a 10-arg record:** `(orderId, userId, emailId, locale, totalPrice, status, lines,
   shipTo, billTo, created)`. Adding/removing a component means updating **every** `new WarehouseOrder(...)`
   call site (`FulfilmentService`, `OrderListener`, `JpaOrderStore`, both tests). No positional surprises.
7. **Domain is framework-free.** `domain/*` are POJO records/enum + one `@Component` policy — no JPA/Jackson.
   JPA lives only in `repository.jpa`. Persistence is always behind the `OrderStore` port.

## Events (see repo skill for the full contract)

| Direction | Destination | Event | Trigger |
|-----------|-------------|-------|---------|
| consume | `PurchaseOrderQueue` (queue) | `PurchaseOrderEvent` | checkout → persist + auto-approve/PENDING |
| consume | `InvoiceTopic` (topic) | `InvoiceEvent` | ship → COMPLETED (backorder leaves APPROVED) |
| publish | `ApprovedOrderQueue` (queue) | `OrderApprovedEvent` | on APPROVED → inventory fulfils |
| publish | `OrderStatusTopic` (topic) | `OrderStatusEvent` | on APPROVED/DENIED/COMPLETED → notification email |

## Client contract

`OrderProcessingClient` (in `client/`) forwards the acting admin's Bearer token; OPC verifies ADMIN itself
(RS256, `auth-client` public key — verify-only, no credential store here). Methods: `ordersByStatus`,
`getOrder`, `approve`, `deny`, `updateOrders` (atomic batch), `sales`. Endpoint paths and DTOs are the
single source of truth in `OrderProcessingEndpoints` / `OrderDtos` — keep them backward compatible.
