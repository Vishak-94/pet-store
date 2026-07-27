# petstore-app-v1 — :8080 shopper-facing storefront (browse → cart → checkout)

> Part of the Java Pet Store → Spring Boot 3.3.5 / Java 21 migration. See the [repo README](../README.md).

**Port:** `8080` · **Package:** `com.petstore` · **Legacy origin:** petstore.ear

## What it does

The HTML storefront of the migrated Pet Store. It serves the shopping UI — catalog
browse (categories → products → items) and search, the shopping cart, sign-on,
registration, account self-service, and checkout. It embeds the in-process **cart-lib**
and is a plain **client** of every other concern:

- It persists **nothing** — no database, no JPA, no order table. Checkout hands the
  order to order-processing-service (OPC), the authoritative order store.
- It is a broker **client**, not the broker host. The ActiveMQ Artemis broker now runs
  as a **standalone container** (repo `docker-compose.yml`); this app connects to it
  (`mode: native`, `embedded.enabled=false`) but no longer publishes on the checkout path.
- Order status, admin approval, catalog, customer, auth, inventory, and notification each
  live in their own service — there are intentionally **no** order-status or `/admin/**`
  capabilities here.

## Layout

Non-standard Maven layout (declared in `pom.xml`): main under `src/`, tests under `test/`,
resources under `resources/`. Package-per-context under `src/com/petstore/`:

| Package | Holds |
|---------|-------|
| _(root)_ | `PetStoreApplication` (`@SpringBootApplication @EnableJms @ConfigurationPropertiesScan`) |
| `catalog/` | `CatalogController`; framework-free view models `Category`/`Product`/`Item`; `CatalogViewMapper` (SDK DTO → view model) |
| `cart/` | `CartController`, `CartIdFilter`; `CartService` (adapter over cart-lib); `CartConfig`; `CartItem` |
| `order/` | `CheckoutController` (JSON) + `PreCheckoutController`; `StorefrontController`'s HTML checkout; `OrderService`, `OrderIdGenerator`, `IdempotencyKeyStore`, `OrderKeyCipher`; `CheckoutForm`/`ContactInfoForm`; `EmptyCartException`, `MissingFormDataException`, `OrderIntakeUnavailableException` |
| `inventory/web` | `StockController` — same-origin proxy `GET /api/stock/{itemId}` for the after-load stepper cap |
| `customer/web` | `CustomerController` — account self-service |
| `security` | `SecurityConfig`, `CustomerServiceAuthProvider`, `LoginController`, `SignOnLocaleSuccessHandler`, `AuthenticatedUser` |
| `config` | `WebConfig` (i18n), `HttpClientConfig` + `ResilientRestClient` (SDK beans), `ServiceEndpoints` (`@ConfigurationProperties`) |
| `web` | `StorefrontController`, `RegistrationController`, `GlobalModelAdvice` (`cartCount`), `CorrelationIdFilter`, `RestExceptionHandler`, `HtmlExceptionHandler` |

`resources/`: `application.yml`, `messages.properties` + `_en`/`_ja`/`_zh`, Thymeleaf
`templates/*.html` (incl. `fragments/nav.html`, `fragments/stepper.html`). Three locales
only: `en_US`, `ja_JP`, `zh_CN`.

