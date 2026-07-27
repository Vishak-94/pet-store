---
name: petstore-dev
description: Conventions and how-to for developing the migrated Java Pet Store (Spring Boot 3.3.5 / Java 21 microservices + libs). Use when building, running, testing, or changing any migrated module (petstore-app-v1, customer/catalog/auth/inventory/notification/order-processing/admin-office services, cart-lib, petstore-messaging). Covers build/run, hexagonal layering, the JMS event contract, ports, and the parity rule.
---

# Pet Store — developer skill

The migrated system is Java Pet Store (J2EE 1.3) re-implemented as **Spring Boot 3.3.5 on
Java 21 (Corretto 21)** — 8 runnable services + an embedded Artemis broker + shared libraries.
The guiding rule inherited from the migration is **parity first**: migrate *observable behaviour*,
not code. New behaviour is a separate, tested change — never folded into a parity fix.

The legacy tree `petstore1.3.1_02/` is a **read-only executable specification**. Read it to confirm
intent; never edit it.

## Module map

| Module | Port | Role | Sub-modules | Java package |
|--------|------|------|-------------|--------------|
| `petstore-app-v1` | 8080 | Storefront (browse/cart/checkout); **publish-only** for orders (no order persistence — legacy-faithful); hosts the embedded Artemis broker; i18n en/ja/zh | flat (`src`,`resources`,`test`) | `com.petstore` |
| `customer-service` | 8081 | Customer domain data (profile/account/card); verify-only auth | `app` + `client` | `com.petstore.customer` |
| `admin-office-service` | 8082 | Admin console; owns NO order data — **delegates to OPC** via `order-processing-client` | flat | `com.petstore.warehouse` |
| `catalog-service` | 8083 | Catalog (category/product/item); locale-split tables | `app` + `client` | `com.petstore.catalog` |
| `inventory-service` | 8085 | Fulfilment + inventory; consumes `ApprovedOrderQueue`, publishes `InvoiceTopic`; pessimistic lock | flat | `com.petstore.inventory` |
| `auth-service` | 8086 | Central IdP: the ONLY token issuer (RS256, holds the private key) and the ONLY credential store | `app` + `client` | `com.petstore.authsvc` / `com.petstore.auth` (client) |
| `notification-service` | 8087 | Customer emails on `InvoiceTopic` + `OrderStatusTopic` | flat | `com.petstore.notification` |
| `order-processing-service` | 8088 | The OPC: **authoritative order store + workflow**; consumes `PurchaseOrderQueue`→persist, auto-approve/PENDING, consumes `InvoiceTopic`→COMPLETED; admin facade API | `app` + `client` | `com.petstore.opc` |
| `cart-lib` | — (lib) | In-process cart, 15-min sliding TTL (= legacy session timeout) | flat | `com.petstore.cart` |
| `petstore-messaging` | — (lib) | Shared JMS destinations + event records + the ONE `MessagingConfig` | flat | `com.petstore.messaging` |

Libraries are imported, not run: `petstore-messaging`, `auth-client`, `cart-lib`,
`customer-service-client`, `catalog-service-client`, `order-processing-client`.

## Build & run

Java 21 is required. Resolve it with `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`
(fallback `/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home`).

- **Build everything:** `./build-all.sh` — installs the libs (`petstore-messaging`, `auth-service`,
  `catalog-service`, `customer-service`, `cart-lib`) to `~/.m2`, then packages the leaf apps
  (`admin-office-service`, `inventory-service`, `notification-service`, `petstore-app-v1`).
  **Note:** `order-processing-service` is NOT in `build-all.sh`'s list — build it separately with
  `cd order-processing-service && mvn -q clean install`.
- **One module:** `cd <module> && mvn -q clean package` (add `install` if other modules depend on it —
  i.e. anything with a `client` sub-module, plus `petstore-messaging`/`cart-lib`).
- **Run the fleet:** `./run-all.sh` (starts the broker-host `petstore-app-v1` first, auto-detects Java 21),
  `./stop-all.sh` to stop. `./generate-keys.sh` creates the RSA keypair (private key is gitignored).
