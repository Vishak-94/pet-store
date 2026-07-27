# order-processing-service — the authoritative Order Processing Center (OPC)

> Part of the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration. See the [repo README](../README.md).

**Port:** `8088` · **Package:** `com.petstore.opc` · **Legacy origin:** `opc.ear`

## What it does

The Order Processing Center is the **authoritative owner of orders and their workflow status** for the
whole system. No other service persists order status; every other service reacts to the events OPC emits.

- Consumes **`PurchaseOrderQueue`** (`PurchaseOrderEvent`, storefront checkout) → persists the order, then
  **auto-approves** under the currency threshold or leaves it **PENDING** for a human admin.
- Consumes **`InvoiceTopic`** (`InvoiceEvent`, inventory ship) → moves the order to **COMPLETED**.
- Consumes **`RestockTopic`** (`RestockEvent`, inventory restock) → **re-drives every APPROVED (backordered)
  order** oldest-first back through fulfilment (the migrated form of the legacy `processPendingPO`-on-restock).
- Publishes **`OrderApprovedEvent`** to **`ApprovedOrderQueue`** (→ inventory-service fulfils) on approval.
- Publishes **`OrderStatusEvent`** to **`OrderStatusTopic`** (→ notification emails) on APPROVED/DENIED/COMPLETED.
- Exposes the **admin facade API** (`/api/orders/**`, `/api/sales`) — the legacy `OPCAdminFacade` — plus a
  synchronous checkout intake endpoint. OPC owns NO admin UI; it owns the data and the workflow.

## Layout

Maven multi-module (`order-processing-service-parent`): the client SDK builds first, then the app depends on it.

| Module | Artifact | What it is |
|--------|----------|------------|
| `app/` | `order-processing-service` | The Spring Boot service (`com.petstore.opc`) that runs on :8088 |
| `client/` | `order-processing-client` | Thin importable SDK ([README](client/README.md)); imported by admin-office-service and by `app` itself |

Inside `app/src/com/petstore/opc`:

| Package | Classes |
|---------|---------|
| `domain` | `WarehouseOrder`, `OrderStatus`, `ContactInfo`, `OrderLine`, `OrderStatusChange`, `SalesReport` (+`SalesBucket`), `ApprovalPolicy` |
| `service` | `FulfilmentService` (intake + auto-approve), `AdminService` (approve/deny/batch/sales + restock re-drive), `ApprovalGateway`, `OrderStatusGateway`, `OutboxWriter` (enqueue in-txn), `OutboxRelay` (`@Scheduled` publisher) |
| `messaging` | `OrderListener` (PurchaseOrderQueue), `InvoiceListener` (InvoiceTopic), `RestockListener` (RestockTopic → re-drive APPROVED backorders) |
| `repository` | `OrderStore` (port), `OutboxStore` (port), `OutboxMessage` (port DTO) |
| `repository.jpa` | **default profile** — `JpaOrderStore` + `JpaOutboxStore` (`@Profile("!mongo")`), entities, Spring Data repos |
| `repository.mongo` | **`mongo` profile** — `MongoOrderStore` + `MongoOutboxStore` (`@Profile("mongo")`), documents, schema/txn config |
| `web` | `OrderProcessingApiController` |
| `security` | `SecurityConfig` |

## Build & run