## Build & run

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # Java 21 required
cd petstore-app-v1
mvn -q clean package          # compile + run this module's tests
mvn -q test                   # tests only
mvn spring-boot:run           # run on :8080
java -jar target/petstore-app-v1-1.0.0.jar
```

The shared libs (`petstore-messaging`, `cart-lib`, and the `*-client` SDKs) must be in
`~/.m2` first — use the repo `../build-all.sh`. At runtime the broker container must be up
(start it first; `../run-all.sh` orders the fleet correctly), and for the UI to fully work
the downstream services should be running: catalog (:8083), customer (:8081), auth (:8086),
inventory (:8085), and order-processing (:8088) for checkout. `mvn test` needs no running
broker — `test/resources/application.yml` overrides Artemis back to an in-VM embedded broker.

## Client SDKs imported

| SDK / lib | Used by | For |
|-----------|---------|-----|
| `auth-client` (`AuthClient`) | `CustomerServiceAuthProvider` | Form-login → auth-service (:8086); returns JWT + userId + roles |
| `customer-service-client` (`CustomerServiceClient`) | `StorefrontController`, `CustomerController`, `SignOnLocaleSuccessHandler` | Register; read/update account/profile/card (:8081), Bearer = session JWT |
| `catalog-service-client` (`CatalogServiceClient`) | `CatalogController`, cart-lib | Browse/search + item price resolution (:8083) |
| `inventory-service-client` (`InventoryClient`) | `StockController`, `CatalogController.resolveStock` | Public per-item availability (:8085) for the display-only stock badge / stepper cap; carries the in-process `SingleFlightStockCache` (TTL) |
| `order-processing-client` (`OrderProcessingClient`) | `OrderService` | Synchronous checkout intake — `POST /api/orders/intake` (:8088), Bearer = shopper JWT |
| `cart-lib` (`CartOperations`/`CartStore`) | `CartService` | **In-process** cart + 15-min sliding TTL |
| `petstore-messaging` (`Events`, `Correlation`) | `CorrelationIdFilter` | Correlation-id bridge (no longer used to publish on checkout) |

Auth is fully **delegated**: this module holds no local credentials and no
`UserDetailsService`. The RS256 JWT lives as the `Authentication` credential and is forwarded
as a Bearer token to downstream services; the stable customer `userId` sits on
`Authentication.getDetails()`, distinct from the username.

## Checkout flow

Checkout is a **synchronous REST intake to OPC**, not a fire-and-forget JMS publish.
`OrderService.checkout` builds a `CheckoutRequest` from the cart (server-minted order id,
computed total, ship-to/bill-to contacts), calls `orderProcessingClient.checkout(request, jwt)`
— a `POST /api/orders/intake` to order-processing-service — proxying the shopper's JWT so OPC
can authorize the intake for the customer role, then empties the cart **only on success**. If
OPC is unreachable the call throws `OrderIntakeUnavailableException` → clean 503 / retry notice
and the cart is left intact.

Duplicate submits are guarded by an **idempotency key**. The checkout page calls
`POST /pre-checkout`, which reserves a fresh server-minted order id per signed-in customer in
the in-memory `IdempotencyKeyStore` and returns it **encrypted** (AES-256/GCM via
`OrderKeyCipher`). The UI parks the ciphertext in the form's hidden `orderKey` field and echoes
it back on submit, where it is decrypted and consumed exactly once — a refresh / double-click
carries the same id, finds the reservation gone, and is rejected. OPC's `order_id` primary key
is the correctness backstop behind this.

Two checkout endpoints exist, both `authenticated()` and both CSRF-exempt: `POST /checkout`
(HTML, `StorefrontController`, identity from `Authentication`) and `POST /api/checkout`
(JSON, `CheckoutController`, takes `userId`/`email` params). `OrderService` hardcodes
`locale = en_US` / `currency = USD` on the intake request (a documented legacy quirk).

Live stock is a **display/UX enhancement only, never an oversell guard** (the authoritative
check is at fulfilment in inventory-service): a coarse item-page badge composed in
`CatalogController.resolveStock`, plus a cart-stepper cap that fetches `GET /api/stock/{itemId}`
after page load. Both degrade silently when inventory-service is unavailable.

## Auth / security

Form login is delegated to auth-service via `CustomerServiceAuthProvider`; the resulting
Spring session is tracked by the servlet **`JSESSIONID`** cookie (cleared on `/logout`), and
carries the JWT as the `Authentication` credential — `ProviderManager` credential-erasure is
disabled so the JWT survives to be forwarded downstream as a Bearer token. There is no separate
JWT cookie. The cart is session-local via a dedicated `cartId` cookie minted by `CartIdFilter`
(HttpOnly, 128-bit SecureRandom), independent of login so logged-out shoppers keep a cart.

Access rules (`SecurityConfig`): public browse/cart/register/login/`/api/stock/**`;
`/admin/**` needs ADMIN (kept for legacy parity); `/checkout`, `/api/checkout`, `/pre-checkout`,
`/customer` require authentication. **CSRF is disabled** for `/checkout`, `/api/checkout`,
`/pre-checkout`, `/cart/**`, `/admin/**` (form/AJAX posts). Sign-on locale precedence: an
explicit `?lang=` wins over the customer's stored `preferredLanguage` (`SignOnLocaleSuccessHandler`).

## See also

- [`CLAUDE.md`](CLAUDE.md) — developer/agent guide for this module (authoritative)
- [`docs/LLD.md`](docs/LLD.md) — class + sequence diagrams
- [`../README.md`](../README.md) — full system topology
- [`../DECISIONS.md`](../DECISIONS.md) — ADRs (e.g. broker externalization, JMS→REST intake)
- [`../docs/PARITY_AUDIT.md`](../docs/PARITY_AUDIT.md) — legacy-vs-migrated parity baseline

## Acknowledgements
Migrated from the Sun Java Pet Store 1.3.1_02 BluePrints sample (Apache 2.0).
