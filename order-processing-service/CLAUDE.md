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
  then **auto-approves** under the currency threshold or leaves it **PENDING** for a human admin.
- Consumes **`InvoiceTopic`** (`InvoiceEvent`, inventory ship) → moves the order to **COMPLETED**.
- Consumes **`RestockTopic`** (`RestockEvent`, inventory restock) → **re-drives every APPROVED (backordered)
  order** oldest-first back through fulfilment (`RestockListener` → `AdminService.redriveApprovedForFulfilment`
  → `ApprovalGateway` outbox). The migrated form of the legacy `processPendingPO`-on-restock (PARITY_AUDIT H2).
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
| `service` | `FulfilmentService` (intake+auto-approve), `AdminService` (approve/deny/batch/sales + `redriveApprovedForFulfilment` on restock), `ApprovalGateway`, `OrderStatusGateway`, `OutboxWriter` (enqueue in-txn), `OutboxRelay` (`@Scheduled` publisher) |
| `messaging` | `OrderListener` (PurchaseOrderQueue), `InvoiceListener` (InvoiceTopic), `RestockListener` (RestockTopic → re-drive APPROVED backorders) |
| `repository` | `OrderStore` (port), `OutboxStore` (port), `OutboxMessage` (port DTO) |
| `repository.jpa` | **default profile** — `JpaOrderStore` + `JpaOutboxStore` (adapters, `@Profile("!mongo")`), `WarehouseOrderEntity`, `WarehouseLineEntity`, `ContactInfoEmbeddable`, `OutboxEntity`, `WarehouseOrderJpaRepository` + `OutboxJpaRepository` (`SpringDataRepositories.java`) |
| `repository.mongo` | **`mongo` profile** — `MongoOrderStore` + `MongoOutboxStore` (adapters, `@Profile("mongo")`), `WarehouseOrderDocument` (embedded `lines[]`/`shipTo`/`billTo`), `OutboxDocument`, `WarehouseOrderMongoRepository` + `OutboxMongoRepository` (`MongoRepositories.java`), `MongoSchemaConfig` (`$jsonSchema` validators + indexes), `MongoTransactionConfig` (`MongoTransactionManager`), `MongoSchema` (collection/field/index-name constants — single source of truth; status enum derived from `OrderStatus`) |
| `web` | `OrderProcessingApiController` |
| `security` | `SecurityConfig` |

## Build & test THIS module

**Not in `build-all.sh`** — build it on its own. Java 21 required.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd order-processing-service && mvn -q clean install   # installs client to ~/.m2, then app
```

`install` (not just `package`) because `app` depends on `order-processing-client` and admin-office-service
depends on it too. Tests: `AdminServiceTest` (batch approval + sales delegation, Mockito),
`JpaOrderStoreSalesTest` (`@DataJpaTest`, real GROUP BY aggregation), and the outbox trio —
`OutboxWriterTest` (enqueue stamps dest/type + round-trippable JSON), `OutboxRelayTest`
(deserialize → publish → mark/retry, poison row doesn't block the batch), `JpaOutboxStoreTest`
(`@DataJpaTest`: enqueue/drain/mark/park). The broker is the shared Artemis in petstore-app-v1 — use `./run-all.sh`.

### Persistence is profile-selectable: `h2` (default) | `mongo`

Both stores sit behind the `OrderStore`/`OutboxStore` ports; **exactly one is active per profile**
(JPA adapters `@Profile("!mongo")`, Mongo adapters `@Profile("mongo")`). Both starters stay on the
classpath — each profile's `application.yml` document excludes the *other's* autoconfig so only one
tries to connect. Full doc: [`../docs/MONGODB_SCHEMA.md`](../docs/MONGODB_SCHEMA.md) (as-built); rationale: `../DECISIONS.md`.

- **Default (H2)** — file-based H2 (`jdbc:h2:file:./data/opc`, overridable via `OPC_DB_PATH`) so orders
  survive restarts; schema owned by **Flyway** under `resources/db/migration`
  (`V1__init_order_schema.sql`, `V2__outbox.sql`), `ddl-auto: none`. Nothing to run — this is the default.
- **Mongo** — `SPRING_PROFILES_ACTIVE=mongo`, MongoDB 7.0 single-node replica set `rs0` on
  `mongodb://localhost:27018/petstore?directConnection=true` (overridable via `OPC_MONGODB_URI`);
  start it with the repo `docker-compose.yml` (service `mongo`, browsable via `mongo-express` on :8971).
  `MongoSchemaConfig` applies the `$jsonSchema` validators + indexes on `ApplicationReadyEvent`;
  `MongoTransactionManager` makes the order-write + outbox-enqueue commit atomically (why `rs0` is required).

**Mongo integration tests** (`repository/mongo/*Test`, 20 tests) run against a **Testcontainers `mongo:7.0`**:
`MongoOrderStoreSalesTest` (GROUP BY parity), `MongoOrderStoreVersionTest` (`@Version` 409 conflict),
`MongoOutboxStoreTest` (drain/mark/park), `MongoSchemaConfigTest` (validator rejects bad status/empty lines +
index existence), and `MongoOrderStoreQueryTest` (the `orderIdsByStatus`/`findAllByCreatedDesc` admin queries,
full contact round-trip, and the empty/missing negative paths). They are the one scoped exception to the
hermetic-no-container rule — `MongoTestBase` starts a **single shared container** for the whole package (a
manual static singleton, not one-per-class, to dodge a Colima multi-container port-forwarding flake) and guards
every test with `assumeTrue(dockerAvailable)`, so a plain `mvn clean install` with no reachable Docker **skips**
all 20 and stays green. To run them on Colima:

