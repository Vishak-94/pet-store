# OPC MongoDB Schema — order aggregate (as-built)

> The OPC (order-processing-service) order + outbox store is available on MongoDB as a
> **profile-selectable** alternative to file-based H2. This documents the **document schema,
> every attribute, and indexes** as actually implemented in `com.petstore.opc.repository.mongo`:
> `WarehouseOrderDocument` (embedded `lines[]` + `shipTo`/`billTo`), `OutboxDocument`,
> `MongoOrderStore` / `MongoOutboxStore` (the `OrderStore`/`OutboxStore` port adapters),
> and `MongoSchemaConfig` (validators + indexes). `EventMeta` is the event envelope (context).
>
> **How to select it:** run the service with the `mongo` Spring profile
> (`SPRING_PROFILES_ACTIVE=mongo`). The **default** profile (no profile) keeps H2 + JPA + Flyway
> unchanged; both sit behind the same ports, and exactly one store is active per profile (the JPA
> adapters are `@Profile("!mongo")`, the Mongo adapters `@Profile("mongo")`). See `../DECISIONS.md`.
>
> Runtime: MongoDB 7.0 as a **single-node replica set `rs0`** (needed for multi-document
> transactions — the outbox insert + order write commit atomically — and change streams).
> Local: `mongodb://localhost:27018/petstore?directConnection=true` (see repo `docker-compose.yml`,
> service `mongo` on host port 27018 + browsable via `mongo-express` on :8971).
> Database name: **`petstore`**.

---

## Why the SQL model collapses into documents

The current SQL store shreds one order across **three tables** + two embedded blocks:
`wh_order` (the order + status), `wh_line` (one row per line, joined by `order_id`), and the
`ship_*` / `bill_*` embedded columns. In MongoDB the whole aggregate is **one document** — the
`lines` array and the `shipTo`/`billTo` subdocuments are embedded, so there are no joins and a
status change is a single-document atomic update.

Three collections in total:

| Collection | Replaces (SQL) | Purpose |
|---|---|---|
| `orders` | `wh_order` + `wh_line` + embedded contacts | the authoritative order aggregate |
| `outbox` | `outbox` | transactional outbox (outbound events) |
| `processed_events` | `processed_event` | idempotency ledger (dedup) — *inventory-owned in the code today; listed for the full picture* |

---

## 1. `orders` collection

One document = one complete order. `orderId` becomes `_id` (free unique index + natural dedup
for at-least-once redelivery: a re-consumed `PurchaseOrderEvent` `replaceOne`s the same `_id`).

```jsonc
{
  "_id": "1001",                     // orderId
  "userId": "uid-1",
  "emailId": "j2ee@petstore.com",
  "locale": "en_US",
  "currency": "USD",
  "totalPrice": 33.00,
  "status": "PENDING",
  "version": 0,
  "created": ISODate("2026-07-27T10:15:00Z"),
  "lines": [
    { "itemId": "EST-1", "productId": "FI-SW-01", "categoryId": "FISH",
      "quantity": 2, "unitPrice": 16.50 }
  ],
  "shipTo": {
    "familyName": "Coyote", "givenName": "Wile",
    "streetName1": "1 Desert Rd", "streetName2": null,
    "city": "Tucson", "state": "AZ", "zipCode": "85701",
    "country": "USA", "telephone": "5205551234", "email": "wile@acme.test"
  },
  "billTo": { /* same shape as shipTo */ }
}
```

### 1.1 Top-level fields

