# Pet Store — High-Level Architecture

> Migrated Java Pet Store (J2EE 1.3 → **Spring Boot 3.3.5 / Java 21**).
> Eight runnable Spring Boot apps + one standalone ActiveMQ Artemis broker + two shared
> libraries. All synchronous cross-service traffic is HTTP/JSON via typed client SDKs;
> all asynchronous order-fulfilment traffic is JMS over the shared broker.

This document is the single high-level map. For per-module internals see each module's
`CLAUDE.md` and `docs/LLD.md`; for the "why" behind design choices see
[`../DECISIONS.md`](../DECISIONS.md).

---

## 1. Service map — clients imported + persistence

Each box is a deployable app. The bracketed list is the **client SDKs it imports to make
cross-service HTTP calls** (i.e. its synchronous dependencies). The persistence tag is what
that service owns: **SQL** = file-based H2 (survives restart), **none** = holds no database.

```
                     ┌───────────────────────────────────────────────────────────────┐
                     │                        BROWSER (shopper / admin / supplier)      │
                     └───────────────┬───────────────────────┬───────────────┬─────────┘
                                     │ :8080                  │ :8082         │ :8085
                                     ▼                        ▼               ▼
   ┌──────────────────────────────────────────┐   ┌───────────────────┐  ┌────────────────────────┐
   │ petstore-app-v1  (Storefront)   :8080     │   │ admin-office      │  │ inventory-service      │
   │ clients:[auth, catalog, customer]         │   │  -service  :8082  │  │              :8085     │
   │ embeds: cart-lib   uses: petstore-messaging│  │ clients:[auth, opc]│  │ clients:[auth]         │
   │ DB: none (publish-only — no order store)  │   │ DB: none (delegates)│ │ uses: petstore-messaging│
   └───┬───────────┬───────────┬───────────────┘   └───┬───────────┬───┘  │ DB: SQL (h2 inventory) │
       │auth       │catalog    │customer               │auth       │opc    └───────┬────────────────┘
       │:8086      │:8083      │:8081                   │:8086      │:8088          │auth :8086
       ▼           ▼           ▼                        ▼           ▼               ▼
 ┌───────────┐ ┌───────────┐ ┌────────────┐      ┌───────────┐ ┌────────────────────────┐
 │auth-service│ │catalog-    │ │customer-   │      │auth-service│ │ order-processing-service│
 │   :8086    │ │service     │ │service     │      │  (shared)  │ │  (OPC)          :8088   │
 │clients:[-] │ │  :8083     │ │  :8081     │      └───────────┘ │ clients:[auth, opc-dtos]│
 │DB: SQL     │ │clients:[-] │ │clients:[auth]│                  │ uses: petstore-messaging │
 │ (h2 auth)  │ │DB: SQL     │ │DB: SQL      │                   │ DB: SQL (h2 opc)         │
 └───────────┘ │ (h2 catalog)│ │ (h2 customer)│                  └────────────────────────┘
               └───────────┘ └──────┬──────┘
                                    │auth :8086
                                    ▼
                              ┌───────────┐          ┌────────────────────────┐
                              │auth-service│          │ notification-service   │
                              │  (shared)  │          │              :8087     │
                              └───────────┘          │ clients:[-]            │
                                                      │ uses: petstore-messaging│
                                                      │ DB: none (observer)    │
                                                      └────────────────────────┘
```

### 1.1 Client-import matrix (who calls whom, synchronously over HTTP)

| Service (port) | Imported client SDKs | Talks HTTP to | Persistence |
|---|---|---|---|
| **petstore-app-v1** (8080) | `[auth-client, catalog-service-client, customer-service-client]` + embeds `cart-lib` | auth :8086, catalog :8083, customer :8081 | **none** — publish-only, no order DB |
| **auth-service** (8086) | `[-]` (imports own `auth-client` for DTOs only) | — (leaf IdP) | **SQL** — file H2 `auth` (credentials/accounts) |
| **catalog-service** (8083) | `[-]` (imports own client for DTOs only) | — (leaf) | **SQL** — file H2 `catalog` (locale-split product data) |
| **customer-service** (8081) | `[auth-client]` + own client DTOs | auth :8086 | **SQL** — file H2 `customer` (PII/profile/card) |
| **order-processing-service** (8088) | `[auth-client, order-processing-client]` + `petstore-messaging` | auth :8086 | **SQL** — file H2 `opc` (**authoritative order store** + outbox) |
| **admin-office-service** (8082) | `[auth-client, order-processing-client]` | auth :8086, OPC :8088 | **none** — pure delegation console |
| **inventory-service** (8085) | `[auth-client]` + `petstore-messaging` | auth :8086 | **SQL** — file H2 `inventory` (stock + dedup ledger) |
| **notification-service** (8087) | `[-]` + `petstore-messaging` | — (JMS-only observer) | **none** — stateless (mailer logs) |

> **Note on "own client" imports.** `auth-service`, `catalog-service`, and
> `order-processing-service` list *their own* `*-client` artifact as a dependency. That is
> **not** a cross-service call — it reuses the client's wire DTO records so the server and its
> callers share one contract. Only `auth-client` in *other* services (storefront, customer,
> OPC, admin, inventory) and `order-processing-client` in *admin* are real outbound calls.

---

## 2. Messaging topology — one broker, 4 business destinations + 2 safety nets