```bash
DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" \
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
mvn -pl app test -Dtest='Mongo*Test' -DargLine="-Dapi.version=1.44"
```

(`api.version` pins the docker-java client so a newer engine doesn't reject the default; the socket
override + Ryuk-disabled are the standard Colima bind-mount workaround.)

## Invariants — do not break

1. **Authoritative order store.** OPC is the single writer of order status. No other service persists
   order status; they react to its events. Do not add a second writer.
2. **`OrderStatus` = PENDING / APPROVED / DENIED / COMPLETED only.** Transitions live in
   `OrderStatus.canGoTo`: PENDING→{APPROVED,DENIED}, APPROVED→COMPLETED, DENIED/COMPLETED terminal.
   **`SHIPPED_PART` was removed on purpose (all-or-nothing fulfilment) — do NOT reintroduce it.** See DECISIONS.md.
3. **Transactional outbox for outbound events.** `ApprovalGateway` and `OrderStatusGateway` do
   NOT publish to JMS directly. They append the event to the `outbox` table via `OutboxWriter`
   **inside the business transaction**, so the event row and the order-status write commit or
   roll back atomically — a rolled-back transaction never emits, and a committed one always
   eventually does (closing the crash window of the old after-commit publish). `OutboxRelay`
   (`@Scheduled`, enabled by `@EnableScheduling` on the app) polls unsent rows and publishes
   them via the shared `MessagePublisher`. Delivery is **at-least-once** (a crash between
   broker-send and the `published_at` stamp re-sends), which is safe because the frozen payload
   carries a fixed `EventMeta.eventId` and consumers are idempotent (invariant #4); rows that
   keep failing park at `opc.outbox.max-attempts`. **Any new outbound event MUST go through the
   outbox (`OutboxWriter.enqueue`), never a direct publish or a `TransactionSynchronization`.**
   The `OutboxStore` port id is a **`String`** (not `long`) so it is store-neutral — `JpaOutboxStore`
   maps the Long IDENTITY via `String.valueOf`/`Long.parseLong`, `MongoOutboxStore` maps the `ObjectId`
   hex — the relay only echoes the id back to `markPublished`/`recordFailure`, so it never parses it.
4. **Idempotent JMS consumers** (JMS is at-least-once). `OrderListener` skips an order it already has;
   `InvoiceListener` no-ops if already COMPLETED. Keep new handlers idempotent.
5. **`ApprovalPolicy` thresholds:** auto-approve `USD < 500`, `JPY < 50000`, else PENDING. Keyed on the
   order's ISO 4217 **`currency`** (null/blank → `USD`), NOT `locale` — the legacy rule
   (`PurchaseOrderMDB.canIApprove`) always meant money (its own comment: "a stub for converting currency").
   Thresholds are legacy-faithful; change only with a parity note.
6. **`WarehouseOrder` is an 11-arg record:** `(orderId, userId, emailId, locale, currency, totalPrice, status,
   lines, shipTo, billTo, created)`. Adding/removing a component means updating **every** `new WarehouseOrder(...)`
   call site (`FulfilmentService`, `OrderListener`, `JpaOrderStore`, both tests). No positional surprises.
7. **Domain is framework-free.** `domain/*` are POJO records/enum + one `@Component` policy — no JPA/Jackson.
   JPA lives only in `repository.jpa`, Mongo mapping only in `repository.mongo`; persistence is always
   behind the `OrderStore`/`OutboxStore` ports. The service/messaging layers never see an entity, a
   document, or a profile — swapping the store is invisible above the port (see the profiles section above).

## Events (see repo skill for the full contract)

| Direction | Destination | Event | Trigger |
|-----------|-------------|-------|---------|
| consume | `PurchaseOrderQueue` (queue) | `PurchaseOrderEvent` | checkout → persist + auto-approve/PENDING |
| consume | `InvoiceTopic` (topic) | `InvoiceEvent` | ship → COMPLETED (backorder leaves APPROVED) |
| consume | `RestockTopic` (topic, sub `opc-restock`) | `RestockEvent` | restock → re-drive APPROVED backorders (H2) |
| publish | `ApprovedOrderQueue` (queue) | `OrderApprovedEvent` | on APPROVED → inventory fulfils |
| publish | `OrderStatusTopic` (topic) | `OrderStatusEvent` | on APPROVED/DENIED/COMPLETED → notification email |

## Client contract

`OrderProcessingClient` (in `client/`) forwards the acting admin's Bearer token; OPC verifies ADMIN itself
(RS256, `auth-client` public key — verify-only, no credential store here). Methods: `ordersByStatus`,
`getOrder`, `approve`, `deny`, `updateOrders` (atomic batch), `sales`. Endpoint paths and DTOs are the
single source of truth in `OrderProcessingEndpoints` / `OrderDtos` — keep them backward compatible.
