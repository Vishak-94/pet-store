# Java Pet Store — migrated to Spring Boot 3.x / Java 21

A migration of the classic **Java Pet Store 1.3.1_02** (Sun J2EE 1.3 "BluePrints":
JSF/WAF + EJB 2.x CMP + JMS/MDBs + Cloudscape, deployed as 4 EARs to the J2EE
reference server) to a modern, self-contained **Spring Boot 3.3 application on
Java 21** — an executable JAR you run with one command.

The migration preserved observable behaviour (verified by characterization tests)
and kept the asynchronous **JMS** backbone; it did not rewrite business logic.

---

## Table of contents
- [Prerequisites](#prerequisites)
- [Build and run](#build-and-run)
- [Using the application](#using-the-application)
- [Endpoints](#endpoints)
- [Architecture](#architecture)
- [How it maps to the legacy app](#how-it-maps-to-the-legacy-app)
- [Testing](#testing)
- [Swapping the database / broker (follow-up)](#swapping-the-database--broker-follow-up)
- [Project layout](#project-layout)

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | **21** (LTS) | Tested on Amazon Corretto 21. `java -version` must report 21. |
| Maven | 3.9+ | Or your IDE's bundled Maven. |

No database or message broker to install — the app embeds **H2** (database) and
**ActiveMQ Artemis** (JMS broker) and starts them in-process.

## Build and run

```bash
# from the petstore-app-v1/ directory
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; ensure Java 21 is active

mvn clean verify          # compile + run all tests
mvn spring-boot:run       # start the app
#   ...or run the jar:
java -jar target/petstore-app-v1-1.0.0.jar
```

The app starts on **http://localhost:8080**. The H2 console is at
`http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:petstore`, user `sa`,
empty password).

> Note: this project uses a **non-standard source layout** — main code under
> `src/`, tests under `test/`, resources under `resources/` — configured in the
> `pom.xml`.

## Using the application

- **Browse the store:** open <http://localhost:8080/> — categories → products →
  items, plus search.
- **Place an order:** add items to the cart, then check out. Small orders
  (< $500 US) are auto-approved and fulfilled; large orders wait for admin approval.
- **Admin:** list pending orders and approve/deny them (REST — see below).

### Quick end-to-end demo (curl)

```bash
# small order -> auto-approved and fulfilled (COMPLETED)
curl -c cj -b cj -X POST 'http://localhost:8080/cart/add?itemId=EST-1'  # (form field also ok)
curl -c cj -b cj -X POST 'http://localhost:8080/cart/add' -d 'itemId=EST-1'
OID=$(curl -c cj -b cj -s -X POST 'http://localhost:8080/checkout?userId=jane' | grep -o '[0-9]\+' | head -1)
curl "http://localhost:8080/orders/$OID/status"     # -> COMPLETED

# large order -> stays PENDING until admin approves
curl -c c2 -b c2 -X POST 'http://localhost:8080/cart/add' -d 'itemId=EST-18'
curl -c c2 -b c2 -X POST 'http://localhost:8080/cart/update' -d 'itemId=EST-18&qty=4'
BIG=$(curl -c c2 -b c2 -s -X POST 'http://localhost:8080/checkout?userId=whale' | grep -o '[0-9]\+' | head -1)
curl "http://localhost:8080/orders/$BIG/status"                 # -> PENDING
curl 'http://localhost:8080/admin/orders?status=PENDING'        # lists $BIG
curl -X POST "http://localhost:8080/admin/orders/$BIG/approve"  # approve
curl "http://localhost:8080/orders/$BIG/status"                 # -> COMPLETED (async)
```

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/` | Home — category list |
| GET | `/category?id=` | Products in a category |
| GET | `/product?id=` | Items of a product |
| GET | `/item?id=` | Item detail |
| GET | `/search?keyword=` | Search items |
| GET/POST | `/cart`, `/cart/add`, `/cart/update`, `/cart/delete` | Shopping cart (session-scoped) |
| POST | `/checkout?userId=&email=` | Place order (→ JMS fulfilment) |
| GET | `/orders/{id}/status` | Order workflow status |
| GET | `/admin/orders?status=` | Admin: list orders by status |
| POST | `/admin/orders/{id}/approve` \| `/deny` | Admin: approve / deny |

## Architecture

Single Spring Boot app, **modular monolith** with **ports & adapters (hexagonal)**
and strict package boundaries per bounded context:

```
catalog · customer · cart · order · fulfilment · admin
```

- **Domain** — framework-free POJOs (no Spring/JPA/JMS annotations).
- **Ports** — technology-agnostic interfaces (`CatalogRepository`,
  `OrderRepository`, `InventoryRepository`, `OrderMessagePublisher`, …).
- **Adapters** — JPA (persistence) and Artemis JMS (messaging) implementations,
  isolated in `repository/jpa` and `messaging` packages.
- **Services** — business logic depending only on ports (Dependency Inversion).

Order workflow is an enum + guarded transitions
(`PENDING → APPROVED/DENIED → SHIPPED_PART → COMPLETED`). Checkout publishes to
the JMS `PurchaseOrderQueue`; an `@JmsListener` consumes and fulfils (idempotent,
safe under at-least-once redelivery). Inventory reservation uses **pessimistic
locking** (`SELECT ... FOR UPDATE`) so concurrent orders cannot oversell.

## How it maps to the legacy app

| Legacy (J2EE 1.3) | Migrated (Spring Boot / Java 21) |
|---|---|
| 4 EARs on the J2EE RI | 1 executable JAR, embedded Tomcat |
| WAF MainServlet + EJBAction dispatch | Spring MVC `@Controller` / `@RestController` |
| EJB 2.x Stateless session beans | `@Service` |
| Stateful cart session bean | `@SessionScope` `CartService` |
| CMP entity beans (+ `ejb-jar.xml`) | JPA entities + Spring Data repositories |
| Catalog DAO + `CatalogDAOSQL.xml` | `CatalogRepository` + JPA adapter |
| ServiceLocator (JNDI) | Spring dependency injection (deleted) |
| JMS + MDBs (OPC/Supplier) | Spring JMS `JmsTemplate` + `@JmsListener` (kept) |
| Swing / Java Web Start admin client | REST admin endpoints |
| Cloudscape | H2 (embedded) |

## Testing

```bash
mvn test        # unit + slice + full-stack characterization tests
```

**43 characterization tests** pin the legacy behaviour so the migration is
verifiable, including the subtle contract quirks: catalog unknown-id → empty (not
404); cart add-resets-qty-to-1 and qty≤0-deletes; empty-cart checkout → error;
locale-based auto-approval thresholds; workflow transition guards; the
**"1 in stock, 2 orders → one ships, one backorders, stock never negative"**
scenario (plus a 20-thread concurrency test); and JMS at-least-once idempotency.

## Swapping the database / broker (follow-up)

Because persistence and messaging sit behind ports, swaps are additive:

- **H2/JPA → MongoDB:** add `repository/mongo` adapters implementing the same
  port interfaces (`@Document` + Spring Data Mongo), activate with a Spring
  profile. No changes to domain, services, controllers, or contract tests.
- **Artemis/JMS → Kafka/RabbitMQ:** replace `JmsOrderMessagePublisher` and swap
  `@JmsListener` → `@KafkaListener` in the inbound adapters. Business logic
  unchanged; the idempotency guard keeps it safe under any at-least-once broker.

## Project layout

```
petstore-app-v1/
├── pom.xml
├── src/          main Java (com/petstore/<context>/{domain,repository,service,web,messaging})
├── test/         characterization tests
├── resources/    application.yml, schema.sql, data.sql, templates/
└── target/
```

## Acknowledgements
Migrated from the Sun Java Pet Store 1.3.1_02 BluePrints sample (Apache 2.0).
