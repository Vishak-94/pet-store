---
name: order-processing-service
description: >-
  Conventions for the Order Processing Center (OPC, :8088, com.petstore.opc) — the AUTHORITATIVE order
  store + workflow of the migrated Pet Store. Use when working on order intake, approval/deny, the
  PENDING/APPROVED/DENIED/COMPLETED lifecycle, auto-approval thresholds, the two JMS listeners
  (PurchaseOrderQueue intake, InvoiceTopic completion), the after-commit ApprovalGateway/OrderStatusGateway,
  sales aggregation (getChartInfo / GET /api/sales), batch approval (POST /api/orders/approvals), the
  WarehouseOrder record, the OrderStore JPA adapter (wh_order/wh_line, ship/bill/created), or the
  order-processing-client SDK that admin-office-service calls. Triggers: OPC, order-processing-service,
  WarehouseOrder, FulfilmentService, AdminService, OrderStatus, ApprovalPolicy, OrderListener,
  InvoiceListener, ApprovedOrderQueue, OrderStatusTopic, sales report, batch approval.
---

# order-processing-service — per-app skill

The OPC (legacy `opc.ear`), port **8088**, package `com.petstore.opc`. It is the **single authoritative
writer of order status** for the whole fleet. First read the repo skill `petstore-dev`
(`../../../../.claude/skills/petstore-dev/SKILL.md`) for the module map, JMS contract, and hexagonal rules —
this skill only adds OPC-specific conventions. See also [`../../../CLAUDE.md`](../../../CLAUDE.md) and the
design detail in [`../../../docs/LLD.md`](../../../docs/LLD.md).

Build/test this module on its own (it is NOT in `build-all.sh`):
`cd order-processing-service && mvn -q clean install` (Java 21).

## Layering here (domain → port → adapter)

- **`domain/`** is framework-free: `WarehouseOrder`, `OrderLine`, `ContactInfo`, `OrderStatusChange`,
  `SalesReport`/`SalesBucket` are records; `OrderStatus` is an enum owning `canGoTo`; `ApprovalPolicy` is the
  one `@Component` (pure decision logic, no persistence). Never add JPA/Jackson/Spring-web annotations here.
- **`repository.OrderStore`** is the port. Services depend on it, never on `EntityManager`/`JpaRepository`.
- **`repository.jpa`** holds the adapter (`JpaOrderStore`), the `@Entity`/`@Embeddable` classes, and the
  Spring Data interface. `WarehouseOrderEntity`/`WarehouseLineEntity`/`ContactInfoEmbeddable` are
  package-private and map to/from the domain records in `JpaOrderStore` — keep the two-way mapping in sync.

`WarehouseOrder` is a **10-arg positional record** `(orderId, userId, emailId, locale, totalPrice, status,
lines, shipTo, billTo, created)`. If you change its shape, update EVERY `new WarehouseOrder(...)`:
`FulfilmentService`, `OrderListener`, `JpaOrderStore.toDomain`, `AdminServiceTest`, `JpaOrderStoreSalesTest`.

## Adding a status transition safely

`OrderStatus` is the closed set **PENDING / APPROVED / DENIED / COMPLETED**. Transitions are declared once
in the `ALLOWED` map and gated by `canGoTo`. To add/change one:

1. Edit the `ALLOWED` map in `OrderStatus`; every writer already guards with `current.canGoTo(target)`
   (`AdminService.applyStatusChange`, `InvoiceListener`), so illegal transitions throw / no-op automatically.
2. If a status should email the customer, publish `OrderStatusEvent` via `OrderStatusGateway.announce` —
   do not publish inline.
3. **Do NOT reintroduce `SHIPPED_PART`** — it was removed on purpose (all-or-nothing fulfilment). Check
   `../../../../DECISIONS.md` before "restoring" any legacy state.
4. Add/extend a test in `AdminServiceTest` (transition + gateway calls) — pin behaviour, don't disable tests.