| Field | BSON type | Source (SQL / code) | Nullable | Detail |
|---|---|---|---|---|
| `_id` | String | `wh_order.order_id` (`@Id`) | no | The `orderId`. Server-minted snowflake from checkout. Primary key ⇒ unique index automatic; doubles as the dedup key (`OrderListener` skips an id it already has). |
| `userId` | String | `user_id` | no | Stable customer id (customer-service key), distinct from the login username. |
| `emailId` | String | `email_id` | no | Shopper email captured at checkout. |
| `locale` | String | `locale` | no | Order locale, e.g. `en_US` (display/i18n). Storefront hardcodes `en_US` today (legacy quirk). No longer drives the auto-approve threshold — see `currency`. |
| `currency` | String | `currency` | yes (→`USD`) | ISO 4217 code the total is denominated in (`USD`/`JPY`). OPC's `ApprovalPolicy` keys the auto-approve threshold on **this**, not `locale` (the legacy rule always meant money, per its own "stub for converting currency" comment). Null/blank → treated as `USD`; historical rows backfill to `USD` (see Flyway `V4`). |
| `totalPrice` | Double | `total_price` | no | Σ(`unitPrice`·`quantity`), computed at checkout and frozen on the order. |
| `status` | String (enum) | `status` (`@Enumerated(STRING)`) | no | One of **`PENDING` / `APPROVED` / `DENIED` / `COMPLETED`** only. Transitions enforced by `OrderStatus.canGoTo`: PENDING→{APPROVED,DENIED}, APPROVED→COMPLETED, DENIED & COMPLETED terminal. Store as the string (not an int) so the UI is readable and it matches `@Enumerated(STRING)`. |
| `version` | Long (NumberLong) | `version` (`@Version`) | no | Optimistic-lock token. Preserves the approve+deny race guard — see §1.4. Starts at 0. |
| `created` | Date | `created` | no | Order-received timestamp (legacy `poDate`). Drives the admin most-recent-first list and date-range sales. Store as BSON `Date` (UTC), the mapping of Java `Instant`. |
| `lines` | Array<subdoc> | `wh_line` rows | no (≥1) | Embedded line items — see §1.2. |
| `shipTo` | Subdoc | `ship_*` embedded | **yes** | Ship-to contact — see §1.3. Nullable: the JSON `/api/checkout` path doesn't collect it. |
| `billTo` | Subdoc | `bill_*` embedded | **yes** | Bill-to contact — same shape as `shipTo`. |

### 1.2 `lines[]` embedded subdocument

Faithful to `WarehouseLineEntity`. **Note:** the SQL `wh_line.id` surrogate key (`@GeneratedValue`)
is **dropped** — it existed only as a join/PK artifact; embedded array elements need no id.

