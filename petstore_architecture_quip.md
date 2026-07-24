# Java Pet Store 1.3.1_02 — Existing Architecture (Low-Level Design)

*Reverse-engineered from source (`components/*`, `apps/*`, `sun-j2ee-ri.xml`, `PopulateSQL.xml`, `CatalogDAOSQL.xml`). This documents the **as-is** legacy system as the behavioural/architectural baseline for a migration.*

---

## 1. Executive summary

Java Pet Store 1.3.1_02 (Sun J2EE "BluePrints", Jan 2003) is a **distributed J2EE 1.3 e-commerce system** — an online pet store with catalog browsing, cart, checkout, asynchronous order fulfilment, supplier integration, and a back-office admin client.

It is **not one application**. It is **four separately-deployed enterprise applications** (`.ear` files) that communicate **asynchronously over JMS**, sharing a library of **19 reusable EJB components**.

| Metric | Value |
|---|---|
| Deployable applications (`.ear`) | 4 — petstore, opc, supplier, admin |
| Reusable components | 19 |
| Java source files | ~309 |
| Lines of Java | ~36,000 |
| JSP pages | 98 |
| EJB deployment descriptors | 20 |
| Persistence | EJB 2.x CMP + one hand-written DAO |
| Database | Cloudscape (→ Apache Derby) |
| Messaging | JMS queues (point-to-point) |
| Runtime | Sun J2EE 1.3 Reference Implementation, JDK 1.4 |

---

## 2. Application (deployment) view

```
 WEB TIER                    BUSINESS / EJB TIER                    MESSAGING
──────────────────────────────────────────────────────────────────────────────

Browser ─HTTP─▶ petstore.ear  (storefront)
                  │ WAF MainServlet (front controller)
                  │ → EJBControllerLocalEJB (session façade + StateMachine)
                  │ → catalog / cart / customer / signon components
                  │
                  └─ checkout builds PurchaseOrder (XML) ──▶ jms/PurchaseOrderQueue
                                                                     │
                                                                     ▼
                opc.ear  (Order Processing Center)
                  │ PurchaseOrderMDB → ProcessManager (workflow state)
                  │ if large order → jms/OrderApprovalQueue ◀── admin approves
                  │ InvoiceMDB, Mail MDBs → jms/*MailQueue → mailer → email
                  └─ forward PO (XML) ──▶ jms/supplier
                                              │
                                              ▼
                supplier.ear  (warehouse/fulfilment)
                  │ SupplierOrderMDB → check inventory, ship
                  └─ send Invoice (XML) ──▶ back to opc

                petstoreadmin.ear  (back-office)
                  └─ Swing client via Java Web Start → queries/approves orders in opc

           All entities persist to Cloudscape/Derby via EJB 2.x CMP / Catalog DAO
```

**Key architectural fact:** the only edges *between* the four apps are **JMS queues** (`PurchaseOrderQueue`, `OrderApprovalQueue`, `supplier`, `*MailQueue`). Each app owns its own database view; shared data travels **as XML over JMS**, not via shared tables. This is a genuine **database-per-service / distributed data** design — 2003's version of microservice data isolation.

---

## 3. Layered / class-level design (by bounded context)

See the attached class diagram (`petstore_lld.png`). Layers are colour-coded: **service/business-logic**, **domain model**, **data-access**, **relational table**, **CMP-generated table**, **JMS async edge**.

### 3.1 WAF — Web Application Framework (the MVC engine)

A home-grown MVC framework (a proto-Struts) that the storefront runs on.

| Class | Stereotype | Responsibility |
|---|---|---|
| `MainServlet` | Front Controller (HttpServlet) | Single entry point; loads `mappings.xml`; `doGet/doPost → doProcess` |
| `RequestProcessor` | Web tier | Turns HTTP request into a WAF `Event` |
| `ScreenFlowManager` | View flow | Declarative page flow (which JSP renders next) |
| `EJBControllerLocalEJB` | Session façade (stateful SB) | Server-side controller; `processEvent(Event): EventResponse` |
| `StateMachine` | Command dispatch | Maps events → `EJBAction` handlers |

