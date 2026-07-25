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

1. **Publish-only order model — NEVER add order persistence here.** `OrderService.checkout`
   builds a `PurchaseOrderEvent` and `publisher.publish(Destinations.PURCHASE_ORDER, event)`,
   then empties the cart. No DB, no JPA, no order table — the OPC (order-processing-service)
   is the authoritative store. There is deliberately **no** `spring-boot-starter-data-jpa` in
   `pom.xml`. Do not add order status/lookup endpoints here (status is owned by OPC on :8088).
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

- **Produces:** `PurchaseOrderEvent` → `PurchaseOrderQueue` (queue) on checkout, via
  `MessagePublisher` from petstore-messaging. Nullable `shipTo`/`billTo` `ContactInfo` are populated.
- **Consumes:** nothing. This module has no `@JmsListener`; it is a pure producer + broker host.

## External dependencies (client SDKs it calls)

| SDK / lib | Used by | For |
|-----------|---------|-----|
| `auth-client` (`AuthClient`) | `CustomerServiceAuthProvider` | Form-login → auth-service (:8086); returns JWT + userId + roles |
| `customer-service-client` (`CustomerServiceClient`) | `StorefrontController`, `CustomerController`, `SignOnLocaleSuccessHandler` | Register; read/update account/profile/card (:8081), Bearer = session JWT |
| `catalog-service-client` (`CatalogServiceClient`) | `CatalogController`, cart-lib | Browse/search + item price resolution (:8083) |
| `cart-lib` (`CartOperations`/`CartStore`) | `CartService` | In-process cart + 15-min sliding TTL |
| `petstore-messaging` (`MessagePublisher`, `Destinations`, `Events`) | `OrderService` | Publish PO to the queue |

Auth is fully **delegated**: this module holds no credentials and no `UserDetailsService`.
The JWT lives as the `Authentication` credential and is forwarded as a Bearer token; the stable
`userId` (customer-service key) is on `Authentication.getDetails()`, distinct from the username.

## Gotchas

- **Two checkout endpoints.** `POST /checkout` (HTML, `StorefrontController`, identity from
  `Authentication`) and `POST /api/checkout` (JSON, `CheckoutController`, takes `userId`/`email`
  as params). Both are in the `authenticated()` matcher and both are CSRF-exempt.
- CSRF is **disabled** for `/checkout`, `/api/checkout`, `/cart/**`, `/admin/**` (form/AJAX posts).
- `GlobalModelAdvice` adds `cartCount` to every `@Controller` view (not `@RestController`).
- `OrderService` hardcodes `locale = Locale.US` on the published PO (legacy quirk) — the UI
  locale does not flow into the PO.
- `CartService.cartId()` throws `IllegalStateException` if `CartIdFilter` didn't run — in tests,
  bind a `MockHttpServletRequest` with `CartIdFilter.REQUEST_ATTR` set (see the existing tests).
- Registration returns the user to the originating screen (L2): `?returnUrl=` wins over `Referer`,
  and only same-app (`/…`, not `//`) redirects are honoured (open-redirect guard).

## Tests (`test/com/petstore/`)

- `order/OrderCharacterizationTest` — checkout publishes PO + computes total + empties cart; no persistence.
- `order/CheckoutAddressTest` — H7 required/optional field set + contacts on the published event.
- `cart/CartServiceAdapterTest` — CartService delegates to cart-lib with the resolved cart id.
- `security/SecurityTest` — `@SpringBootTest` slice; login delegated to a mocked `AuthClient`;
  public pages open, `/checkout` redirects when anonymous, logout ends session.