- **Verbose build output:** redirect to a temp file and tail/grep it — build logs are large.

## Architecture rules (hexagonal / ports & adapters)

1. **Domain layer is framework-free.** Domain types are POJOs/records with no Spring, JPA, or Jackson
   annotations. JPA `@Entity` classes are separate persistence adapters that map to/from domain records.
2. **Persistence behind a port.** Every aggregate has a `XxxRepository`/`XxxStore` interface (the port)
   with a JPA adapter. Services never touch `EntityManager`/`JpaRepository` directly.
3. **Messaging behind the shared contract.** Do not hand-roll JMS config per service — import
   `com.petstore.messaging.MessagingConfig` (provides the JSON converter + `queueFactory`/`topicFactory`).
4. **Clients are thin.** A `client` sub-module exposes a typed client + DTOs + endpoint constants; it
   forwards the caller's Bearer token. Keep public DTOs/signatures backward compatible.
5. **SOLID + match surrounding style** (comment density, naming, idioms) when editing.

## JMS event contract (the backbone)

Broker: Artemis on `:61616`, embedded in `petstore-app-v1`. All destination names live in
`petstore-messaging/.../Destinations.java`; all event records in `.../events/`. The single type-id map
is `MessagingConfig.TYPE_IDS` (`_type` header → class) — producers and consumers can never drift.

| Destination | Kind | Event | Flow |
|-------------|------|-------|------|
| `PurchaseOrderQueue` | queue | `PurchaseOrderEvent` | storefront checkout → OPC (persist + approve) |
| `ApprovedOrderQueue` | queue | `OrderApprovedEvent` | OPC approval → inventory-service (fulfil) |
| `InvoiceTopic` | topic | `InvoiceEvent` | inventory ship → OPC (COMPLETED) **and** notification (email) |
| `OrderStatusTopic` | topic | `OrderStatusEvent` | OPC approve/deny/complete → notification (status email) |
| `RestockTopic` | topic | `RestockEvent` | inventory restock → OPC re-drives APPROVED backorders (retry-on-restock, H2) |

Every event carries an `EventMeta` envelope (`eventId`, `type`, `occurredAt`, `correlationId`). When you
add or change an event, update `TYPE_IDS` **and** the `EventSerializationTest` round-trip test. New fields
should be nullable for backward compatibility with in-flight messages.

Publishing is **after-commit**: gateways register a `TransactionSynchronization` so a rolled-back
transaction never publishes. Consumers must be **idempotent** (JMS is at-least-once).

## Order workflow

States: `PENDING → APPROVED → COMPLETED`, or `PENDING → DENIED`. Auto-approval threshold (in
`ApprovalPolicy`): US `< 500`, JAPAN `< 50000`, else stays PENDING for manual admin approval.

## Auth model

RS256. `auth-service` holds the private key and is the only issuer; every other service holds only the
public key (via `auth-client`) and can verify but not forge. All users live in `auth-service`'s single
account store. Seed logins: `j2ee/j2ee` (USER), `supplier/supplier` (SUPPLIER), `admin/admin` (ADMIN).

## Testing & the parity rule

- Tests live in each module's `test/` (or `app/test/`) and must not import legacy EJB types.
- Characterization/parity tests pin behaviour **as it is** — do not weaken or `@Disabled` a test to go green.
- Message flows: assert payload shape and idempotency. HTTP: pin status codes + JSON shapes.
- The behavioural parity baseline vs legacy is tracked in `docs/PARITY_AUDIT.md`; architecture rationale
  (~30 ADRs) is in `DECISIONS.md` at the repo root — **check it before "restoring" anything**, since some
  legacy behaviours were dropped intentionally (e.g. all-or-nothing fulfilment).

## Where to look

- Per-module design: `<module>/docs/LLD.md` (class + sequence diagrams).
- Per-module Claude guidance: `<module>/CLAUDE.md`.
- Per-module skill: `<module>/.claude/skills/` (scoped conventions).
- Global rationale: `DECISIONS.md`, parity baseline `docs/PARITY_AUDIT.md`, diagrams under `docs/`.
