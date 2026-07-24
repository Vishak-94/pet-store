# Pet Store Migration — Decision Log (ADRs)

Running record of every migration decision: the options considered, what we chose,
and why. Status: **DECIDED** (locked by user) · **RECOMMENDED** (my proposal, awaiting confirm) · **OPEN**.

| # | Decision | Options considered | Chosen | Status | Rationale |
|---|----------|--------------------|--------|--------|-----------|
| 1 | Source application | kitchensink · Oracle Pet Store · both | **Pet Store only** | DECIDED | User: focus on Pet Store; kitchensink out of scope |
| 2 | Target runtime | Spring Boot · Quarkus | **Spring Boot 3.x (latest stable) / Java 21** | DECIDED | Largest ecosystem, simplest local run, exec JAR |
| 3 | Async messaging | Keep JMS (broker) · In-process Spring events | **Keep JMS** (@JmsListener, keep InvoiceTopic) | DECIDED | User directive; preserve distributed design faithfully |
| 4 | SOAP web-services variant | Migrate it · Drop it | **Drop — migrate JMS build only** | DECIDED | The two builds are duplicates; migrating both doubles work |
| 5 | Database (goal 1) | H2 embedded · Postgres · MongoDB | **H2 embedded** (Mongo = stretch) | DECIDED | No install; runs on laptop; Mongo later behind port |
| 6 | Business logic | Refactor/improve · Preserve exactly | **Preserve exactly** (characterization tests) | DECIDED | User directive; parity first, verify before/after |
| 7 | Design approach | Ad hoc · SOLID + patterns | **SOLID; ask before choosing a pattern** | DECIDED | User directive |
| 8 | Migration strategy | Big-bang · Strangler Fig · Lift-and-shift | **Strangler Fig, tests-first** | RECOMMENDED | Incremental, always shippable, low risk |
| 9 | Architecture topology | Modular monolith · Microservices | **Modular monolith (keep module boundaries)** | RECOMMENDED | Simplest local run; JMS still used between modules |
| 10 | Inventory concurrency fix | Atomic conditional UPDATE · @Version optimistic lock · leave as-is | **Atomic conditional UPDATE** (+ non-neg constraint) | RECOMMENDED | Container lock is gone in JPA; UPDATE...WHERE qty>=n is race-safe & sets floor in one statement |
| 11 | Checkout consistency | Keep optimistic (no stock check) · Add real-time reservation | **Keep optimistic** | RECOMMENDED | Preserve behavior + storefront/inventory decoupling |
| 12 | Legacy code handling | Edit in place · New project, legacy read-only | **New `petstore-spring/` project; legacy read-only** | DECIDED | It's a re-platform, not in-place edits |
| 13 | Phase-1 slice | Catalog first · All 4 apps up front · skeleton only | **Catalog slice first** | RECOMMENDED | Prove runtime end-to-end before fanning out |
| 14 | JMS broker (local) | Embedded Artemis · External ActiveMQ (Docker) | **Embedded Artemis** | RECOMMENDED | Real JMS, no separate install; Docker not present |
| 15 | Order workflow pattern | State pattern · status enum + guards | **Status enum + guarded transitions (Option A)** | DECIDED | User chose A after pros/cons review; right-sized for 5 stable states, closest to legacy string-status model |
| 16 | Web action dispatch | Fold EJBActions into services · explicit Command pattern | **Thin controllers → services directly** | DECIDED | User chose; idiomatic Spring MVC (the framework is the dispatcher), no command indirection |

| 17 | Base package name | Keep `com.sun.j2ee.blueprints` · rename | **Rename → `com.petstore`** | DECIDED | Thymeleaf 3.1 blocks property access on `com.sun.*` (treated as restricted JDK namespace); also correct for a modern rewrite |
| 18 | View model types | Java records · plain immutable classes | **Plain immutable classes w/ getters** | DECIDED | SpringEL/Thymeleaf security blocks reflective record-component access; getters render cleanly |
| 19 | Catalog module layout | Multi-Maven-module · single module + package boundaries | **Single module, strict packages (domain/repository/service/web)** | RECOMMENDED | Same SOLID separation, far simpler to boot; split later if needed |

| 20 | Customer aggregate shape (Phase 2) | Faithful CMP graph (Customer→Account→ContactInfo→Address + Profile as separate tables) · flatten into one customer table | **Flatten into one `customer` table + separate `app_user`** | DECIDED | Pieces always accessed together; simpler, no `__PMPrimaryKey`; behavior identical |
| 21 | Password storage (Phase 2) | Preserve plaintext equality (legacy) · hash now | **Preserve plaintext matchPassword** | DECIDED | Behavior-preserving migration; hashing is a separate security improvement, flagged for later (NOT during parity migration) |

| 22 | Cart scope (Phase 3) | `@SessionScope` bean · session-stored DTO · singleton | **`@SessionScope` (proxyMode TARGET_CLASS)** | DECIDED | Preserves legacy stateful-per-user cart; singleton would leak carts across users (forbidden shortcut) |
| 23 | Cart↔catalog coupling | Cart stores full item snapshots · cart stores id→qty, resolves via CatalogService | **Store id→qty, resolve via CatalogService** | DECIDED | Matches legacy (CatalogHelper.getItem per line); enables the "skip dangling item" behavior |

| 24 | Order↔status persistence (Phase 4) | Status field on PO row · separate order_status table | **Separate `order_status` table** (unifies legacy ManagerEJBTable, 3 MDB writers) behind OrderStatusService | DECIDED | Matches legacy Manager spine; one service owns transitions |
| 25 | PO lineItems fetch (Phase 4) | LAZY · EAGER | **EAGER** | DECIDED | Aggregate is always used whole; avoids LazyInit outside session |
| 26 | Checkout JMS payload (Phase 4) | Full PO as XML (legacy) · order id, load PO in listener | **Order id** (PO already persisted); JSON/behind port | RECOMMENDED | Simpler, contract-testable; legacy XML re-createable later if needed |

| 27 | Inventory concurrency (Phase 5) | Read-then-write (legacy) · atomic conditional UPDATE · @Version · **pessimistic lock** | **Pessimistic lock (`SELECT ... FOR UPDATE` via PESSIMISTIC_WRITE)** — user directed (superseded atomic-UPDATE) | DECIDED | Explicit row lock held across read-check-decrement in one @Transactional; closest to the legacy EJB container-lock semantics; verified with 20-thread concurrency test (5 stock → 5 succeed, never negative) + FOR UPDATE SQL confirmed |
| 28 | JMS consumer (Phase 5) | @JmsListener · MessageListener bean | **@JmsListener on PurchaseOrderQueue** (@EnableJms) | DECIDED | Direct MDB replacement; verified live end-to-end |
| 29 | Fulfilment idempotency | none · dedup table · terminal-state guard | **Terminal-state guard** (skip if COMPLETED/DENIED) | DECIDED | Verified live: JMS redelivered order 1001 twice, completed once |
| 30 | @Modifying inventory query | default · flushAutomatically+clearAutomatically | **flush+clear automatically** | DECIDED | Bulk UPDATE must sync persistence context so reads see new stock |

## Swappability guarantee (SOLID / hexagonal) — for the DB→Mongo, JMS→Kafka follow-up

Audited (verified by grep, not assertion):
- **Domain packages import ZERO infrastructure** (no jakarta.persistence/jms, no hibernate, no springframework.data). Pure POJOs.
- **Service packages import ZERO infrastructure** (no EntityManager, JmsTemplate, JPA). They depend only on port interfaces via constructor injection (DIP).
- **JPA appears ONLY in `*/repository/jpa/`**; **JMS appears ONLY in `*/messaging/`**. All infra is quarantined in adapters.