Request flow: `HTTP → MainServlet → RequestProcessor → (Event) → EJBControllerLocalEJB.processEvent() → StateMachine → EJBAction → component EJBs → EventResponse → ScreenFlowManager → JSP`.

### 3.2 Catalog context — the clean DAO layer

The **only** part with an explicit, well-designed data layer (read-heavy, so tuned SQL was wanted).

| Class | Stereotype | Key methods |
|---|---|---|
| `CatalogEJB` | Session bean (service) | `getCategory`, `getProducts`, `getItem`, `searchItems` |
| `CatalogDAO` | **Interface (port)** | `getCategory/getProduct/getItem/getItems/searchItems` |
| `CloudscapeCatalogDAO` | Adapter | Vendor SQL from `CatalogDAOSQL.xml` |
| `GenericCatalogDAO` | Adapter | Portable SQL |
| `Category`/`Product`/`Item`/`Page` | Domain value objects | — |

This is a 2003 version of **ports & adapters**: `CatalogDAO` is the port, the two DAOs are adapters, SQL is externalised per vendor.

### 3.3 Cart + SignOn contexts

| Class | Stereotype | Key methods |
|---|---|---|
| `ShoppingCartLocalEJB` | Stateful session bean | `addItem`, `updateItemQuantity`, `deleteItem`, `getItems`, `getSubTotal`, `empty` |
| `SignOnEJB` | Session bean | `authenticate(user,pwd): boolean`, `createUser` |
| `UserEJB` | CMP entity bean | `userName` PK, `password` |

### 3.4 Customer context — CMP entity beans

| Class | Stereotype | Fields / relations |
|---|---|---|
| `CustomerEJB` | CMP entity | `userId` PK; → Account, → Profile |
| `AccountEJB` | CMP entity | `status`; → ContactInfo, → CreditCard |
| `ProfileEJB` | CMP entity | `preferredLanguage`, `favoriteCategory`, `myListPreference`, `bannerPreference` |
| `ContactInfoEJB`, `AddressEJB`, `CreditCardEJB` | CMP entities (shared) | names/email/phone, street/city/state/zip, card details |

Relationships: `User ┈ Customer 1—1 Account 1—1 ContactInfo 1—1 Address`, `Customer 1—1 Profile`, `Account 1—1 CreditCard`.

### 3.5 Order / OPC context — the MDB workflow

| Class | Stereotype | Key methods |
|---|---|---|
| `PurchaseOrderMDB` | Message-driven bean | `onMessage` → persist PO, start workflow |
| `OrderApprovalMDB`, `InvoiceMDB` | Message-driven beans | approval / invoice transitions |
| `ProcessManagerEJB` | Session bean (workflow) | `createManager`, `updateStatus`, `getStatus`, `getOrdersByStatus` |
| `PurchaseOrderEJB` | CMP entity | `poId` PK, `poUserId`, `poDate`, `poValue`; `addLineItem`, `getData` |
| `LineItemEJB` | CMP entity | `itemId`, `quantity`, `quantityShipped`, `unitPrice` |
| `PurchaseOrder`/`LineItem` | Domain value objects | marshalled to/from **XML** for JMS |

### 3.6 Supplier context

| Class | Stereotype | Key methods / fields |
|---|---|---|
| `SupplierOrderMDB` | Message-driven bean | `onMessage` → fulfil, ship, invoice |
| `SupplierOrderEJB` | CMP entity | `poId` PK, `poDate`, `poStatus` |
| `InventoryEJB` | CMP entity | `itemId` PK, `quantity` |

---

## 4. Data model — two schema styles

The database layer is **split across two mechanisms** — this is one of the highest-effort parts of any migration.

