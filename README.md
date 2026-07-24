# Java Pet Store — Migrated to Spring Boot 3.x / Java 21

A migration of the legacy **Java Pet Store 1.3.1_02** (J2EE 1.3 BluePrints — EJB 2.x CMP,
JMS, Cloudscape, 4 `.ear` apps) to a modern **Spring Boot 3.3.5 / Java 21** system.

The migration was approached *as if the legacy codebase were far larger*: work was broken
into phased tasks, behaviour was pinned with **characterization tests** before changes, and
the system was decomposed along the legacy bounded contexts using the **strangler-fig** and
**ports-and-adapters** patterns. Every significant decision (with the options considered and
why) is logged in [`DECISIONS.md`](DECISIONS.md).

> **Scope note.** The core assignment is a *runtime migration* to Spring Boot / Java 21. This
> repo goes further and demonstrates how the same legacy app would be **decomposed at scale**
> into services along its natural seams (catalog / cart / customer / order-approval /
> inventory / auth / notifications) coordinated over JMS. The single-app migration story and
> the service-decomposition story are both here; see *Architecture* below.

---

## Prerequisites

| Tool | Version |
|------|---------|
| JDK  | **Java 21** (tested with Amazon Corretto 21) |
| Maven | 3.9+ |
| OS | macOS / Linux (scripts are bash; `lsof`, `nc`, `curl` used) |

No database or message broker to install — an in-memory **H2** database per service and an
**embedded ActiveMQ Artemis** broker (hosted by the storefront) are wired in.

---

## Build & Run (one command each)

```bash
./build-all.sh     # builds + installs all modules in dependency order
./run-all.sh       # starts all services (broker host first), waits for health
# ... use the app ...
./stop-all.sh      # stops everything
```

`run-all.sh` auto-detects a Java 21 JDK (via `JAVA_HOME` or `/usr/libexec/java_home -v 21`).
Per-service logs are written to `./logs/`.

Then open the storefront: **http://localhost:8080/**

### Run the tests

```bash
# from any module, or across the build:
mvn test
```

Test totals (all green): **petstore-messaging 4 · auth-client 3 · catalog-service 16 ·
customer-service 8 · cart-lib 12 · petstore-app-v1 15** = **58 tests, 0 failures**. These are
predominantly **characterization tests** pinning the legacy observable behaviour (cart quirks,
catalog not-found semantics, order/fulfilment rules).

---

## Services & ports

| Port | Service | Responsibility | Legacy origin |
|------|---------|----------------|---------------|
| 8080 | **petstore-app-v1** | Storefront: catalog browse, cart, checkout. Hosts the embedded broker; embeds `cart-lib`. | petstore.ear |
| 8081 | **customer-service** | Customer domain data (profile / account / card). | customer component |
| 8082 | **admin-office-service** | Order approval + status (ADMIN). | admin.ear + opc.ear |
| 8083 | **catalog-service** | Catalog (categories/products/items), multi-locale. | catalog component |
| 8085 | **inventory-service** | Inventory + fulfilment (SUPPLIER). | supplier.ear |
| 8086 | **auth-service** | Central identity provider — the sole token issuer (RS256). | signon component |
| 8087 | **notification-service** | Emails the customer on order events. | opc mailer / MailInvoiceMDB |
| 61616 | *Artemis broker* | Shared JMS (embedded in petstore-app-v1). | JMS backbone |

**Libraries** (not runnable apps, imported by the above): `petstore-messaging` (JMS
destinations + event envelope + converter), `auth-client` (RS256 verify + login),
`cart-lib` (in-process cart), `customer-service-client` / `catalog-service-client` (API SDKs).

---

## Architecture

```
Browser ─▶ petstore-app-v1 (:8080, storefront + embedded broker)
             │  ├─ login ───────▶ auth-service (:8086)  ── issues RS256 JWT; every service verifies
             │  ├─ browse ──────▶ catalog-service (:8083)
             │  ├─ cart ────────▶ cart-lib (in-process)
             │  └─ checkout ─── publishes PurchaseOrder ─┐
             ▼                                            │
     Artemis broker (:61616)  ◀───────────────────────────┘
       PurchaseOrderQueue ─▶ admin-office-service (:8082)  approve
       ApprovedOrderQueue ─▶ inventory-service (:8085)     reserve stock + ship
       InvoiceTopic (pub/sub) ─┬▶ admin-office-service     → order COMPLETED
                               └▶ notification-service      → email customer
```

- **Auth:** `auth-service` is the only credential store and the only token minter (RS256 —
  private key held only by auth-service; services verify with the public key via `auth-client`,
  so they *cannot* forge tokens).
- **Messaging:** two queues (commands, one consumer each) + one **topic** `InvoiceTopic`
  (event fan-out — restores the legacy `InvoiceTopic`), all defined once in `petstore-messaging`.
- **Data:** database-per-service (H2). Cross-service references are by opaque id, not shared tables.

---

## Migration approach (summary)

1. **Analyse & pin.** Reverse-engineered the legacy apps; wrote **characterization tests**
   capturing observable behaviour (e.g. cart "add resets qty to 1", catalog miss → empty not
   error, subtotal skips dangling items) *before* touching anything.
2. **Strangler-fig, phase by phase.** Migrated one bounded context at a time (catalog → cart →
   order → customer → …), keeping the system runnable throughout.
3. **Ports & adapters.** Domain logic depends on ports (e.g. `CatalogRepository`,
   `InventoryStore`); JPA/JMS are swappable adapters.
4. **Preserve JMS.** Kept async messaging (Artemis), including the legacy `InvoiceTopic`
   pub/sub, with a shared messaging library so the contract is single-sourced.
5. **Decompose at the seams.** Extracted services along the legacy `.ear` boundaries + a
   central auth IdP, each with its own DB and an importable client SDK.

Full decision log — every option weighed and the rationale — is in **[`DECISIONS.md`](DECISIONS.md)**.

---

## Notes / known limitations

- The RSA keypair under `auth-service` is a **demo key** committed for local runs; a real
  deployment would inject it as a secret (and serve the public key via JWKS for rotation).
- Order ids currently come from an in-memory counter (restart resets it) — the known follow-up
  is a persistent / snowflake id + checkout idempotency (see `DECISIONS.md`).
- MongoDB (the optional stretch goal) is **not** implemented — all services use H2.
