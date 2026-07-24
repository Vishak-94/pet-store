# Java Pet Store → Spring Boot 3.x / Java 21 — Full Migration Plan

**Scope:** Java Pet Store 1.3.1_02 (4 apps, ~36k LOC). **Goal 1:** re-platform to latest
stable Spring Boot 3.x on Java 21, runnable locally. **No business-logic changes.**
Keep JMS. SOLID + patterns (ask when unsure). Legacy tree is read-only reference.

Defaults applied for OPEN items (revisable): Strangler Fig · modular monolith ·
embedded Artemis broker · Catalog slice first.

---

## 1. Principles & constraints (recap)

- Preserve observable behavior — characterization tests pin it BEFORE migrating.
- Keep JMS (broker), `@JmsListener` for consumers, keep the InvoiceTopic (pub/sub).
- SOLID throughout; surface pattern choices to the user (State, Command, etc.).
- Migrate the JMS build only (drop the SOAP `webservices/` variant).
- New `petstore-app-v1/` project; `petstore1.3.1_02/` stays read-only.

## 2. Target runtime & tooling

| Item | Choice |
|---|---|
| Framework / language | Spring Boot 3.3.x (latest stable 3.x) · Java 21 (Corretto 21, already installed) |
| Build | Maven multi-module; executable JAR (`mvn spring-boot:run`) |
| Web | Spring MVC + Thymeleaf |
| Persistence | Spring Data JPA + Hibernate → H2 embedded (Mongo variant later, behind port) |
| Messaging | Spring JMS + embedded ActiveMQ Artemis (real queues + topic, no separate install) |
| Testing | JUnit 5, MockMvc, `@DataJpaTest`, Testcontainers (Artemis/Mongo when Docker present) |

## 3. Target module structure (modular monolith, SOLID boundaries)

```
petstore-app-v1/
├── pom.xml (parent)
├── domain/         framework-free POJOs/records + Bean Validation (no Spring/JPA/Jackson)
├── catalog/        browse: CatalogRepository (port) + JPA adapter + CatalogService
├── customer/       account, signon, profile: entities + repos + services
├── cart/           CartService (@SessionScope)
├── order/          PurchaseOrder aggregate + OrderStatusService (unifies the 3 status writers)
├── fulfilment/     inventory (race-fixed), supplier order, @JmsListener workflow
├── messaging/      JMS config, publishers, Anti-Corruption Layer (XML <-> domain records)
├── web/            Thymeleaf controllers + @RestControllerAdvice
└── app/            Spring Boot main, application.yml, Artemis + H2 config
```

SOLID mapping: **S** — god-objects (WAF StateMachine, EJBController) split into focused
services; 3 MDB status-writers → one `OrderStatusService`. **O** — new workflow states via
new handlers. **L** — repo ports honest across JPA/Mongo. **I** — narrow ports (CatalogQuery,
InventoryStore, OrderRepository). **D** — services depend on interfaces; ServiceLocator deleted.

## 4. Files — three buckets

### DELETE (platform scaffolding, no equivalent)
`waf/**`, `components/servicelocator/**`, all `*Local.java`/`*LocalHome.java`/`*Home.java`
EJB trios, all `ejb-jar.xml`/`sun-j2ee-ri.xml`/`web.xml`/`application.xml`/`*-ds.xml`,
entire `webservices/**` (SOAP variant), admin Swing/JWS client + `*.jnlp`, Ant
`build.xml`/`setup.xml`.

### TRANSLATE (logic verbatim, plumbing swapped)
| Legacy | → New | Swap |
|---|---|---|
| `catalog/ejb/CatalogEJB` + `dao/CatalogDAO*` + `CatalogDAOSQL.xml` | `catalog/CatalogService` + `CatalogRepository` (+ entities) | @Stateless→@Service; DAO→Spring Data |
| `cart/ejb/ShoppingCartLocalEJB` | `cart/CartService` | stateful SB → @SessionScope; cart logic verbatim |
| `signon/ejb/SignOnEJB` | `customer/SignOnService` | authenticate/createUser unchanged |
| `customer/**` CMP (Customer/Account/Profile/ContactInfo/Address/CreditCard) | `customer/*` JPA entities + `CustomerService` | CMP→JPA |
| `purchaseorder`, `lineitem` CMP | `order/*` entities | CMP→JPA; PO 1—N LineItem |
| `processmanager/ejb/ProcessManagerEJB` | `order/OrderStatusService` | 3 status writers unified |
| `opc/**` MDBs (PurchaseOrder/Invoice/OrderApproval/Mail) | `order|fulfilment/*Listener` | onMessage→@JmsListener (+ idempotency) |
| `supplier/**` MDB + `OrderFulfillmentFacadeEJB` + `InventoryEJB` | `fulfilment/*` | logic verbatim; inventory race-guard fix |
| `petstore/controller/ejb/actions/*EJBAction` | folded into @Service methods (or Command — ASK) | dispatch removed |
| `AsyncSenderEJB` | `messaging/OrderMessagePublisher` (JmsTemplate) | keep JMS |
| `*.jsp` + `screendefinitions_*` | `web/templates/*.html` | JSF/JSP→Thymeleaf |