All async traffic flows through **one** standalone ActiveMQ Artemis broker (`:61616`, hosted
as a container — see `docker-compose.yml`; started first by `run-all.sh`). Destination names
and the `MessagePublisher`/converter live in the shared **`petstore-messaging`** lib so no
service hardcodes a queue name.

**Queue** = point-to-point, exactly one consumer processes each message.
**Topic** = pub/sub, every subscribed consumer gets its own copy.

```
                         ┌──────────────────────────────────────────────────────┐
                         │        ActiveMQ Artemis broker  (:61616)              │
                         │                                                        │
  petstore-app-v1  ────▶ │  ▣ PurchaseOrderQueue   (queue)  ───▶  order-processing │
  OrderService.checkout  │                                        (OrderListener)  │
                         │                                                        │
  order-processing  ───▶ │  ▣ ApprovedOrderQueue   (queue)  ───▶  inventory-service│
  OutboxRelay(Approval)  │                                     (OrderApprovedListener)
                         │                                                        │
  inventory-service ───▶ │  ◈ InvoiceTopic         (topic)  ──┬─▶ order-processing │
  OrderApprovedListener  │                                    │   (InvoiceListener → COMPLETED)
                         │                                    └─▶ notification-svc │
                         │                                        (InvoiceNotificationListener → email)
                         │                                                        │
  order-processing  ───▶ │  ◈ OrderStatusTopic     (topic)  ───▶  notification-svc │
  OutboxRelay(Status)    │                                    (OrderStatusNotificationListener → email)
                         │                                                        │
                         │  ─ safety nets ─────────────────────────────────────  │
                         │  ▣ DLQ          (queue)  ───▶ notification (DlqListener, ERROR log)
                         │  ▣ ExpiryQueue  (queue)  ───▶ notification (DlqListener, ERROR log)
                         │    retry: ~1s → ~2s → 3rd failure ⇒ routed to DLQ      │
                         └──────────────────────────────────────────────────────┘
```

### 2.1 Destination → producer → consumer(s)

| Destination | Kind | Producer | Consumer(s) | Payload |
|---|---|---|---|---|
| **PurchaseOrderQueue** | queue | petstore-app-v1 (`OrderService.checkout`) | order-processing (`OrderListener`) | `PurchaseOrderEvent` |
| **ApprovedOrderQueue** | queue | order-processing (`OutboxRelay` ← `ApprovalGateway`) | inventory-service (`OrderApprovedListener`) | `OrderApprovedEvent` |
| **InvoiceTopic** | topic | inventory-service (`OrderApprovedListener`) | order-processing (`InvoiceListener` → COMPLETED) **and** notification (`InvoiceNotificationListener` → email) | `InvoiceEvent` |
| **OrderStatusTopic** | topic | order-processing (`OutboxRelay` ← `OrderStatusGateway`) | notification (`OrderStatusNotificationListener` → email) | `OrderStatusEvent` |
| **DLQ** | queue | broker (after 3 failed deliveries) | notification (`DlqListener`, ERROR log) | any (raw `jakarta.jms.Message`) |
| **ExpiryQueue** | queue | broker (expired messages) | notification (`DlqListener`, ERROR log) | any (raw `jakarta.jms.Message`) |

> **Why `InvoiceTopic` is a topic, not a queue:** the invoice fans out to **two** independent
> consumers — OPC (to flip the order to COMPLETED) and notification (to email the customer).
> A queue would deliver to only one. `OrderStatusTopic` is a topic for the same fan-out reason
> even though today it has a single subscriber.

### 2.2 Reliability model (post-hardening)

- **Producer side (OPC):** transactional **outbox** — the event is written to the `outbox`
  table in the same DB transaction as the state change, then `OutboxRelay` publishes it. A row
  that fails to publish is retried up to **3** attempts, then **parked** (WARN logged).
- **Broker side:** redelivery with exponential back-off (`~1s → ~2s`), **max 3 attempts**, then
  the message is routed to the **DLQ**. `notification-service`'s `DlqListener` turns any
  quarantined message into an operator-visible ERROR (it also owns `ExpiryQueue`).
- **Consumer side:** **idempotent** consumers (inventory keeps a durable `processed_event`
  ledger; OPC owns terminal state) so at-least-once redelivery can't double-apply.

---

## 3. End-to-end order flow (happy path)

```
 shopper                storefront        broker            OPC              inventory        notification
   │  browse/cart/checkout │                │               │                  │                  │
   │──────────────────────▶│                │               │                  │                  │
   │                        │ PurchaseOrder  │               │                  │                  │
   │                        │───────────────▶│ (queue)       │                  │                  │
   │                        │                │──────────────▶│ persist PENDING  │                  │
   │  admin approves (:8082 → OPC :8088)     │               │ APPROVED         │                  │
   │                        │                │  ApprovedOrder │──(outbox)───────▶│ (queue)          │
   │                        │                │◀──────────────│                  │──▶ reserve stock  │
   │                        │                │  InvoiceTopic  │                  │  all-or-nothing   │
   │                        │                │◀───────────────────────────────── │ (topic, fan-out) │
   │                        │                │──────────────▶│ COMPLETED        │                  │
   │                        │                │───────────────────────────────────────────────────▶│ email
   │                        │  OrderStatusTopic (each transition, via outbox)   │                  │
   │                        │                │◀──────────────│                  │                  │
   │                        │                │───────────────────────────────────────────────────▶│ email
```

**Legend:** `▣` queue (point-to-point) · `◈` topic (pub/sub) · `[client]` imported client SDK ·
`SQL` file-based H2 (durable) · `none` no database.