Ports (technology-agnostic interfaces): `CatalogRepository`, `CustomerRepository`, `CredentialsRepository`, `OrderRepository`, `InventoryRepository`, `OrderMessagePublisher`. (Consumer side: `OrderFulfilmentListener` is the JMS inbound adapter.)

**To swap H2/JPA → MongoDB:** add `*/repository/mongo/` adapters implementing the SAME port interfaces (`@Document` classes + Spring Data Mongo repos), annotate with a profile (`@Profile("mongo")`), disable the JPA ones. ZERO changes to domain, services, controllers, or tests-of-contract. (Same pattern already proven in the kitchensink MemberStore JPA↔Mongo swap.)

**To swap Artemis/JMS → Kafka/RabbitMQ:** replace `JmsOrderMessagePublisher` (implements `OrderMessagePublisher` port) with a `KafkaOrderMessagePublisher`, and swap `@JmsListener` → `@KafkaListener` in the inbound adapter. `OrderService`/`FulfilmentService` unchanged (they know only the port + the idempotent handler). Note: idempotency guard already in place → safe under any at-least-once broker.

Caveat for Mongo: MongoDB has no `SELECT ... FOR UPDATE`; the pessimistic inventory lock would become either a Mongo `findAndModify` atomic conditional update or a transaction with `$inc`+guard. That's an adapter-internal change — the `InventoryRepository.tryReserve` contract is unchanged.

| 31 | Admin client (Phase 6) | Migrate Swing/JWS · REST endpoints | **REST admin endpoints** (AdminController) | DECIDED | Swing/JWS dead on modern JVMs; REST replaces it (getOrdersByStatus, approve, deny) |
| 32 | Approve→fulfilment trigger (Phase 6) | Publish inline in @Transactional · publish AFTER_COMMIT | **@TransactionalEventListener(AFTER_COMMIT)** | DECIDED | Live test found race: inline publish → consumer read stale PENDING. After-commit event fixes it |

## Bugs found via live end-to-end testing (Phase 6)
1. **Large order auto-shipped without approval.** FulfilmentService auto-transitioned PENDING→APPROVED, bypassing admin. Fix: fulfilment only ships APPROVED orders; PENDING waits. Regression test added.
2. **Approved order not fulfilling (race).** approve() published to JMS inside the transaction, before commit → the fulfilment consumer read stale PENDING status and skipped. Fix: publish via AFTER_COMMIT transactional event. Both verified live: large order → PENDING → admin approve → COMPLETED.

