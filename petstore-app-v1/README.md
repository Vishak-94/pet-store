# Java Pet Store — storefront (`petstore-app-v1`)

The **:8080 storefront** of the migrated Java Pet Store. It serves the HTML shopping UI
(browse → cart → checkout, sign-on, registration, account self-service), hosts the
**embedded ActiveMQ Artemis broker** the whole fleet shares, and embeds the in-process
**cart-lib**. Spring Boot 3.3.5 on Java 21, package root `com.petstore`.

This is one module of a **multi-service system**, not a standalone monolith. It is
**publish-only for orders**: checkout publishes a `PurchaseOrderEvent` to the JMS
`PurchaseOrderQueue` and does not persist orders. The authoritative order store + workflow,
admin console, catalog, customer, auth, inventory and notification concerns each live in
their own service. See the repo root [`README.md`](../README.md) for the full topology and
[`../DECISIONS.md`](../DECISIONS.md) for the rationale.

> For a developer/agent guide to this module see [`CLAUDE.md`](CLAUDE.md); for the class +
> sequence design see [`docs/LLD.md`](docs/LLD.md).

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | **21** (LTS) | Tested on Amazon Corretto 21. `java -version` must report 21. |
| Maven | 3.9+ | Or your IDE's bundled Maven. |

The shared libs (`petstore-messaging`, `cart-lib`, and the `*-client` SDKs) must be in
`~/.m2` first — the repo `../build-all.sh` installs them. The JMS broker is embedded
(Artemis, in-process); there is **no database in this module** (it persists nothing).

## Build and run

```bash
# from the petstore-app-v1/ directory
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; ensure Java 21 is active

mvn clean package         # compile + run this module's tests
mvn spring-boot:run       # start on :8080 (also opens the shared broker on :61616)
#   ...or run the jar:
java -jar target/petstore-app-v1-1.0.0.jar
```

Start this app **first** (it hosts the broker), or use the repo `../run-all.sh` which
orders the fleet correctly. For the UI to fully work the downstream services should be up:
catalog (:8083), customer (:8081), auth (:8086), and — for orders to actually be processed —
order-processing (:8088) + inventory (:8085) + notification (:8087).

> Note: this project uses a **non-standard source layout** — main code under `src/`, tests
> under `test/`, resources under `resources/` — configured in the `pom.xml`.

## Using the application

- **Browse the store:** open <http://localhost:8080/> — categories → products → items, plus search.
- **Register / sign on:** create an account and log in; a returning user's stored
  `preferredLanguage` is applied to the session locale on sign-on (unless `?lang=` overrides it).
- **Place an order:** add items to the cart, then check out. Checkout collects and validates
  ship-to and bill-to addresses and publishes the order to JMS. Approval/fulfilment happen
  asynchronously in the order-processing and inventory services; order **status and admin
  approval live in those services**, not here (order-processing-service on :8088, admin console
  on :8082).

## Endpoints (this module)

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/` | public | Home — category list |
| GET | `/category?id=` | public | Products in a category |
| GET | `/product?id=` | public | Items of a product |
| GET | `/item?id=` | public | Item detail |
| GET | `/search?keyword=` | public | Search items |
| GET/POST | `/cart`, `/cart/add`, `/cart/update`, `/cart/delete` | public | Shopping cart (cart-id cookie) |
| GET | `/register-form` · POST `/register` | public | Registration (returns to originating screen) |
| GET | `/login` · `/logout` | public | Form login (delegated to auth-service) / sign-off |
| GET/POST | `/checkout` | **authenticated** | HTML checkout — collect/validate ship+bill address, publish PO |
| POST | `/api/checkout?userId=&email=` | **authenticated** | JSON checkout — publish PO |
| GET/POST | `/customer` | **authenticated** | Account self-service (update account/profile/card) |

There are intentionally **no** order-status or `/admin/**` endpoints in this module — those
capabilities are owned by order-processing-service and admin-office-service respectively.

## Architecture (this module's place in the system)

Hexagonal (ports & adapters), package-per-context under `com.petstore`
(`catalog`, `cart`, `order`, `customer`, `security`, `web`, `config`):

- **Domain** — framework-free view models (no Spring/JPA/JMS annotations).
- **Messaging adapter** — `OrderService` builds a `PurchaseOrderEvent` and publishes it via
  `MessagePublisher` (from `petstore-messaging`) to `Destinations.PURCHASE_ORDER`. No inbound
  JMS listeners live here — this module is a pure producer + broker host.
- **Client SDKs** — auth (login), customer (register/read/update), catalog (browse/search/price),
  all called with the session JWT as a Bearer token. Auth is fully delegated; this module holds
  no credentials.
- **Cart** — session-local via a `cartId` cookie (`CartIdFilter`), delegating to the in-process
  `cart-lib` (15-min sliding TTL = legacy session timeout).

The order workflow enum (`PENDING → APPROVED/DENIED → COMPLETED`) and its persistence live in
order-processing-service, not here.

## How it maps to the legacy app

| Legacy (J2EE 1.3) | Migrated (this storefront) |
|---|---|
| WAF MainServlet + EJBAction dispatch | Spring MVC `@Controller` |
| Stateful cart session bean | cart-id cookie + in-process `cart-lib` |
| JSP + WAF templates | Thymeleaf templates (`resources/templates/`) |
| ServiceLocator (JNDI) | Spring dependency injection (deleted) |
| Checkout → OPC via JMS | Checkout → `PurchaseOrderQueue` via `MessagePublisher` (kept) |
| Sign-on locale from profile | `SignOnLocaleSuccessHandler` applies `preferredLanguage` |

(The persistence, fulfilment, and admin mappings live in the corresponding services — see the
repo root README and `../docs/PARITY_AUDIT.md`.)

## Testing

```bash
mvn test        # unit + slice + characterization tests for this module
```

Key tests (`test/com/petstore/`): `order/OrderCharacterizationTest` (checkout publishes the PO,
computes the total, empties the cart — no persistence), `order/CheckoutAddressTest` (H7
ship/bill required-field validation), `cart/CartServiceAdapterTest`, and `security/SecurityTest`.

## Project layout

```
petstore-app-v1/
├── pom.xml
├── src/          main Java (com/petstore/<context>/{domain,service,web,messaging,config})
├── test/         characterization + slice tests
├── resources/    application.yml, messages*.properties, templates/
├── docs/         LLD.md (class + sequence design)
├── CLAUDE.md     module guide for Claude Code sessions
└── target/
```

## Acknowledgements
Migrated from the Sun Java Pet Store 1.3.1_02 BluePrints sample (Apache 2.0).