### PRESERVE (~unchanged)
Domain value objects (Category/Product/Item/Page, CartItem), catalog SQL semantics,
Bean Validation constraints, seed data (`PopulateSQL.xml`→`data.sql`), JMS destination
topology (PurchaseOrderQueue, OrderApprovalQueue, InvoiceTopic, *MailQueue).

## 5. Sanctioned technical fix (not a logic change)
Inventory concurrency: replace implicit EJB-container lock with atomic
`UPDATE inventory SET quantity = quantity - :qty WHERE item_id = :id AND quantity >= :qty`
(0 rows updated → backorder) + `CHECK (quantity >= 0)`. Preserves oversell→backorder
outcome (1 ships, 1 stays PENDING); pinned by characterization test.

## 6. Phased roadmap

| Phase | Scope | Key files created | Done when |
|---|---|---|---|
| 0 · Scaffold | parent pom, Boot 3.x/Java 21, H2, embedded Artemis, CI, CLAUDE.md, ledger, char-test harness | pom.xml(s), application.yml, PetStoreApplication, ArtemisConfig | empty app boots on Java 21 |
| 1 · Catalog | browse + catalog schema | Product/Category/Item entities, CatalogRepository(+JPA), CatalogService, CatalogController, data.sql, tests | **runs & browsable locally** |
| 2 · Customer/SignOn | register, auth, profile | Customer/Account/Profile/User entities, repos, SignOnService, CustomerService, controllers, tests | auth + profile tests green |
| 3 · Cart | cart (session) | CartService(@SessionScope), CartItem record, CartController, edge-case tests | cart edge tests green |
| 4 · Order | checkout + PO aggregate | PurchaseOrder/LineItem entities, OrderRepository, OrderService, OrderStatusService, OrderMessagePublisher, tests | checkout emits + persists |
| 5 · Fulfilment + JMS | inventory (fixed), supplier, workflow listeners | Inventory/SupplierOrder entities, FulfilmentService, @JmsListener beans (PurchaseOrder/Invoice/OrderApproval/Mail), ACL, tests | end-to-end order flow green |
| 6 · Web + Admin | Thymeleaf UI, REST admin | templates/*.html, WebControllers, AdminController (replaces Swing), RestExceptionHandler | UI + admin flows green |
| 7 · Cutover | data migration, parallel-run, decommission | Flyway/migration scripts, reconciliation | legacy off; ledger all-green |

## 7. Testing strategy (the safety net)
- Characterization tests FIRST per module — pin legacy behavior incl. quirks:
  - Catalog: unknown id → empty (not 404); locale lookups
  - Cart: addItem resets qty=1; updateItemQuantity(≤0) deletes; getSubTotal recomputes
  - Checkout: empty cart → error; optimistic (no stock check)
  - Inventory: 1 stock + 2 orders → one COMPLETED, one PENDING, stock never negative
  - Order status: PENDING→APPROVED→SHIPPED
  - JMS: at-least-once → @JmsListener idempotent (re-delivery no double-process)
- Pyramid: unit (services) · slice (@DataJpaTest) · MockMvc (web/REST) · integration
  (Testcontainers Artemis for the full async flow).
- Migrate → run tests → green-or-rollback; commit only on green; update ledger.

## 8. Risks & mitigations
| Risk | Mitigation |
|---|---|
| CMP relationships mis-mapped to JPA | Reverse-engineer from ejb-jar.xml + ER diagram; slice tests per aggregate |
| Lost container locking → oversell/negative stock | Atomic conditional UPDATE + CHECK constraint (§5) |
| JMS redelivery double-processing | Idempotency key / dedup in each @JmsListener |
| XML→JSON wire change breaks contract | ACL + golden-file message tests before changing format |
| Duplicated shared tables across apps | Consolidate into shared domain module (ADR) |
| Big-bang regression | Strangler Fig; vertical slice first; parallel-run before cutover |

## 9. Open decisions (surface when reached)
- Order workflow: State pattern vs status enum + guards (Order phase) — ASK.
- Web dispatch: fold EJBActions into services vs explicit Command (Web phase) — ASK.
- Async topology long-term: keep broker vs collapse to in-process events (post goal-1).

## 10. Definition of done (goal 1)
`mvn spring-boot:run` boots on Java 21; catalog browsable; a full order flows
end-to-end through JMS (checkout → PO persisted → approval → fulfilment → invoice →
status SHIPPED → mail); all characterization tests green; ledger all-green; README with
build/run steps.