## The after-commit gateway pattern

Outbound events are published **after the DB transaction commits**, never inline. Both `ApprovalGateway`
(`dispatchForFulfilment` → `ApprovedOrderQueue`) and `OrderStatusGateway` (`announce` → `OrderStatusTopic`)
do the same thing: if `TransactionSynchronizationManager.isSynchronizationActive()`, register a
`TransactionSynchronization` whose `afterCommit()` calls `publisher.publish(...)`; otherwise send inline.
This guarantees a rolled-back approval (e.g. one bad entry in a batch) emits nothing. Any NEW outbound event
MUST copy this pattern — put the publish behind a gateway, register after-commit, use the shared
`MessagePublisher` + `Destinations` from `petstore-messaging`. Build events with
`Events.meta(SomeEvent.TYPE)` so the envelope + type-id stay correct.

## The two JMS listeners

- **`OrderListener`** — `@JmsListener(destination = "PurchaseOrderQueue", containerFactory = "queueFactory")`.
  Queue = exactly one instance handles each order. Maps `PurchaseOrderEvent` (incl. `shipTo`/`billTo`
  contact info and `meta.occurredAt`→`created`) into a `WarehouseOrder[PENDING]` and calls
  `FulfilmentService.receiveOrder`, which dedupes on `findById` (idempotent) and auto-approves via
  `ApprovalPolicy` (US<500, JAPAN<50000).
- **`InvoiceListener`** — `@JmsListener(destination = "InvoiceTopic", containerFactory = "topicFactory")`,
  `@Transactional`. Topic = one of several subscribers (notification-service also gets it). `shipped` +
  `canGoTo(COMPLETED)` → COMPLETED (+announce); already COMPLETED → no-op; not shipped → stays APPROVED.

Keep both idempotent — JMS is at-least-once. Use the `queueFactory`/`topicFactory` from the shared
`MessagingConfig`; never hand-roll JMS config.

## Adding an aggregation query

Sales aggregation flows controller → `AdminService.salesReport` → `OrderStore.aggregateSales` →
`WarehouseOrderJpaRepository`. To add a new aggregation:

1. Add a `@Query` (JPQL) to `WarehouseOrderJpaRepository` returning `List<Object[]>` rows. Follow the
   existing `aggregateByCategory`/`aggregateByItem` shape: `[key, SUM(qty*unitPrice), SUM(qty)]`, filtered on
   `o.created BETWEEN :start AND :end`, joined to `o.lines`.
2. Map rows to a domain record in `JpaOrderStore` (guard nulls, cast via `Number`). Prefer a
   framework-free result record like `SalesReport`/`SalesBucket`.
3. Expose through the `OrderStore` port (new method) so services stay off the repository.
4. Cover it with a `@DataJpaTest` like `JpaOrderStoreSalesTest` (real GROUP BY, range boundaries).

## Client / DTO contract (for admin-office-service to call)

The `client` module (`order-processing-client`) is the single source of the HTTP contract:
`OrderProcessingEndpoints` (path constants), `OrderDtos` (wire records), `OrderProcessingClient` (typed
`RestClient`). The server controller imports these DTOs/constants so server and callers can't drift.

- Admin-office calls `OrderProcessingClient.{ordersByStatus, getOrder, approve, deny, updateOrders, sales}`,
  forwarding the acting admin's Bearer token; OPC verifies ADMIN itself (verify-only, `auth-client`).
- Batch approval: build an `OrderApprovalDto(List<OrderStatusChangeDto>)` and call `updateOrders` →
  `POST /api/orders/approvals` (atomic, all-or-nothing).
- **Keep DTOs and endpoint constants backward compatible.** When you add a field, make it nullable; when you
  add an endpoint, add the constant + a client method + the controller mapping together, and rebuild the
  client (`mvn -q clean install`) so dependents pick it up.