| Field | BSON type | Source | Nullable | Detail |
|---|---|---|---|---|
| `itemId` | String | `item_id` | no | Catalog item id, e.g. `EST-1`. |
| `productId` | String | `product_id` | no | Parent product id, e.g. `FI-SW-01`. |
| `categoryId` | String | `category_id` | no | Category, e.g. `FISH`. Used by the sales aggregation grouping. |
| `quantity` | Int (Int32) | `quantity` | no | Units ordered. |
| `unitPrice` | Double | `unit_price` | no | Price per unit, snapshotted at checkout (won't drift with catalog price changes). |

### 1.3 `shipTo` / `billTo` embedded subdocument

Faithful to `ContactInfoEmbeddable` (the flattened legacy `ContactInfo` + `Address`). Same shape
for both. Every field is a String; **`streetName2` is the only optional one** (all others were
required at checkout — pinned by the H7 required-field set).

| Field | BSON type | Nullable | Detail |
|---|---|---|---|
| `familyName` | String | no | Surname. |
| `givenName` | String | no | First name. |
| `streetName1` | String | no | Address line 1. |
| `streetName2` | String | **yes** | Address line 2 — optional (legacy); blank normalises to `null`. |
| `city` | String | no | |
| `state` | String | no | |
| `zipCode` | String | no | String, not a number — preserves leading zeros. |
| `country` | String | no | |
| `telephone` | String | no | String — preserves `+`, leading zeros, formatting. |
| `email` | String | no | Contact email (may differ from top-level `emailId`). |

### 1.4 Preserving the `@Version` optimistic lock in MongoDB

The SQL `@Version` guards the approve+deny race (two admins act on one PENDING order). MongoDB
has no `@Version` at the driver level, so it becomes a **version-guarded conditional update**:

```jsonc
db.orders.updateOne(
  { _id: "1001", version: 5 },                        // only if nobody else moved it
  { $set: { status: "APPROVED" }, $inc: { version: 1 } }
)
// result.matchedCount === 0  → someone else won the race
//   → throw the OptimisticLockingFailureException equivalent → 409 Conflict (unchanged contract)
```

> Spring Data MongoDB supports `@Version` on the mapped class and applies exactly this guard
> automatically on `save()` — so the current 409 behaviour is preserved with no manual filter.

### 1.5 Indexes on `orders`

| Index | Backs | Query |
|---|---|---|
| `_id` (automatic) | `findById`, `statusOf`, `updateStatus`, dedup | key lookups |
| `{ status: 1 }` | `orderIdsByStatus` | admin "orders awaiting approval" scans |
| `{ created: -1 }` | `findAllByCreatedDesc` + sales range | admin overview, date-range sales |

---

## 2. `outbox` collection (transactional outbox)

Stays a **separate collection** (not embedded in the order) — faithful to `OutboxEntity`. An
outbound event is inserted here **in the same transaction** as the order-status write, so event
and state commit or roll back together. A relay publishes unsent docs and stamps `publishedAt`.

```jsonc
{
  "_id": ObjectId("665f1c…"),        // Mongo-minted; surfaced to the port as its hex String
  "destination": "ApprovedOrderQueue",
  "topic": false,
  "eventType": "OrderApproved",
  "payload": "{\"meta\":{...},\"orderId\":\"1001\",...}",
  "orderId": "1001",
  "createdAt": ISODate("2026-07-27T10:16:00Z"),
  "publishedAt": null,
  "attempts": 0
}
```

| Field | BSON type | Source | Nullable | Detail |
|---|---|---|---|---|
| `_id` | ObjectId | `outbox.id` (`@GeneratedValue IDENTITY`) | no | Mongo's native `ObjectId` replaces the auto-increment Long — no sequence needed and it's monotonic-ish for insertion order. The `OutboxStore` port id is a **`String`** (`OutboxMessage.id`, `markPublished(String)`, `recordFailure(String)`): `MongoOutboxStore` maps the `ObjectId` to its hex string, and `JpaOutboxStore` maps the Long via `String.valueOf` / `Long.parseLong`, so the relay is store-agnostic. |
| `destination` | String | `destination` | no | JMS destination name, e.g. `ApprovedOrderQueue`, `OrderStatusTopic`. |
| `topic` | Boolean | `is_topic` | no | `true` = topic (pub/sub), `false` = queue (point-to-point). Tells the relay which template to use. |
| `eventType` | String | `event_type` | no | Logical event type / JMS `_type` id, e.g. `OrderApproved`, `OrderStatus`. |
| `payload` | String | `payload` (`@Lob` JSON) | no | The event serialized as JSON, stored **as a String** (as-built) — byte-identical to what the JMS converter sends, so the frozen payload + `eventId` are preserved exactly (the parity-safe choice over a re-serialized nested Object). |
| `orderId` | String | `order_id` | no | Correlates the outbox row to its order (for tracing/debugging). |
| `createdAt` | Date | `created_at` | no | When the row was enqueued. |
| `publishedAt` | Date | `published_at` | **yes** | `null` until the relay publishes it; set = delivered. Relay queries `{ publishedAt: null }`. |
| `attempts` | Int (Int32) | `attempts` | no | Publish attempts; a row parks as a poison message at `opc.outbox.max-attempts` (3). |

### 2.1 Indexes + the change-stream upgrade

| Index | Query |
|---|---|
| `ix_outbox_unpublished` `{ publishedAt: 1, attempts: 1 }` | relay drains unsent rows below the park cap (created by `MongoSchemaConfig`) |

**As-built: the relay still polls.** `OutboxRelay` runs on its `@Scheduled` poll of
`{ publishedAt: null, attempts < max }` unchanged across both stores — the same code path serves H2
and Mongo. What Mongo *adds* is the atomicity guarantee: the insert of the outbox doc + the `orders`
status update run in **one multi-document transaction** (via `MongoTransactionManager`, `@Profile("mongo")`),
which is the reason the single-node replica set `rs0` is required — everything else is single-document atomic.

The **future** upgrade (not implemented): drive the relay off a **change stream on the `outbox`
collection** so it reacts to each insert with no poll interval, same at-least-once guarantee. Left as a
follow-up — the poller is store-agnostic and already correct, so this is a latency optimisation, not a
correctness fix.

---

## 3. `processed_events` collection (idempotency ledger)

Faithful to inventory's `ProcessedEventEntity`. *In the code today this ledger is
**inventory-owned**, not OPC — listed here for the complete picture if inventory also moves to
Mongo.* One document per fully-applied event; the `_id` **is** the `eventId`, so a duplicate
insert fails the unique key and the replay is skipped.

```jsonc
{ "_id": "9f3c2a7e-…" }   // EventMeta.eventId of a fully-processed event
```

| Field | BSON type | Source | Nullable | Detail |
|---|---|---|---|---|
| `_id` | String | `processed_event.event_id` | no | The `EventMeta.eventId`. PK ⇒ a second insert of the same id throws a duplicate-key error — the dedup backstop even if the read-then-check races. No other fields (existence *is* the fact). |

> OPC's own idempotency doesn't need this ledger: it dedups on the order `_id` (`OrderListener`
> skips a known order) and on terminal state (`InvoiceListener` no-ops if already COMPLETED). The
> ledger matters for inventory, whose apply (decrement stock) isn't naturally idempotent.

---

## 4. The event envelope (`EventMeta`) — context

Not a standalone collection; it's the envelope embedded inside every event **payload** (in the
`outbox.payload` JSON, and on the wire). Documented so the payload's shape is unambiguous:

