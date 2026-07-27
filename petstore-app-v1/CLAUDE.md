# petstore-app-v1 — Claude Code guide

The **:8080 storefront** of the migrated Java Pet Store. Serves the HTML shopping UI
(browse → cart → checkout, sign-on, registration, account self-service) and embeds the
in-process **cart-lib**. It is a plain **client** of the externalized ActiveMQ Artemis
broker (a standalone container — see the repo `docker-compose.yml`); it no longer hosts
the broker. Java package root: `com.petstore`. Spring Boot 3.3.5 / Java 21.

> Repo-wide conventions (build/run scripts, JMS contract, hexagonal rules, auth model,
> order workflow) live in the **`petstore-dev`** skill — read it first and don't duplicate
> it here. This file is only what's specific to petstore-app-v1.

See also: [`docs/LLD.md`](docs/LLD.md) (class + sequence diagrams), the repo root
[`DECISIONS.md`](../DECISIONS.md) (ADRs — check before "restoring" anything) and
[`docs/PARITY_AUDIT.md`](../docs/PARITY_AUDIT.md) (parity baseline; H7/H9/M4/L2 tracked there).

## Package layout (`src/com/petstore/`)

| Package | Holds |
|---------|-------|
| _(root)_ | `PetStoreApplication` — `@SpringBootApplication @EnableJms @ConfigurationPropertiesScan` |
| `catalog/web`, `catalog/domain`, `catalog/` | `CatalogController`; framework-free view models `Category`/`Product`/`Item`; `CatalogViewMapper` (SDK DTO → view model) |
| `inventory/web`, `inventory/client` | `StockController` (`GET /api/stock/{itemId}` — same-origin proxy for the after-load stepper cap); `InventoryClient` (thin RestClient over inventory-service's public availability read; no SDK jar exists) |
| `cart/web`, `cart/service`, `cart/config`, `cart/domain` | `CartController`, `CartIdFilter`; `CartService` (adapter over cart-lib); `CartConfig` (wires `CartStore`/`CartOperations`); `CartItem` |
| `order/web`, `order/service` | `CheckoutController` (JSON), `CheckoutForm`, `ContactInfoForm`, `MissingFormDataException`; `OrderService`, `OrderIdGenerator`, `EmptyCartException` |
| `customer/web` | `CustomerController` — account self-service (M4) |
| `security` | `SecurityConfig`, `CustomerServiceAuthProvider`, `LoginController`, `SignOnLocaleSuccessHandler` |
| `config` | `WebConfig` (i18n), `HttpClientConfig` (SDK beans), `ServiceEndpoints` (`@ConfigurationProperties`) |
| `web` | `StorefrontController` (HTML checkout + registration), `GlobalModelAdvice` (`cartCount`), `RestExceptionHandler` |

`resources/`: `application.yml`, `messages.properties` + `_en`/`_ja`/`_zh`, `templates/*.html`
(Thymeleaf, incl. `fragments/nav.html`, `fragments/stepper.html`). Non-standard layout — main
under `src/`, tests under `test/`, resources under `resources/` (declared in `pom.xml`).

## Build / run / test (THIS module)

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # Java 21 required
cd petstore-app-v1
mvn -q clean package          # build + tests (no `install` needed — nothing depends on this app)
mvn -q test                   # tests only
mvn spring-boot:run           # run on :8080 (also opens the shared broker on :61616)
java -jar target/petstore-app-v1-1.0.0.jar
```

Depends on the libs being in `~/.m2` first (`petstore-messaging`, `cart-lib`, and the
`*-client` SDKs). Use the repo `./build-all.sh` (starts with the libs) or `./run-all.sh`
(starts this broker-host **first**). Downstream services (catalog :8083, customer :8081,
auth :8086) should be up for the UI to fully work.

## Invariants (do NOT break)

1. **No order persistence here — hand the order to OPC and empty the cart.** `OrderService.checkout`
   builds a `CheckoutRequest` and calls `orderProcessingClient.checkout(request, jwt)` — a
   **synchronous** `POST /api/orders/intake` to order-processing-service — then empties the cart
   on success. (This replaced the old fire-and-forget publish of a `PurchaseOrderEvent` to
   `PurchaseOrderQueue`; see root `DECISIONS.md`.) No DB, no JPA, no order table — the OPC
   (order-processing-service) is the authoritative store. There is deliberately **no**
   `spring-boot-starter-data-jpa` in `pom.xml`. Do not add order status/lookup endpoints here
   (status is owned by OPC on :8088). The storefront **proxies the shopper's JWT**
   (`auth.getCredentials()`) to OPC so it can authorize the intake endpoint for the customer role;
   if OPC is unreachable, checkout throws `OrderIntakeUnavailableException` → clean 503 / retry
   notice and the cart is left intact (NOT emptied).
2. **This module is a broker CLIENT, not the broker host.** `application.yml` runs Artemis
   `mode: native` with `embedded.enabled: false` and connects to `broker-url`
   (`${BROKER_URL:tcp://localhost:61616}`) — the standalone container in the repo
   `docker-compose.yml`. Start the broker container first (`run-all.sh` does this). Tests
   override back to an in-VM embedded broker (`test/resources/application.yml`) so `mvn test`
   needs no running container. Do NOT re-add an embedded broker server or a TCP acceptor to
   production config — the fleet connects to the container, not to this JVM.
3. **Cart is session-local via a cart-id cookie, not a `@SessionScope` bean.** `CartIdFilter`
   mints/reads a `cartId` cookie (HttpOnly, 128-bit SecureRandom) and stashes it on the request;
   `CartService` reads it from `RequestContextHolder` and delegates to the in-process cart-lib
   (`CartOperations`). Never key carts on username (logged-out shoppers can have carts) and never
   generate the id client-side.
4. **Checkout required-field set (H7).** Ship-to AND bill-to each require: family name, given
   name, street 1, city, state, zip, telephone (7 each → 14 total). `streetName2`, `country`,
   `email` are optional; blank optionals normalise to `null`. Validation is `ContactInfoForm.requireValid`
   → `MissingFormDataException`. Keep this set exactly (pinned by `CheckoutAddressTest`).
5. **Sign-on locale precedence:** explicit `?lang=` on the login request **wins** over the
   customer's stored `preferredLanguage`. `SignOnLocaleSuccessHandler` returns early if
   `request.getParameter("lang") != null`, otherwise applies the stored preference (H9).
6. **i18n:** three locales only — `en_US`, `ja_JP`, `zh_CN` (`WebConfig.SUPPORTED`). Cookie-backed
   `CookieLocaleResolver` (cookie `lang`) + `LocaleChangeInterceptor` (`?lang=`). UI text lives in
   `messages_*.properties` (basename `classpath:messages`, `fallbackToSystemLocale=false`); add a
   key to **all** bundles. Catalog text is localised by catalog-service (locale-split tables),
   not by these bundles.

## JMS events

- **Produces:** nothing. Checkout intake moved from JMS to a **synchronous REST call** to OPC's
  `POST /api/orders/intake` (see `DECISIONS.md`); the storefront no longer publishes a
  `PurchaseOrderEvent` to `PurchaseOrderQueue`. `MessagePublisher` / `Destinations.PURCHASE_ORDER`
  are no longer used here. (The `petstore-messaging` dependency + Artemis client remain on the
  classpath — the storefront still runs as a broker client for the rest of the fleet's plumbing —
  but no `publish(...)` call is made on the checkout path.)
- **Consumes:** nothing. This module has no `@JmsListener`.

## External dependencies (client SDKs it calls)

| SDK / lib | Used by | For |
|-----------|---------|-----|
| `auth-client` (`AuthClient`) | `CustomerServiceAuthProvider` | Form-login → auth-service (:8086); returns JWT + userId + roles |
| `customer-service-client` (`CustomerServiceClient`) | `StorefrontController`, `CustomerController`, `SignOnLocaleSuccessHandler` | Register; read/update account/profile/card (:8081), Bearer = session JWT |
| `catalog-service-client` (`CatalogServiceClient`) | `CatalogController`, cart-lib | Browse/search + item price resolution (:8083) |
| `cart-lib` (`CartOperations`/`CartStore`) | `CartService` | In-process cart + 15-min sliding TTL |
| `order-processing-client` (`OrderProcessingClient`) | `OrderService` | Synchronous checkout intake — `POST /api/orders/intake` (:8088), Bearer = shopper JWT |
| `petstore-messaging` (`Events`, `Correlation`) | `CorrelationIdFilter` | Correlation-id bridge; no longer used to publish on checkout |

Auth is fully **delegated**: this module holds no credentials and no `UserDetailsService`.
The JWT lives as the `Authentication` credential and is forwarded as a Bearer token; the stable
`userId` (customer-service key) is on `Authentication.getDetails()`, distinct from the username.

## Gotchas

- **Two checkout endpoints.** `POST /checkout` (HTML, `StorefrontController`, identity from
  `Authentication`) and `POST /api/checkout` (JSON, `CheckoutController`, takes `userId`/`email`
  as params). Both are in the `authenticated()` matcher and both are CSRF-exempt.
- CSRF is **disabled** for `/checkout`, `/api/checkout`, `/cart/**`, `/admin/**` (form/AJAX posts).
- `GlobalModelAdvice` adds `cartCount` to every `@Controller` view (not `@RestController`).
- **Live stock is a display/UX enhancement beyond legacy — never an oversell guard.** Legacy
  never showed stock to shoppers; the authoritative all-or-nothing oversell check lives at
  fulfilment (inventory-service, pessimistic row lock). Two independent, both-degradable
  mechanisms surface stock: (1) the **item-page badge** — composed server-side in
  `CatalogController.resolveStock` (coarse: In stock / Only N left / Out of stock), hidden on any
  failure; (2) the **cart-stepper cap** — the `fragments/stepper.html` JS fetches
  `GET /api/stock/{itemId}` *after* page load (off the render path, so no browse-time fan-out and
  no added latency) and disables `+` at the on-hand ceiling on product/item/search pages. Both are
  **client/UX only** — a raw `POST /cart/set?qty=N` still bypasses the cap. `StockController` is a
  same-origin proxy (browser is on :8080, inventory-service on :8085 → direct fetch is cross-origin)
  that forwards to `InventoryClient` and returns `204` when stock is unavailable (stepper stays
  uncapped). `/api/stock/**` is public (browse data); it exposes exact counts, unlike the coarse badge.
  Both paths share one `InventoryClient` bean, which **caches each per-item reading in-process**
  (`SingleFlightStockCache`, TTL `inventory.stock-cache.ttl`, default 1h): a fresh entry is served
  without a remote call, and on expiry exactly one thread refreshes an item while concurrent readers
  wait for its result (single-flight — no cache stampede). Safe precisely because the value is
  display-only and never an oversell guard, so up-to-a-TTL staleness is acceptable; transport
  failures/empties are **not** cached, so the badge recovers as soon as inventory-service is healthy.
- `OrderService` hardcodes `locale = Locale.US` (and `currency = USD`) on the intake request
  (legacy quirk) — the UI locale does not flow into the order.
- **Checkout intake is synchronous** — a slow/down OPC blocks the checkout thread until the SDK's
  bounded timeout / circuit breaker trips, then `OrderIntakeUnavailableException` → 503. It no
  longer returns instantly the way the old fire-and-forget publish did.
- `CartService.cartId()` throws `IllegalStateException` if `CartIdFilter` didn't run — in tests,
  bind a `MockHttpServletRequest` with `CartIdFilter.REQUEST_ATTR` set (see the existing tests).
- Registration returns the user to the originating screen (L2): `?returnUrl=` wins over `Referer`,
  and only same-app (`/…`, not `//`) redirects are honoured (open-redirect guard).

## Tests (`test/com/petstore/`)

- `order/OrderCharacterizationTest` — checkout POSTs a `CheckoutRequest` to OPC (JWT forwarded) +
  computes total + empties cart on success; OPC-down → `OrderIntakeUnavailableException`, cart kept.
- `order/CheckoutAddressTest` — H7 required/optional field set + contacts on the intake request.
- `cart/CartServiceAdapterTest` — CartService delegates to cart-lib with the resolved cart id.
- `security/SecurityTest` — `@SpringBootTest` slice; login delegated to a mocked `AuthClient`;
  public pages open, `/checkout` redirects when anonymous, logout ends session.