### 4.1 Catalog schema — hand-designed relational (clean)

From `PopulateSQL.xml`. Normalized, with a **locale-split pattern** (base table + `_details` per locale) and real foreign keys.

```
category ──< product ──< item
   │            │           │
category_    product_     item_
details      details      details   (one row per locale)
```

| Table | Key columns | Type |
|---|---|---|
| `category` / `category_details` | catid PK; name, image, descn, locale | Relational (hand-written) |
| `product` / `product_details` | productid PK, catid FK; name, image, descn, locale | Relational |
| `item` / `item_details` | itemid PK, productid FK; listprice/unitcost DECIMAL(10,2), attr1..5, locale | Relational |

### 4.2 Everything else — CMP container-generated (machine-shaped)

From `sun-j2ee-ri.xml`. Auto-generated by the container from `<cmp-field>` declarations. Tell-tale signs: `__PMPrimaryKey`, `__reverse_*` FK columns, `EJBTable` suffix, everything `VARCHAR(255)`, separate join tables.

| Context | Tables | Type |
|---|---|---|
| Customer | `UserEJBTable`, `CustomerEJBTable`, `AccountEJBTable`, `ProfileEJBTable`, `ContactInfoEJBTable`, `AddressEJBTable`, `CreditCardEJBTable`, `CounterEJBTable` | CMP-generated |
| Order (OPC) | `PurchaseOrderEJBTable`, `LineItemEJBTable` (+ join table), `ManagerEJBTable`, `ContactInfoEJBTable`, `AddressEJBTable`, `CreditCardEJBTable` | CMP-generated |
| Supplier | `SupplierOrderEJBTable`, `InventoryEJBTable`, `LineItemEJBTable` (+ join), `ContactInfoEJBTable`, `AddressEJBTable` | CMP-generated |

**Critical observation:** `ContactInfo`, `Address`, `CreditCard`, `LineItem` tables are **duplicated across petstore / opc / supplier** — each `.ear` has its own DB view. This confirms the distributed, database-per-service design.

---

## 5. Key patterns present (and their modern equivalents)

| Pet Store (2003) | Modern equivalent |
|---|---|
| WAF `MainServlet` front controller | Spring MVC `DispatcherServlet` |
| `EJBControllerLocalEJB` + `StateMachine` + `EJBAction` | `@Controller`/`@Service` methods |
| EJB 2.x Local Home/Interface/Bean trios | plain `@Service` / `@Component` beans |
| CMP entity beans | JPA entities + Spring Data repositories |
| Catalog DAO + `CatalogDAOSQL.xml` | Spring Data repository / `JdbcTemplate` adapter |
| `ServiceLocator` (JNDI caching) | Spring dependency injection (eliminated) |
| JMS + MDBs between 4 EARs | Spring events, or Kafka/RabbitMQ + `@KafkaListener`, or in-process (modular monolith) |
| `xmldocuments` (XML over JMS) | JSON DTOs / records |
| JSP + custom taglibs + WAF templates | Thymeleaf / React |
| Cloudscape | H2 / PostgreSQL / MongoDB |

---

## 6. Why this matters for migration (summary)

1. **Catalog migrates easily** — clean schema + already a port/adapter.
2. **CMP tables need reverse-engineering + redesign** — do not carry over `__PMPrimaryKey`/`__reverse_` columns or VARCHAR(255)-everything; model proper JPA entities with correct types and associations, plus an old→new data-migration script.
3. **The duplication is an architectural decision** — keep database-per-context (microservices + messaging) or consolidate into a modular monolith with in-process events.
4. **The JMS seam is the biggest call** — the four apps are only coupled through queues; that boundary determines the target topology.
5. **Migrate along the bounded contexts** — Catalog (lowest risk) → Customer/SignOn → Cart → Order → OPC/Supplier fulfilment (async backbone last).

*Class-level diagram generated with Python + Graphviz (`petstore_lld_diagram.py`).*