**Not in `build-all.sh`** — build it on its own. Java 21 required.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd order-processing-service && mvn -q clean install   # installs client to ~/.m2, then app
```

`install` (not just `package`) because `app` depends on `order-processing-client`, and admin-office-service
depends on it too.

Run (default H2 profile):

```bash
mvn -pl app spring-boot:run      # or java -jar app/target/order-processing-service-1.0.0.jar
```

Run against MongoDB:

```bash
docker compose up -d mongo       # single-node replica set rs0 on :27018 (mongo-express on :8971)
SPRING_PROFILES_ACTIVE=mongo mvn -pl app spring-boot:run
```

The broker is the shared Artemis in petstore-app-v1 — bring the system up with `./run-all.sh`.

**Testcontainers Mongo tests skip without Docker.** A plain `mvn clean install` with no reachable Docker
skips the 20 `repository/mongo/*Test` integration tests (each guarded by `assumeTrue(dockerAvailable)`) and
stays green. To run them on Colima:

```bash
DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" \
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
mvn -pl app test -Dtest='Mongo*Test' -DargLine="-Dapi.version=1.44"
```

## API surface

The admin facade (the legacy `OPCAdminFacade`). All endpoints below are **ADMIN-only** (RS256 Bearer,
verified by OPC itself) except `/api/orders/intake`, which is customer-authenticated (the storefront
proxies the shopper's JWT) because it is the checkout write path.

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/orders/intake` | Synchronous checkout intake (customer-auth) — persist + auto-approve + dispatch |
| `GET` | `/api/orders?status=` | Order ids by workflow status |
| `GET` | `/api/orders/all` | All orders as summaries, newest-received first |
| `GET` | `/api/orders/{id}` | Full order detail |
| `GET` | `/api/orders/{id}/status` | Just the status |
| `POST` | `/api/orders/{id}/approve` | Approve a PENDING order → APPROVED |
| `POST` | `/api/orders/{id}/deny` | Deny a PENDING order → DENIED |
| `POST` | `/api/orders/approvals` | Atomic batch status update (legacy `updateOrders`/`OrderApproval`) |
| `GET` | `/api/sales?start=&end=&category=` | Sales aggregation over a date range (legacy `getChartInfo`) |

## Events (JMS)

See the repo skill for the full contract.

| Direction | Destination | Event | Trigger |
|-----------|-------------|-------|---------|
| consume | `PurchaseOrderQueue` (queue) | `PurchaseOrderEvent` | checkout → persist + auto-approve/PENDING |
| consume | `InvoiceTopic` (topic) | `InvoiceEvent` | ship → COMPLETED (backorder leaves APPROVED) |
| consume | `RestockTopic` (topic, sub `opc-restock`) | `RestockEvent` | restock → re-drive APPROVED backorders |
| publish | `ApprovedOrderQueue` (queue) | `OrderApprovedEvent` | on APPROVED → inventory fulfils |
| publish | `OrderStatusTopic` (topic) | `OrderStatusEvent` | on APPROVED/DENIED/COMPLETED → notification email |

## Persistence — H2 (default) | MongoDB

Persistence is **profile-selectable**. Both stores sit behind the `OrderStore`/`OutboxStore` **ports**
(ports & adapters / hexagonal); **exactly one is active per profile** (JPA adapters `@Profile("!mongo")`,
Mongo adapters `@Profile("mongo")`). Both starters stay on the classpath — each profile's `application.yml`
document excludes the other's autoconfig so only one tries to connect. The service and messaging layers
never see an entity, a document, or a profile — swapping the store is invisible above the port.

- **Default (H2)** — file-based H2 (`jdbc:h2:file:./data/opc`, overridable via `OPC_DB_PATH`) so orders
  survive restarts. Schema owned by **Flyway** under `resources/db/migration` (`V1__init_order_schema.sql`,
  `V2__outbox.sql`), `ddl-auto: none`. Nothing to run — this is the default.
- **Mongo** — `SPRING_PROFILES_ACTIVE=mongo`, MongoDB 7.0 single-node replica set `rs0` on
  `mongodb://localhost:27018/petstore?directConnection=true` (overridable via `OPC_MONGODB_URI`), started via
  the repo `docker-compose.yml`. `MongoSchemaConfig` applies `$jsonSchema` validators + indexes on
  `ApplicationReadyEvent`; `MongoTransactionManager` makes the order-write + outbox-enqueue commit atomically
  (why `rs0` is required). Full doc: [`docs/MONGODB_SCHEMA.md`](../docs/MONGODB_SCHEMA.md).

Concurrent writes are guarded by **`@Version`** optimistic locking (surfaced as HTTP 409). Outbound events use
a **transactional outbox**: gateways append the event row inside the business transaction, and a `@Scheduled`
`OutboxRelay` publishes unsent rows via the shared `MessagePublisher` (at-least-once). The **store chokepoint**
(`OrderStore.updateStatus`) enforces the lifecycle: a same-status write is an idempotent no-op, a terminal
order can never be reversed, and any transition not satisfying `OrderStatus.canGoTo` throws
`IllegalStateException` (409).

## Invariants

Do not break these (full detail in [CLAUDE.md](CLAUDE.md)):

1. **Authoritative order store** — OPC is the single writer of order status; do not add a second writer.
2. **`OrderStatus` = PENDING / APPROVED / DENIED / COMPLETED only** — transitions in `OrderStatus.canGoTo`
   (PENDING→{APPROVED,DENIED}, APPROVED→COMPLETED, DENIED/COMPLETED terminal); reversal is blocked at the
   store chokepoint. `SHIPPED_PART` was removed on purpose — do not reintroduce it.
3. **Transactional outbox for outbound events** — any new outbound event MUST go through
   `OutboxWriter.enqueue`, never a direct publish. Delivery is at-least-once.
4. **Idempotent JMS consumers** — `OrderListener` skips an order it already has; `InvoiceListener` no-ops if
   already COMPLETED. Keep new handlers idempotent.
5. **`ApprovalPolicy` thresholds** — auto-approve `USD < 500`, `JPY < 50000`, else PENDING; keyed on the
   order's ISO 4217 `currency` (null/blank → USD), legacy-faithful.
6. **Domain is framework-free** — `domain/*` are POJO records/enum + one `@Component` policy; JPA/Mongo
   mapping lives only behind the ports.

## See also

- [CLAUDE.md](CLAUDE.md) — detailed module guide + invariants
- [docs/LLD.md](docs/LLD.md) — design detail + diagrams
- [docs/MONGODB_SCHEMA.md](../docs/MONGODB_SCHEMA.md) — as-built Mongo schema
- [client/README.md](client/README.md) — the `order-processing-client` SDK
- [../DECISIONS.md](../DECISIONS.md) — repo rationale (~30 ADRs) — check before "restoring" any legacy behaviour
- [../docs/PARITY_AUDIT.md](../docs/PARITY_AUDIT.md) — legacy-vs-migrated parity baseline