| Field | Type | Detail |
|---|---|---|
| `eventId` | String | Unique per message — the dedup key (JMS is at-least-once). |
| `type` | String | Logical event type; also the JMS `_type` id. |
| `occurredAt` | String | ISO-8601 instant the event happened. |
| `correlationId` | String | Ties the event to the originating request/trace (from `X-Correlation-Id` / MDC). |

---

## 5. Parity checklist (verified after the move)

Boxes marked ☑ are pinned by a passing Mongo integration test (`repository/mongo/*Test`, run against a
Testcontainers `mongo:7.0` replica set); ✓ items hold because the enforcing code is store-agnostic
(domain/service layer, unchanged) and already covered by the profile-neutral suite.

- [x] `status` set stays exactly **PENDING / APPROVED / DENIED / COMPLETED** — no `SHIPPED_PART`.
      *(`$jsonSchema` enum in `MongoSchemaConfig`; `MongoSchemaConfigTest` rejects `SHIPPED_PART`.)*
- [x] Transition rules match `OrderStatus.canGoTo`. *(enforced in the store-agnostic domain enum;
      `MongoOrderStoreVersionTest.updateStatus_appliesTransition` exercises it through the Mongo store.)*
- [x] Approve+deny race still yields **409** on the loser (via `@Version` conditional update).
      *(`MongoOrderStoreVersionTest.staleWrite_...throwsOptimisticLock`.)*
- [x] `OrderListener` idempotent (dedup on order `_id`); `InvoiceListener` no-op if COMPLETED.
      *(listener logic unchanged; `_id` = `orderId` gives natural dedup, `save` = `replaceOne` on the same key.)*
- [x] Backorder (`InvoiceEvent shipped=false`) leaves the order **APPROVED** (all-or-nothing).
      *(decided in `InvoiceListener`, store-agnostic — no Mongo-specific path.)*
- [x] Sales aggregation reproduces the SQL `GROUP BY`: group-by-category when `categoryId` is null,
      else group-by-item within that category; `revenue = Σ qty·unitPrice`, `quantity = Σ qty`.
      *(`MongoOrderStore` `$match`→`$unwind`→`$group` pipeline; `MongoOrderStoreSalesTest` asserts the
      same figures as `JpaOrderStoreSalesTest`.)*
- [x] Outbound events still go through the **outbox** (never a direct publish).
      *(`MongoOutboxStore` implements the same `OutboxStore` port; relay/gateways unchanged.)*
- [x] `totalPrice` / line `unitPrice` frozen at checkout (no drift). *(mapped verbatim in
      `MongoOrderStore` to/from `WarehouseOrderDocument`; no recompute on read.)*
```
