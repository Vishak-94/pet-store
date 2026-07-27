# petstore-messaging — shared JMS contract library

> Shared library for the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration (in-process jar, not a service). See the [repo README](../README.md).

**Package:** `com.petstore.messaging` · **Legacy origin:** the Pet Store JMS async layer (e.g. `InvoiceTopic` pub/sub, `MailOrderApprovalMDB` / `MailCompletedOrderMDB`, supplier `processPendingPO`).

## What it provides

- The **single source of truth for the JMS wire contract** — destination names, the event envelope + schema, and `_type` id routing — so producers and consumers can never drift apart. **No port: this is a library, not a service.**
- **Destinations**: the one registry of destination names + kind — `PurchaseOrderQueue`, `ApprovedOrderQueue` (queues) and `InvoiceTopic`, `OrderStatusTopic`, `RestockTopic` (topics).
- The **event envelope**: `EventMeta` (`eventId`, `type`, `occurredAt`, `correlationId`) plus the `Events` factory and an MDC-backed `Correlation` carrier that bridges HTTP ↔ JMS so one checkout traces end-to-end.
- The **event records** (`events/`): `PurchaseOrderEvent`, `OrderApprovedEvent`, `InvoiceEvent`, `OrderStatusEvent`, `RestockEvent`.
- `MessagingConfig` (`@Configuration @EnableJms`): the `TYPE_IDS` `_type`→class map, the Jackson JSON message converter, and the `queueFactory` (point-to-point) / `topicFactory` (JMS 2.0 durable + shared pub/sub) listener factories.
- `MessagePublisher`: a thin, transport-only publisher — `publish(Destination, event)` stamps `_type` and routes to the queue/topic template. No business logic and no transactional coupling live here.

## Maven coordinates

```
com.petstore:petstore-messaging:1.0.0
```

## Key types

| Type | Role |
|------|------|
| `Destinations` | The ONE registry of destination names + kinds (queues vs topics), as compile-time `String` constants + `Destination` objects. |
| `Destination` | `record(name, boolean topic)` with `queue()` / `topic()` factories; encodes point-to-point vs pub/sub semantics. |
| `EventMeta` | Envelope record: `eventId` (dedup key), `type`, `occurredAt`, `correlationId`. |
| `Events` | `EventMeta` factory: `meta(type, correlationId)` / `meta(type)` (the one-arg form reads the ambient `Correlation` MDC). |
| `Correlation` | MDC-backed correlation-id carrier (`current` / `set` / `clear`); the HTTP↔JMS bridge (MDC key `correlationId`). |
| `MessagingConfig` | `@Configuration @EnableJms`: `TYPE_IDS` map, `jacksonJmsMessageConverter`, `queueFactory`, `topicFactory`. |
| `MessagePublisher` | `@Component`: `publish(Destination, event)` — stamps `_type`, sends via queue/topic template. |
| `events/PurchaseOrderEvent` | `TYPE="PurchaseOrder"`; nested records `Line` + `ContactInfo`. |
| `events/OrderApprovedEvent` | `TYPE="OrderApproved"`; nested record `Line`. |
| `events/InvoiceEvent` | `TYPE="Invoice"`. |
| `events/OrderStatusEvent` | `TYPE="OrderStatus"`. |
| `events/RestockEvent` | `TYPE="Restock"` (`itemId`, `quantityAdded`) — RestockTopic re-drive of backordered orders. |

## Used by

- **Every service that produces or consumes an event** imports `petstore-messaging:1.0.0`: `petstore-app-v1`, `order-processing-service`, `inventory-service`, and `notification-service`.
- Flow (full table in `docs/LLD.md`): storefront checkout → `PurchaseOrderQueue` → order-processing → `ApprovedOrderQueue` → inventory → `InvoiceTopic` → {order-processing, notification}; order-processing → `OrderStatusTopic` → notification; inventory → `RestockTopic` → order-processing.

## Build

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # Java 21 required
cd petstore-messaging
mvn -q clean install    # build + tests, then PUBLISH the jar to ~/.m2
```

It is a **library**: use `install` (not just `package`) — it must be installed to `~/.m2` **before** any dependent service builds. Changing this library means **rebuilding every dependent** (a stale `~/.m2` copy is the usual cause of "producer and consumer disagree on JSON"); `./build-all.sh` installs it first for exactly this reason. Flat layout: sources under `src/`, tests under `test/`.

## See also

- [`CLAUDE.md`](CLAUDE.md) — invariants (TYPE_IDS, additive fields, queue vs topic, durable+shared subs), boundaries
- [`docs/LLD.md`](docs/LLD.md) — class + destinations table + sequence design
- [`../DECISIONS.md`](../DECISIONS.md) — ADRs / architecture rationale
- [`../docs/PARITY_AUDIT.md`](../docs/PARITY_AUDIT.md) — legacy behavioural baseline