## Migration gap fixes (post phase-6 audit)
A gap analysis (legacy endpoints vs migrated) found the customer web surface, credit card, and locale were incompletely migrated. Fixed:
- **CreditCard** — was dropped in the first Phase-2 flatten (decision #20). Restored: `CreditCard` domain type, added to `Customer` aggregate + `customer` table (card_number/type/expiry). Response masks the PAN. Card storage kept plaintext for parity; PCI tokenisation flagged as post-cutover (like #21 passwords).
- **Customer web layer** — `CustomerService` logic existed but had NO controller. Added `CustomerController`: `POST /register`, `GET /customer/{id}`, `PUT /customer/{id}/account|profile|card`. Replaces legacy createcustomer.do / customer.do.
- **Locale** — catalog endpoints hardcoded Locale.US; legacy was multi-lingual (en/ja/zh). Added optional `?lang=` param (defaults en_US) on all catalog endpoints.
- Verified live: register with card → masked on read, update account keeps card, duplicate → 409. 47 tests green (+4 card tests).

### Sign-on / logout — DONE (Spring Security)
- Added `spring-boot-starter-security`: form login (`/login`) + `/logout`, replacing the legacy SignOnFilter + signoff.do.
- `CustomerUserDetailsService` bridges Security to the existing `CredentialsRepository` (reuses migrated UserEJB store); passwords compared plaintext via `{noop}` (parity, #21) — BCrypt = post-cutover hardening.
- Route rules mirror legacy signon-config: public catalog/cart/register/login; `/customer/**`+`/checkout` authenticated; `/admin/**` needs ROLE_ADMIN ('admin' user). Logout invalidates session (drops session-scoped cart, like signoff.do).
- CSRF enabled for browser forms (Thymeleaf auto-injects `_csrf`); verified live: token→login 302→protected route reachable; logout ends session; wrong password unauthenticated; customer role → /admin 403. 55 tests green (+8 security).
- Seed users: j2ee/j2ee (customer), admin/admin (back-office). data.sql made idempotent (H2 MERGE) so multi-context tests + restarts are safe.

### Clickable storefront UI — DONE (closed the 2 UX-parity gaps)
- Shared Thymeleaf nav fragment (thymeleaf-extras-springsecurity6): Home/Cart + Sign On/Register when anonymous, username + Sign Off when authed, Admin link for ADMIN.
- Add-to-Cart buttons on item/product/search pages (POST /cart/add).
- HTML registration form (/register-form) capturing login + address + credit card → CustomerService.register.
- HTML checkout page (/checkout, StorefrontController) that pulls the signed-in customer's saved account/card + order summary → Place Order → order_complete page.
- REST checkout moved to /api/checkout (avoids conflict with the HTML /checkout).
- Verified live end-to-end: register → login → add-to-cart → cart → checkout → order placed. 55 tests still green.

### Still deliberately NOT migrated (documented, low priority)
- Admin `getChartInfo` (sales reporting/analytics — out of scope).
- Supplier `queryOrderStatus` + `/RcvrRequestProcessor` warehouse inventory-view UI (internal supplier tooling).

## Microservices split — customer-service extracted (first service)
Decisions (user-confirmed): plan = build customer-service · DB-per-service · JWT auth · checkout→cart-service (later).
- New standalone Spring Boot app `customer-service/` (port 8081), own H2 DB (customer schema only: app_user, customer). Reuses the monolith's customer domain/repository/service verbatim (same com.petstore.customer packages).
- **JWT issuer**: POST /auth/login → validates via migrated SignOnService (plaintext parity) → returns signed JWT (jjwt HS384, roles claim; admin→ADMIN). Stateless SecurityFilterChain + JwtAuthFilter verifies Bearer tokens; /register + /auth/login public, everything else authenticated.
- Endpoints: POST /auth/login, POST /register, GET /customer/{id}, PUT /customer/{id}/account|profile|card.
- Verified live on :8081 — wrong pw→401, login→JWT, no-token→403, register→201, token→GET customer→200 (card masked). 3 tests green.
- Next services (catalog, cart+checkout, inventory/admin, order-processing) not yet built — plan-only.

## Security hardening — BCrypt password hashing (customer-service)
INTENTIONAL deviation from legacy parity (all prior work preserved plaintext, #21). In customer-service:
- SignOnService now injects PasswordEncoder (delegating encoder). createUser stores encoder.encode(raw) → {bcrypt}$2a$...; authenticate uses encoder.matches(raw, storedHash). Legacy User.matchPassword no longer used for verification (kept but dead).
- password column widened VARCHAR(25)→VARCHAR(80) for the ~68-char hash. Length validation applies to the RAW password (max 25) before hashing.
- Seed users re-hashed: j2ee/j2ee, admin/admin stored as {bcrypt} hashes in data.sql.
- Verified live: seeded login 200, wrong pw 401, register→login roundtrip 200 (hashed on register). 3 tests green.
- Rationale: passwords crossing a network boundary in a microservice make plaintext-at-rest the top risk; this is the one behavior-changing security fix worth making. Still open (deferred as infra/not-local): TLS in transit, RS256/JWKS for JWT.

## customer-service local hardening (all 5, tested)
1. **BCrypt** — passwords hashed at rest + verified via encoder.matches (done earlier).
2. **@Valid + @RestControllerAdvice** — RegisterRequest constraints (NotBlank userName, password 4-25, @Email); uniform error body {status,error,detail,correlationId}: validation→400 field map, duplicate→409, not-found→404.
3. **Actuator** — spring-boot-starter-actuator; /actuator/health,info,metrics exposed (health show-details).
4. **Correlation IDs** — CorrelationIdFilter (highest precedence): reuse inbound X-Correlation-Id or generate, put in SLF4J MDC (log pattern shows cid=...), echo response header. Enables cross-service log stitching.
5. **Random stored customerId** — User gains customerId (random UUID at register, stored in app_user.customer_id); login returns it + puts in JWT cid claim. No longer derivable from username.
Verified live + 14 tests green (11 new HardeningTest). Deferred (infra/not-local): TLS, RS256/JWKS, token revocation, rate limiting, log aggregation.

## Monolith fully delegates customer/auth to customer-service (user-directed)
The monolith now holds ZERO customer business logic — it is a pure CLIENT of customer-service.
- DELETED from monolith: customer domain (User/Customer/Account/Profile/CreditCard), CredentialsRepository/CustomerRepository + JPA adapters/entities, SignOnService/CustomerService, CustomerController, CustomerUserDetailsService; dropped app_user/customer tables from schema.sql/data.sql; removed 12 monolith customer tests.
- Endpoints config (no hardcoded URLs): services.customer.{base-url,endpoints.*} in application.yml → ServiceEndpoints @ConfigurationProperties (@ConfigurationPropertiesScan). RestClient bean.
- CustomerServiceClient: login (→AuthResult{token,customerId,roles}), register, getCustomer(bearer). All URLs from config.
- AUTH DELEGATED: CustomerServiceAuthProvider calls client.login() on form-login; JWT kept as the Authentication credential; roles from result. No local UserDetailsService/credentials. SecurityConfig uses ProviderManager(provider).
- StorefrontController: register → client; checkout reads customer email/address via client using the session JWT.
- Verified live (both services up): register mia via monolith→customer-service, login (delegated), add-to-cart→checkout (email from customer-service), wrong pw→login?error. 43 monolith tests green (SecurityTest rewritten with @MockBean client).
- TRADE-OFF (accepted): monolith now HARD-depends on customer-service for auth/register — no local fallback (that was the point). If customer-service is down, login/register fail.

## customer-service-client SDK (importable)
Extracted the client into a standalone Maven library `customer-service-client` (com.petstore:customer-service-client:1.0.0), installed to local .m2, imported by the monolith.
- Owns the API contract: CustomerServiceEndpoints (hardcoded PATH constants = the contract), CustomerDtos (AuthResult/AccountDto/CardDto/ProfileDto/RegisterRequest/CustomerView), CustomerServiceClient (login/register/getCustomer/updateAccount/profile/card).
- Base URL is a constructor arg (env-specific, default localhost:8081) — NOT hardcoded; only paths are. Thin deps: spring-web + spring-context (no Boot starter).
- Monolith: added dependency, deleted its inline client, HttpClientConfig now @Bean-produces the SDK client from ServiceEndpoints base-url; AuthProvider + StorefrontController use SDK DTOs.
- Verified: 43 monolith tests green; live register+login (leo) via monolith→SDK→customer-service.

## customer-service produces its own client SDK (multi-module, single-sourced contract)
Restructured customer-service into a multi-module Maven project so ONE build produces both the SDK and the server, single-versioned:
- customer-service/ (parent pom, packaging=pom) → modules: client/ (customer-service-client SDK) + app/ (Spring Boot server).
- `mvn install` at the parent builds client → installs to .m2 → builds app (which depends on client).
- SERVER reuses the SDK contract: CustomerController maps use CustomerServiceEndpoints.* path constants; request/response bodies are CustomerDtos records (validation constraints live on the SDK DTOs, provider supplied by the app). Server and every client now provably share one contract — no drift.
- SDK gained jakarta.validation-api (constraints on RegisterRequest/AccountDto); RestClient/DTOs unchanged.
- Verified: parent build green, 14 customer-service tests pass, server boots + login/validation work via SDK constants; monolith still builds against the SDK (43 tests green).

## warehouse-service extracted (admin.ear + supplier.ear merged)
Third service: merges back-office order approval (admin) + fulfilment/inventory (supplier). Port 8082.
- **Ownership (split, as decided):** warehouse owns order_status + inventory (+ its own order read-model); monolith keeps purchase_order/line_item CREATION. They sync via JMS.
- **Contract:** monolith checkout publishes the FULL PurchaseOrder as JSON to PurchaseOrderQueue (was: order-id only). warehouse consumes (OrderListener), stores read-model, auto-approves (<500 US /<50000 JP) or holds PENDING, reserves stock (pessimistic lock), ships. Idempotent.
- **Shared broker:** monolith hosts embedded Artemis with a TCP acceptor on :61616 (EmbeddedBrokerConfig / ArtemisConfigurationCustomizer); warehouse connects as native client. One shared PurchaseOrderQueue across services.
- **Auth:** warehouse verifies the customer-service JWT (JwtAuthFilter, Bearer or jwt cookie), all /warehouse/** + /api/** require ROLE_ADMIN. No login of its own.
- **UI:** Thymeleaf /warehouse/orders (approve/deny) + /warehouse/inventory (levels) — replaces admin Swing/JWS + supplier RcvrRequestProcessor.
- **Monolith slimmed:** deleted fulfilment/, admin/, OrderApprovedListener/Event, OrderStatusService, ApprovalPolicy, OrderStatus, order_status + inventory tables. Order status endpoint removed (now warehouse /api/orders/{id}/status). 29 tests green.
- **Verified live (3 services + shared broker):** register/login (customer-service) → checkout (monolith) → JMS → warehouse: small order auto-ships COMPLETED, large order PENDING → admin approves (ADMIN JWT) → COMPLETED, non-admin/no-token → 403, inventory decremented 100→99.

## Corrections
- Earlier PPT slide 16 stated "getSubTotal THROWS on items removed from catalog." **Incorrect** — verified in source: legacy `getItems()` catches `CatalogException` and SILENTLY SKIPS the dangling item; subtotal just ignores it. Migration preserves the skip behavior; tests pin it. (Slide should be corrected.)

## Notes
- Pattern decisions (#15, #16) will be surfaced to the user when the relevant module is reached (per constraint #7).
- Constraints persisted in memory: `petstore-migration-constraints.md`.

## DB-backed roles + ADMIN/SUPPLIER split + user management (Batch 25/26)
Replaced the hardcoded username→role check with DB-backed roles and split the single
legacy "administrator" role (shared by admin.ear + supplier.ear) into two, matching the
two distinct legacy JOBS the source revealed:
- **Legacy reality:** there was ONE role "administrator". admin.ear = order approval (OPCAdminFacade); supplier.ear = the "receiver" job (RcvrRequestProcessor read `qty_<itemId>` params → setQuantity, i.e. restock inventory). Same credential, two consoles.
- **New role model:** `enum Role { USER, SUPPLIER, ADMIN }` persisted on app_user (VARCHAR, default USER).
  - **ADMIN** = order approval (/warehouse/orders, /api/orders) + user management (/warehouse/users, customer-service /admin/users).
  - **SUPPLIER** = inventory view + restock (/warehouse/inventory, /api/inventory) — the receiver job. ADMIN also allowed (hasAnyRole).
  - **USER** = storefront customer; no warehouse access.
- **Roles now sourced from DB:** AuthController.login reads user.getRole() (was: hardcoded "j2ee"→USER). JWT carries the DB role. Seeds: j2ee/USER, supplier/SUPPLIER, admin/ADMIN.
- **RoleGrantPolicy (Strategy pattern — chosen with user):** `canGrant(granter, target)`. ADMIN→{ADMIN,SUPPLIER,USER}; everyone else→none. So admin can create admins AND suppliers; a supplier creating an ADMIN is refused. Enforced in UserAdminService; ForbiddenRoleGrantException→403.
  - *Approaches considered:* (a) inline if/switch in the controller — rejected (logic scattered, hard to test/extend); (b) Strategy/policy object — CHOSEN (single responsibility, unit-testable, swappable if the grant matrix grows).
- **customer-service /admin/users API** (ADMIN-gated in SecurityConfig): GET list, POST create {userName,password,role}, PUT {userName}/role. AdminExceptionHandler maps forbidden_grant→403, duplicate→409, invalid→400. **Ordered @Order(HIGHEST_PRECEDENCE)** so it wins over the global RestExceptionHandler, which maps IllegalArgumentException→404 (a pre-existing quirk) — otherwise a too-short-password validation error surfaced as a misleading 404. Now returns a clean 400.
- **Warehouse "Manage Users" UI (ADMIN-only):** /warehouse/users page (list + create form). Warehouse does NOT own accounts — it PROXIES to customer-service /admin/users, forwarding the admin's JWT (from the jwt cookie) as a Bearer token. Single source of truth for accounts (customer-service); customer-service re-enforces ADMIN + RoleGrantPolicy. Nav "Users" link on orders page.
- **Sign-out:** POST /warehouse/logout clears the jwt cookie → redirect to login?loggedout. "Sign Off" button on every warehouse page.
- **API vs browser error codes (fixed):** SecurityConfig exceptionHandling now branches on /api/ prefix — API paths return clean 401 (authenticationEntryPoint) / 403 (accessDeniedHandler) as JSON; browser /warehouse/** paths redirect to /warehouse/login (or ?forbidden). Status is written directly (not sendError) to avoid the /error re-dispatch bouncing an API 401 back into a 302 login redirect.
- **Verified live (all 3 services + shared broker):**
  - supplier→/api/inventory 200, →restock 200, →/api/orders 403; user→/api/inventory 403; no-token→401; admin→both 200.
  - admin creates SUPPLIER via /admin/users→201; supplier creating ADMIN→403; too-short pw→400; admin creates ADMIN admin2→201; admin2 approves→200.
  - Warehouse Manage Users UI (cookie auth): admin lists users, creates SUPPLIER 'recv1'→then recv1 logs in, restocks 200, approve 403. Supplier blocked from /warehouse/users→redirect login?forbidden.

## catalog-service extracted (4th service; multi-module SDK, monolith is pure client)
Split the catalog/browse bounded context out of the monolith into its own service, mirroring the customer-service decomposition (parent pom → client SDK + app). Port 8083.
- **Ownership (DB-per-service):** catalog-service owns the 6 locale-split tables (category/category_details, product/product_details, item/item_details) + seed. Dropped from the monolith's schema.sql/data.sql.
- **Structure:** catalog-service/ (parent, packaging=pom) → client/ (catalog-service-client SDK) + app/ (Spring Boot :8083). `mvn install` builds client → .m2 → app.
- **SDK (the contract, single-sourced):** CatalogServiceEndpoints (path constants), CatalogDtos (CategoryDto/ProductDto/ItemDto + concrete CategoryPage/ProductPage/ItemPage records — concrete, not generic PageDto<T>, so RestClient deserializes element types without ParameterizedTypeReference), CatalogServiceClient (getCategory/getCategories/getProduct/getProducts/getItem/getItems/searchItems). Server REUSES the SDK DTOs + endpoint constants in CatalogApiController, so server can't drift from clients.
- **New REST API (catalog-service app):** GET /api/categories, /api/categories/{id}, /api/categories/{id}/products, /api/products/{id}, /api/products/{id}/items, /api/items/{id}, /api/items?keyword=. Public (browse was always public). Single-entity miss → 404 (client maps → Optional.empty); pages → 200 with (possibly empty) list; locale via ?lang=en_US.
- **Behaviour preserved:** CatalogService/JpaCatalogRepository/entities lifted verbatim; legacy contract intact (Optional.empty/EMPTY_PAGE on miss, locale-specific reads, Item accessor quirks getAttribute()==attr1, getListCost()==listPrice). 9 characterization tests moved to catalog-service + 7 new API-contract tests (MockMvc) = 16 green.
- **DECISIONS asked & chosen (user):**
  - *Monolith wiring:* DIRECT CatalogServiceClient injection (chosen) vs HTTP-adapter-behind-port. Deleted the CatalogRepository port + JPA + local CatalogService; inject the SDK client straight into CatalogController + CartService (matches how the monolith uses CustomerServiceClient).
  - *Cart N+1:* KEEP per-item calls (chosen) vs add a batch getItems(ids). CartService.getItems() still calls getItem(id) per line (now a remote call), preserving the silent-skip-dangling behaviour verbatim. (Batch op noted as a future optimization.)
- **Monolith changes:** kept the framework-free view models (Item/Category/Product) because Thymeleaf reads them via legacy getters (i.listCost, item.attribute) — records don't work in SpringEL (learned earlier). Added CatalogViewMapper (SDK DTO → view model). CatalogController + CartService now call the client; deleted local CatalogService/port/JPA (9 files) + Page.java (unused). No hardcoded catalog URLs — paths are SDK constants, base-url from config (services.catalog.base-url, ${CATALOG_SERVICE_URL:http://localhost:8083}). Added catalog client bean to HttpClientConfig, catalog Service to ServiceEndpoints.
- **Tests adapted:** CartCharacterizationTest → offline unit test mocking CatalogServiceClient with the same seed items (10 green); OrderCharacterizationTest → @MockBean CatalogServiceClient seeded EST-1/EST-5 (3 green); monolith suite 20 green total.
- **Verified live (4 services + broker):** browse home/category/product/item/search all render from catalog-service; login (customer-service) → add EST-1+EST-5 → cart resolves names/prices via catalog client (Angelfish 16.5, Bulldog 18.5, subtotal 35.0) → checkout → JMS → warehouse received order 1001 (2 lines) → APPROVED → COMPLETED.
- **Topology now:** monolith :8080 (storefront UI + cart + order creation; pure client of customer + catalog) · customer-service :8081 (identity/JWT) · warehouse-service :8082 (fulfilment/inventory/admin) · catalog-service :8083 (catalog) · shared Artemis broker :61616.

## cart-service extracted (5th service; in-memory + 15-min sliding TTL)
Split the shopping cart out of the monolith into its own service + SDK, mirroring the catalog/customer decomposition (parent pom → client + app). Port 8084. The interesting part: cart is STATEFUL per-session in-memory state, not a stateless DB service.
- **Legacy behaviour confirmed from source first:** legacy ShoppingCartLocalEJB is a stateful session EJB holding a HashMap<itemId,qty>, held by ShoppingClientFacadeLocalEJB in the HttpSession. signon-config.xml protects ONLY customer.screen/customer.do/enter_order_information.screen/signon_welcome.screen — cart URLs are NOT protected, so UNSIGNED users CAN add to cart; sign-in is forced only at checkout. Cart carried across login via session continuity (no merge).
- **Storage decision (user):** in-memory with a 15-min TTL (NOT Redis, NOT DB). CartStore = ConcurrentHashMap<cartId, {LinkedHashMap<itemId,qty>, lastAccess}> with a @Scheduled sliding-TTL sweeper (idle > 15 min → evicted) — a faithful session-timeout analog. State lives only in the cart-service process memory; carts are deliberately ephemeral (matches legacy). Caveat logged: single-instance only (in-memory won't survive scale-out/restart — would need Redis for that; accepted for this exercise).
- **Cart identity decision (user):** dedicated SecureRandom cartId COOKIE (not HttpSession-scoped). CartIdFilter (monolith) mints a 128-bit SecureRandom hex id on first request if absent, sets it as an HttpOnly session cookie, and stashes it on the request. Works for anonymous shoppers and survives login (decoupled from JSESSIONID) — so the anonymous cart carries over sign-in with no merge step, exactly like legacy. Server-issued only (never client-chosen) to prevent cart hijacking, same rule as a session id.
- **Resolution decision (user):** cart-service resolves item names/prices + computes subtotal itself via catalog-service-client (faithful port of legacy ShoppingCartEJB.getItems()/getSubTotal()) — the true full extraction. So cart-service → catalog-service dependency (mirrors the legacy cart→catalog component dependency).
- **SDK (cart-service-client):** CartServiceEndpoints (paths + X-Cart-Id header const), CartDtos (CartItemView, CartView records), CartServiceClient (view/addItem/addItem(qty)/setQuantity/deleteItem/empty — all cartId-based). Server reuses the SDK DTOs + constants (single-sourced contract). REST API: GET /api/cart, POST/PUT/DELETE /api/cart/items, DELETE /api/cart; cartId via X-Cart-Id header.
- **Behaviour preserved (11 characterization tests in cart-service):** addItem=set-to-1 (reset not increment), addItem(qty)=overwrite, setQuantity(qty<=0)=silent remove, view() skips dangling items, count=DISTINCT lines (dangling id still counts as a raw line), subtotal=Σ listPrice*qty; carts isolated by cartId.
- **Monolith changes:** CartService is now a THIN ADAPTER (reads request cartId via RequestContextHolder, delegates to CartServiceClient, maps CartItemView→CartItem view model). Same method surface (getItems/getSubTotal/getCount/quantityOf/addItem/updateItemQuantity/deleteItem/empty) so CartController/OrderService/StorefrontController/GlobalModelAdvice are UNCHANGED. Deleted the in-memory LinkedHashMap. Added CartIdFilter, cart client bean (HttpClientConfig), cart Service to ServiceEndpoints, services.cart.base-url. No hardcoded cart URLs.
- **Tests adapted:** monolith CartCharacterizationTest (which needed a real in-memory cart) → deleted; replaced with CartServiceAdapterTest (7 tests: verifies cartId resolution + SDK delegation + DTO→CartItem mapping). OrderCharacterizationTest → @MockBean CartServiceClient + bound MockHttpServletRequest carrying cartId (3 green). Monolith suite 17 green (7 adapter + 3 order + 7 security).
- **Verified live (5 services + broker):** ANONYMOUS add EST-1+EST-5 (no login) → cartId cookie issued (128-bit hex) → cart persists across requests → state confirmed living in cart-service :8084 (queried directly) → login as j2ee KEEPS the anon cart (subtotal 35.0 still there, no merge) → checkout → JMS → warehouse received order 1001 (2 lines) → cart emptied.
- **Topology now (5 apps):** monolith :8080 (storefront UI + order creation; pure client of customer/catalog/cart) · customer-service :8081 (identity/JWT) · warehouse-service :8082 (fulfilment/inventory/admin) · catalog-service :8083 (catalog) · cart-service :8084 (cart, in-memory+TTL) · shared Artemis broker :61616. Three importable SDKs: customer/catalog/cart client.

## CORRECTION: cart is an EMBEDDABLE LIBRARY, not a standalone service (in-process)
User correction (right call): cart should NOT be a standalone :8084 server — it runs WITHIN the monolith's JVM. The "client" package should BE the implementation (abstract all APIs + business logic as an embeddable library), imported and run in-process. Reverted the previous "cart-service :8084 app + HTTP client" design.
- **Why:** unlike customer/catalog (real remote, DB-backed services whose client is a thin HTTP proxy holding NO logic), cart is session-local in-memory state with no DB and no reason to be remote. A standalone server bought nothing and cost a network hop for session-local data + a separate process + single-instance-anyway. So for cart, the library IS the logic, running in-process.
- **What changed (cart-service collapsed {client+app+server} → one library):**
  - DELETED: the :8084 Spring Boot app, CartApiController (REST), CartServiceApplication, application.yml, and the HTTP-proxy CartServiceClient + CartServiceEndpoints (REST contract). No server, no HTTP.
  - cart-service is now a SINGLE module: cart-service-client = embeddable library holding CartDtos (CartItemView/CartView), CartStore (in-memory ConcurrentHashMap + 15-min sliding TTL via a plain daemon ScheduledExecutorService — framework-free, AutoCloseable), and CartOperations (the business logic; add/set/delete/view/empty). Depends on catalog-service-client (item resolution — catalog IS genuinely remote). Framework-free (no Spring/Boot); JUnit/Mockito test scope only. 12 tests (incl. a TTL-sweep test).
- **Monolith wiring (in-process, no HTTP):** CartConfig @Bean-wires CartStore (destroyMethod="close" so the sweeper thread stops on shutdown) + CartOperations (given the CatalogServiceClient bean). CartService adapter now delegates to the in-process CartOperations (not an HTTP client). Removed the cart HTTP client bean from HttpClientConfig, the cart Service from ServiceEndpoints, and services.cart.base-url from yml; added root-level cart.ttl-minutes/sweep-interval-seconds config. CartIdFilter (SecureRandom cartId cookie) UNCHANGED — still keys carts, keeps anonymous + login-carryover.
- **Distinction captured:** customer/catalog client = thin HTTP proxy (logic remote); cart "client" = embeddable library (logic in-process). Same word "client", two different roles — driven by whether the thing owns remote state.
- **Tests:** monolith CartServiceAdapterTest now mocks CartOperations (in-process) instead of the HTTP client (7 green); OrderCharacterizationTest @MockBean CartOperations (3 green). Monolith suite 17 green. Cart library 12 green.
- **Verified live (4 services + broker; NO :8084):** :8084 confirmed down; anonymous add EST-1+EST-5 → cartId cookie → resolved via catalog-service → login keeps cart (35.0) → checkout → JMS → warehouse order 1001 → cart emptied. All cart ops in-process.
- **Topology now (4 apps + 1 embedded lib):** monolith :8080 (storefront + order creation + EMBEDS cart library) · customer-service :8081 · warehouse-service :8082 · catalog-service :8083 · shared Artemis :61616. SDKs: customer-service-client + catalog-service-client (thin HTTP proxies), cart-service-client (embeddable in-process library).

## Rename: cart-service-client → cart-lib (package com.petstore.cart)
Renamed the cart module to reflect what it is (an embeddable in-process library, neither a "service" nor a "client"):
- Directory cart-service/ → cart-lib/; flattened the pointless parent+child multi-module nesting into ONE project (standard src/main + src/test layout).
- Maven artifact cart-service-client → cart-lib (com.petstore:cart-lib:1.0.0). Old artifact purged from .m2.
- Java package com.petstore.cart.client → com.petstore.cart (dropped the misleading ".client"). CartDtos/CartOperations/CartStore + test moved accordingly.
- Monolith updated: pom dependency → cart-lib; imports com.petstore.cart.client.* → com.petstore.cart.* (CartConfig, CartService, CartServiceAdapterTest, OrderCharacterizationTest). Monolith's own cart packages (.service/.web/.domain/.config) untouched.
- Verified: cart-lib 12 tests green + installed; monolith 17 tests green; monolith boots and cart works in-process (add EST-1 → Angelfish/16.5).

## Decision rule: standalone service vs in-process library (when to extract)
Captured the principle behind the splits (also added as PPT slide 47). Extract a component to its own SERVICE when it owns remote/shared/durable state OR needs independent scaling/deployment; otherwise keep it as an in-process LIBRARY. Consistency with sibling components is NOT a reason.
- customer / catalog / warehouse → SERVICES: each owns a DB (accounts / products / orders+inventory) and has an independent scaling or deployment reason (JWT issuer; read-heavy cacheable; different traffic + roles).
- cart → LIBRARY (cart-lib, in-process): session-local ephemeral in-memory state, no DB, on the checkout hot path. A standalone server would add a network hop for a user's own scratchpad, and a stateful in-memory "service" is single-instance (or forces Redis) — extra distributed-state cost for zero gain.
- "Client" was the wrong frame for cart: a client is a thin proxy to a REMOTE owner of the logic; cart IS the state + logic, so it's an embeddable library, not a client.
- Cart would FLIP to a service only if a real requirement appears: carts must survive across devices/sessions (durable), or the storefront must scale horizontally with carts shared across instances → then back it with Redis/DB and extract, because it would finally own SHARED state.

## Split warehouse-service → order-approval (warehouse) + inventory-service (6th app)
User: legacy admin.ear (order approval) and supplier.ear (inventory + fulfilment) were TWO different apps for two different roles updating two different things — better to have two apps. Agreed (passes our service-vs-library rule cleanly: separate state + separate role + separate scaling + legacy precedent). Re-split the merged warehouse-service.
- **Legacy reality confirmed from source:** it was actually THREE pieces — opc.ear (OrderApprovalMDB/PurchaseOrderMDB/OPCAdminFacade = the approval engine), petstoreadmin.ear (a Swing GUI calling OPCAdminFacade, no logic), and supplier.ear (SupplierOrderMDB + OrderFulfillmentFacade = receive PO/fulfil/ship/invoice; RcvrRequestProcessor = restock UI). They were decoupled by JMS: opc approved → queue → supplier fulfilled → invoice back to opc.
- **New split (kept warehouse-service, extracted inventory-service :8085):**
  - warehouse-service :8082 (ADMIN) — order intake + approval decision + status (opc + admin GUI). Owns wh_order. On approve (auto or manual) publishes ApprovedOrderQueue; consumes InvoiceQueue → COMPLETED.
  - inventory-service :8085 (SUPPLIER) — inventory table + pessimistic-locked reserve + fulfilment + receiver restock UI (supplier.ear). Consumes ApprovedOrderQueue, ships, publishes InvoiceQueue.
- **Fulfilment seam decision (user): JMS-triggered, in inventory-service** (not synchronous, not kept in warehouse). Faithful to legacy opc↔supplier event flow; no client SDK needed between the two (pure messaging).
- **Event flow:** checkout → PurchaseOrderQueue → warehouse (store; auto-approve <500 US/<50000 JP else PENDING) → ApprovedOrderQueue → inventory (reserve stock all-or-nothing, ship) → InvoiceQueue → warehouse (APPROVED→COMPLETED). Approve publishes AFTER_COMMIT (ApprovalGateway + TransactionSynchronization) so a rolled-back approval never dispatches — same publish-before-commit fix as before. JMS type-id mappings: order/approvedOrder/invoice, shared across both services (decouples package names).
- **What moved OUT of warehouse:** InventoryStore/JpaInventoryStore/InventoryEntity + inventory JPA repo, inventory table + seed, ship() logic, inventory UI (/warehouse/inventory) + inventory API (/api/inventory) + inventory nav links + template. Warehouse SecurityConfig dropped the SUPPLIER inventory matcher (now ADMIN-only for orders+users). FulfilmentService reduced to intake+approval-decision; AdminService.approve dispatches via JMS instead of shipping in-process.
- **inventory-service (new):** own H2 (inventory table), JWT verify (SUPPLIER/ADMIN), own login (/inventory/login delegates to customer-service, jwt cookie) + receiver UI (/inventory view+restock) + /api/inventory. Backorder = all-or-nothing (short stock → invoice shipped=false, warehouse leaves order APPROVED for retry).
- **Verified live (6 apps + broker):** auto-approve small order → fulfil → COMPLETED, EST-1 100→99; large order 580.5 → PENDING → admin approve → JMS → fulfil → COMPLETED, EST-18 5→2; SUPPLIER→warehouse orders 403 / inventory 200; ADMIN→both 200; SUPPLIER restock EST-2 +10→11.
- **Topology now (6 apps + 1 lib + broker):** monolith :8080 (storefront+order-creation, embeds cart-lib) · customer-service :8081 · warehouse-service :8082 (ADMIN approval) · catalog-service :8083 · cart-lib (in-process) · inventory-service :8085 (SUPPLIER fulfilment/inventory) · Artemis :61616. Queues: PurchaseOrderQueue, ApprovedOrderQueue, InvoiceQueue.

## Staff/customer identity separation — staff off customer-service (per-service creds)
User: customer-service is for real customers; suppliers (and admins) should have their OWN databases + usernames/passwords. Correct — previously a supplier/admin was just a row in customer-service's app_user with a SUPPLIER/ADMIN role, and both staff services DELEGATED login to customer-service. That leaked back-office staff into the customer identity store and violated DB-per-service.
- **Decision (user): each staff service owns its own credentials** (not a shared staff-auth service), and **applies to BOTH supplier and admin now** (customer-service becomes customers-only).
- **inventory-service** now owns SUPPLIER identities: staff_user table + seed (supplier/supplier BCrypt), StaffUserEntity/Repository, StaffAuthService (BCrypt authenticate), its OWN JwtService (own secret `inventory.jwt.secret`, HS + roles claim). Login authenticates locally (no customer-service call) and issues its own JWT into the jwt cookie; JwtAuthFilter verifies with the local JwtService. Dropped services.customer + the shared petstore.jwt secret.
- **warehouse-service** now owns ADMIN identities: same pattern (staff_user seed admin/admin, own `warehouse.jwt.secret`). Login authenticates locally + issues own JWT. Manage Users UI repointed from the customer-service /admin/users proxy to the LOCAL StaffAuthService (create/list ADMINs); role fixed to ADMIN (suppliers are managed by inventory-service). Logout switched to POST.
- **customer-service** trimmed to customers only: removed the supplier + admin seed rows from data.sql (now just j2ee/USER). Role enum + /admin/users left in place (harmless), 14 tests still green.
- **Three independent issuers now, three secrets:** customer-service (customers), warehouse-service (admins), inventory-service (suppliers). A token from one is NOT accepted by another (different signing secret) — verified.
- **Verified live:** supplier→inventory own-login 302+cookie, /inventory 200; admin→warehouse own-login 302+cookie, /warehouse/orders 200; customer-service login supplier/admin → 401 (gone), j2ee → 200; customer JWT → inventory /api/inventory 401 and → warehouse /api/orders 401 (cross-service rejection); full order flow still COMPLETES (order 1003 checkout→approve→fulfil→invoice→COMPLETED).
- **Consequence:** the 3 staff/customer stores are fully independent. Trade-off (accepted): JWT issue/verify + BCrypt + staff table is now duplicated per staff service rather than centralized (the alternative was a shared staff-auth service; user chose per-service ownership).

## Central auth-service (IdP) + auth-client library — one issuer, RS256, verify-only everywhere
User: should ONE customer-service handle all auth? Answered NO (that repeats the original mistake of putting staff in the customer DOMAIN service). The right model is a DEDICATED identity provider that is NOT the customer domain service. Built it, with an importable auth-client library.
- **Decisions (user):** RS256 asymmetric signing (auth-service holds the PRIVATE key = sole minter; verifiers hold only the PUBLIC key, so they physically cannot forge tokens — real IdP pattern). auth-client library ships verify + login (verifier + Spring filter + claims + AuthClient).
- **auth-service :8086 (multi-module: client + app):** the ONE credential store (account table: userName, bcrypt password, userId, role) for ALL users — customers + staff in one place, distinguished by role (USER/SUPPLIER/ADMIN), NOT by scattering creds across services. /auth/login mints RS256 (JwtIssuer, private key from classpath PEM); /auth/accounts provisions a credential (used by customer registration). Seeds: j2ee/USER, supplier/SUPPLIER, admin/ADMIN. RSA-2048 keypair generated with openssl (PKCS#8 private in app resources; public bundled in auth-client).
- **auth-client library (com.petstore:auth-client):** AuthClaims (decoded token = userId/username/roles), JwtVerifier (RS256 public-key verify), AuthJwtFilter (ready-to-wire verify-only Spring filter — Bearer or jwt cookie → ROLE_* authorities), AuthPublicKey.bundled() (loads the bundled public PEM — zero-config verifier), AuthClient (login + provision, calls auth-service). 3 tests incl. forged-token-rejected.
- **All services became VERIFY-ONLY, holding NO credentials:** each imports auth-client, deletes its own JwtService/JwtAuthFilter/credential store, and wires `new AuthJwtFilter(new JwtVerifier(AuthPublicKey.bundled()))`. Login pages delegate to auth-service via AuthClient. Removed: inventory-service staff_user + own secret; warehouse-service staff_user + own secret + local Manage Users (account mgmt belongs to the IdP); customer-service app_user + SignOnService + JwtService + AuthController + UserAdmin + RoleGrant + domain User/Role. customer-service now owns ONLY customer DOMAIN data (profile/account/card), keyed by the userId auth-service mints; registration calls auth.provision(USER) then stores the aggregate. Monolith form-login delegates to AuthClient (was customer SDK); carries userId in auth details for customer-service lookups (customer keyed by userId now, not username).
- **user_data replication answer (user asked):** credentials NOT replicated (only auth-service). Identity claims CARRIED in the token (not stored per service). Domain attributes stay in their one owning service; others hold only the userId reference. Verified: no service but auth-service has a credential store.
- **Verified live (7 procs + broker):** all 3 logins via auth-service RS256 (200); one token verified across all services (supplier→inventory 200, admin→warehouse 200, supplier→warehouse 403 role, forged token 401); customer-service /auth/login GONE (403 — no longer an issuer); UI logins delegate + set cookie (supplier→inventory, admin→warehouse both 302→200); customer register provisions cred in auth-service (newbie then logs in at auth-service 200); full order flow round-trips PurchaseOrderQueue→ApprovedOrderQueue→InvoiceQueue with orders 1001/1002 → COMPLETED. Tests: auth-client 3, customer-service 8, monolith 15 — all green.
- **Topology now (7 apps + 1 lib + broker):** monolith :8080 (storefront, embeds cart-lib) · customer-service :8081 (customer DOMAIN, verify-only) · warehouse-service :8082 (ADMIN approval, verify-only) · catalog-service :8083 · inventory-service :8085 (SUPPLIER, verify-only) · auth-service :8086 (the ONE issuer) · Artemis :61616. Libraries: cart-lib (in-process), auth-client (verify+login), customer/catalog client SDKs.
- **Trade-off (accepted):** the previous per-service credential duplication is GONE — one issuer, one credential store, one place for rotation/revocation/policy. auth-service is another process to run, but it's the correct IdP architecture.

## Shared messaging library (petstore-messaging) + JMS topic fan-out (InvoiceTopic)
User: create a JMS library holding all config + destinations so apps just pick which topic to send to; and what should the message format be. Built it, converted the invoice hop to a topic, and added a 2nd subscriber to prove fan-out.
- **Problem it fixed:** JmsConfig (the Jackson converter + _type mapping) was DUPLICATED in 3 services, destination names were hardcoded string literals in 2+ places each, and the message records (OrderMessage/ApprovedOrderMessage/InvoiceMessage) were COPY-PASTED across monolith/warehouse/inventory — the exact drift risk that causes silent _type mismatches.
- **petstore-messaging library (com.petstore:petstore-messaging):**
  - Destinations — the ONE place for names + kind: PURCHASE_ORDER (queue), APPROVED_ORDER (queue), INVOICE = topic("InvoiceTopic"). Destination record encodes queue-vs-topic so callers can't mix them.
  - Message format decision (user): EVENT ENVELOPE + typed payload. EventMeta { eventId (dedup/idempotency), type (also the JMS _type id), occurredAt (ISO-8601), correlationId (trace across HTTP→JMS) } embedded in each event record: PurchaseOrderEvent, OrderApprovedEvent, InvoiceEvent. JSON via Jackson (records), JavaTimeModule for Instant. Events.meta(type[,correlationId]) mints metadata.
  - MessagingConfig — ONE MappingJackson2MessageConverter with the full _type→class map (TYPE_IDS), plus queueFactory (pubSubDomain=false) and topicFactory (pubSubDomain=true) for @JmsListener(containerFactory=...).
  - MessagePublisher — publisher.publish(dest, event): routes to the topic or queue template by dest.topic(), stamps _type from the event. Apps never touch JmsTemplate/pub-sub flags/headers.
- **Queue vs topic rule applied:** command flows (one consumer) stay QUEUES (PurchaseOrderQueue, ApprovedOrderQueue). The invoice/completion is an EVENT many care about → TOPIC (InvoiceTopic), restoring the legacy Pet Store InvoiceTopic (legacy: supplier published invoice to a topic, OPC's InvoiceRcvr subscribed). Confirmed from legacy source: InvoiceTopic was the ONLY JMS topic; everything else was a queue.
- **All services migrated:** monolith publishes PurchaseOrderEvent; warehouse consumes it (queue) + publishes OrderApprovedEvent (queue) + SUBSCRIBES to InvoiceTopic (topic) to complete the order; inventory consumes OrderApproved (queue) + publishes InvoiceEvent to InvoiceTopic (topic). Deleted all 3 local JmsConfigs + all copy-pasted message records. Each service widened scanBasePackages to include com.petstore.messaging (their base pkg is com.petstore.<svc>, so the library's com.petstore.messaging beans weren't auto-scanned — the monolith worked only because its base is com.petstore).
- **notification-service :8087 (NEW, fan-out demo):** a 2nd independent InvoiceTopic subscriber that "emails" the customer on completion. Added with ZERO changes to the publisher (inventory) — the point of pub/sub.
- **Verified live:** placed order 1001 → inventory published one InvoiceEvent to InvoiceTopic → BOTH subscribers received their own copy: warehouse completed the order (status COMPLETED via API) AND notification-service logged "EMAIL → customer for order 1001: 'Your order (total 16.5) has shipped!'". Full enveloped pipeline PurchaseOrderQueue→ApprovedOrderQueue→InvoiceTopic works. Library 4 tests green.
- **Topology now (8 apps + 2 embed libs + broker):** monolith :8080 (hosts broker, embeds cart-lib) · customer-service :8081 · warehouse-service :8082 · catalog-service :8083 · inventory-service :8085 · auth-service :8086 · notification-service :8087 · Artemis :61616. Libraries: petstore-messaging, auth-client, cart-lib, + customer/catalog client SDKs. Queues: PurchaseOrderQueue, ApprovedOrderQueue. Topic: InvoiceTopic.

## Rename: monolith petstore-spring → petstore-app-v1
Renamed the storefront module (dir + Maven artifactId/name + spring.application.name) to petstore-app-v1. Java package com.petstore unchanged (module name ≠ package). It's a top-level executable app (no other module imports it), so only doc/config refs needed updating (README, MIGRATION_PLAN, ppt). Rebuilt (15 tests green), jar now petstore-app-v1-1.0.0.jar, restarted on :8080 (still hosts the shared Artemis broker).

## Rename: warehouse-service → admin-office-service
Renamed the back-office order-approval service (dir + Maven artifactId/name + spring.application.name) to admin-office-service — it's the ADMIN order-approval app (from legacy admin.ear + opc.ear); inventory/supplier work already lives in inventory-service. Java package com.petstore.warehouse + class names left unchanged (self-contained; module name ≠ package). Rebuilt, jar now admin-office-service-1.0.0.jar, restarted on :8082 (unchanged port; verify-only via auth-client, consumes PurchaseOrderQueue, subscribes InvoiceTopic, publishes ApprovedOrderQueue).

## notification-service: real email composition (legacy MailInvoiceMDB parity)
Added email functionality to notification-service mirroring the legacy customer-relations MailInvoiceMDB (+ mailer component).
- **Email carried through the event chain (user choice):** added userId+emailId to OrderApprovedEvent and emailId to InvoiceEvent in petstore-messaging; admin-office-service ApprovalGateway + inventory OrderApprovedListener now thread emailId/userId from the PurchaseOrderEvent → invoice, so the InvoiceEvent is self-contained (matches legacy, where the invoice message carried customer info). No runtime lookup needed at notification time.
- **Transport (user choice): Spring MailSender-style port + logging dev-sender.** MailSender interface (the legacy MailHelper/MailerMDB seam) + LoggingMailSender (default, @ConditionalOnMissingBean) logs the fully-composed email — no SMTP/infra. Swappable to real SMTP (JavaMailSender + spring.mail.*) by dropping in an smtpMailSender bean; logging one backs off.
- **OrderMailComposer** = the composition half of MailInvoiceMDB: subject "Java Pet Store Order Shipped: <orderId>" (legacy MAIL_SUBJECT prefix) + body; backorder variant for shipped=false. Email = legacy mailer.ejb.Mail (to/subject/body). Composition split from sending → unit-testable without transport.
- **InvoiceNotificationListener** now composes + sends (was inline log). Still a topic subscriber on InvoiceTopic alongside admin-office-service (fan-out preserved).
- **Verified live:** placed order → notification-service logged the composed email: To buyer@petstore.com, Subject "Java Pet Store Order Shipped: 1001", body with order # + total $16.50; order COMPLETED. (Recipient came from the monolith checkout's email — carried through the chain end-to-end.)
- Known minor: monolith checkout used its fallback <username>@petstore.com rather than the registered profile email (profile lookup by userId didn't resolve that request) — email plumbing is correct; the source address is a separate monolith profile-fetch detail.

## GOAL RAISED: full legacy feature parity
User asked to make the app have ALL functionality like the legacy app. Next step is a gap analysis (legacy features vs current) to plan remaining work rather than guess — to be produced and worked through.

## Multi-locale storefront (en/ja/zh) — legacy multi-lingual parity
Restored the legacy multi-lingual store. Backend was already locale-aware (catalog-service locale-split tables + lang API param); added the missing pieces.
- **Locale data:** seeded ja_JP + zh_CN rows in catalog-service category_details/product_details/item_details (translations of the en_US catalog), mirroring legacy Populate-UTF8.
- **Locale persistence + switcher (user choice):** Spring i18n in petstore-app-v1 (WebConfig) — CookieLocaleResolver ("lang" cookie, default en_US) + LocaleChangeInterceptor (?lang= switches AND persists). Supported: en_US/ja_JP/zh_CN. CatalogController now reads LocaleContextHolder (resolved locale) instead of a per-request lang param, so the choice sticks across pages. Language switcher (EN/日本語/中文) added to the shared nav fragment.
- **Localized UI + catalog:** MessageSource bundles messages_en/ja/zh (+ base) for nav/main/cart/checkout labels, wired via Thymeleaf #{...}; catalog content localized from catalog-service per the active locale. (Currency left as $ for now — prices are the same numeric value across locales in the seed; per-locale currency formatting is a small follow-up.)
- **Verified live:** ?lang=ja_JP → Japanese UI (ホーム/カテゴリー) + catalog (魚/犬/鳥) + title; loading home with NO param stayed Japanese (cookie persisted); zh_CN → 首页/鱼/欢迎光临宠物商店; en_US → back to English. Monolith 15 tests green.
- Cleanup: removed a stale /admin/orders link from the nav (that route lives on admin-office-service, not the monolith).

## Legacy parity status (running)
Done toward "all functionality like legacy": catalog/cart/checkout/order-approval/inventory/fulfilment/auth/JMS-incl-InvoiceTopic/invoice-email/multi-locale. User chose to SKIP legacy SOAP web-services + XML/XSD doc exchange (interop artifacts, not user features; JSON events + REST cover the intent). Not yet built (candidates): order-approval + completed-order emails, customer account UI + order history, admin populate/reset tooling.

## order-processing-service extracted (the OPC) + storefront made publish-only + admin-office as thin console
Built the legacy Order Processing Center (opc.ear) as its own service, moved all order logic out of petstore-app-v1, and kept admin-office-service (legacy admin.ear) as a thin console that delegates to it.
- **Legacy fidelity (from source):** legacy OrderEJBAction only generated an id (persistent uidgen EJB) + built the PO + PUBLISHED to the queue — it did NOT persist. opc.ear's PurchaseOrderMDB persisted on consume, ran approval, InvoiceMDB completed, OPCAdminFacade served admin queries. admin.ear was a thin GUI calling OPCAdminFacade.
- **Decisions (user):** faithful OPC (absorb approval+invoice+admin), MATCH legacy persistence (storefront publishes only; OPC persists on consume), KEEP admin-office-service, and admin-office delegates to OPC via an HTTP client SDK.
- **order-processing-service :8088 (new, multi-module client+app):** owns the authoritative order store + workflow (moved from admin-office). Consumes PurchaseOrderQueue → persists + auto-approves (locale threshold) else PENDING; on approve publishes ApprovedOrderQueue; consumes InvoiceTopic → COMPLETED. Exposes the admin facade API (OPCAdminFacade): GET /api/orders?status, /api/orders/{id}, /{id}/status, POST /{id}/approve|deny — ADMIN-only, verify-only via auth-client. order-processing-client SDK single-sources the DTOs/paths.
- **petstore-app-v1 now publish-only (legacy model):** OrderService builds a PurchaseOrderEvent from the cart and PUBLISHES only — no persistence. Deleted the order JPA entities/repo/domain + purchase_order/line_item tables; DROPPED spring-boot-starter-data-jpa + H2 entirely (order was its only entity — storefront now has NO database). New OrderIdGenerator = snowflake-style (64-bit, time-ordered, in-process) — fixes the in-memory AtomicLong that reset to 1001 on restart. OrderService returns OrderPlaced(orderId,total). 16 monolith tests green (rewritten to verify publish+total+empty, not persistence).
- **admin-office-service now a thin console (legacy admin.ear):** owns NO order data. Its UI + /api/orders delegate to order-processing-service via OrderProcessingClient, forwarding the admin's JWT (cookie for UI, Bearer for API). Dropped JPA/H2/JMS/petstore-messaging deps + schema/data; kept login + orders UI + auth-client. Retained package com.petstore.warehouse (self-contained; rename is cosmetic).
- **Verified live (8 services + broker, via ./run-all.sh):** auto-approve small order → storefront publishes (snowflake id 3560871686…) → OPC persists+approves → inventory fulfils → invoice → OPC COMPLETED → customer emailed; admin-office /api/orders returns the SAME via OPC delegation. Manual path: large order (580.5) → PENDING → admin approves THROUGH admin-office console → OPC → fulfil → COMPLETED. Behaviour matches prior runs.
- **Topology now (8 services + broker):** petstore-app-v1 :8080 (storefront, publish-only, hosts broker, embeds cart-lib) · customer-service :8081 · admin-office-service :8082 (ADMIN console, delegates to OPC) · catalog-service :8083 · inventory-service :8085 · auth-service :8086 · notification-service :8087 · order-processing-service :8088 (OPC, owns orders) · Artemis :61616. run-all.sh/stop-all.sh updated for :8088.
