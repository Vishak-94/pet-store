---
name: petstore-app-v1
description: How to work in the petstore-app-v1 storefront module (:8080) of the migrated Java Pet Store. Use when changing the storefront UI, catalog browse/search pages, the shopping cart, checkout and its ship-to/bill-to address validation, sign-on / registration / account self-service, i18n (en/ja/zh) message bundles or Thymeleaf templates, the embedded Artemis broker host, or the checkout→PurchaseOrderQueue publish. Trigger terms include storefront, checkout, cart, add-to-cart, sign-on/login, register, preferredLanguage/locale, PurchaseOrderEvent, and com.petstore controllers/services/templates.
---

# petstore-app-v1 — storefront skill

The :8080 HTML storefront and shared-broker host. Package root `com.petstore`, Spring Boot
3.3.5 / Java 21, non-standard layout (`src/`, `test/`, `resources/`). For shared conventions
(build/run scripts, JMS contract, hexagonal rules, auth, order workflow) use the repo
**`petstore-dev`** skill — don't repeat it here.

Module docs: [`../../../CLAUDE.md`](../../../CLAUDE.md) (invariants + build/run/test) and
[`../../../docs/LLD.md`](../../../docs/LLD.md) (class + sequence diagrams).
Parity baseline: repo `docs/PARITY_AUDIT.md`; ADRs: repo `DECISIONS.md`.

## Where things live (`src/com/petstore/`)

- **Controllers** (thin, delegate only): `catalog/web/CatalogController`, `cart/web/CartController`,
  `web/StorefrontController` (HTML checkout + registration), `order/web/CheckoutController` (JSON),
  `customer/web/CustomerController`, `security/LoginController`.
- **Services / logic:** `cart/service/CartService` (adapter over cart-lib), `order/service/OrderService`
  (builds + publishes the PO), `order/service/OrderIdGenerator`.
- **Config:** `config/WebConfig` (i18n), `config/HttpClientConfig` (SDK beans), `config/ServiceEndpoints`,
  `cart/config/CartConfig`, `order/messaging/EmbeddedBrokerConfig`, `security/SecurityConfig`.
- **Templates:** `resources/templates/*.html` (Thymeleaf) + `fragments/nav.html`, `fragments/stepper.html`.
- **i18n bundles:** `resources/messages.properties` + `_en` / `_ja` / `_zh`.

## Conventions / how-to

### Add a route
Add a `@GetMapping`/`@PostMapping` to the matching controller (keep it thin — call a service or SDK,
return a view name or JSON). If it needs auth, add the path to `SecurityConfig.filterChain`
(`authorizeHttpRequests`); otherwise it falls through to `anyRequest().permitAll()`. For form/AJAX
POSTs that should skip CSRF, add the path to the `csrf.ignoringRequestMatchers` list. HTML handlers
return a template name; JSON handlers use `@ResponseBody`/`@RestController`.

### Add a template / template field
Create/edit under `resources/templates/`. Reuse `fragments/nav.html` (`th:replace`). Read domain
objects via JavaBean getters (`${item.listCost}`) — the catalog view models are plain classes, not
records, precisely so SpringEL can read them. Every `@Controller` view already gets `cartCount`
(from `GlobalModelAdvice`). New user-facing text must be a message key added to **all three** bundles
(`messages_en/ja/zh.properties`), referenced with `#{key}`.

### Validation pattern (checkout)
Nested command object: `CheckoutForm { ContactInfoForm shipTo; ContactInfoForm billTo; }`, bound as
`shipTo.*` / `billTo.*`. Validation is a static `ContactInfoForm.requireValid(shipTo, billTo)` that
collects blanks via `missingRequiredFields(who)` and throws `MissingFormDataException`. Required set
(both sides): family name, given name, street1, city, state, zip, telephone. Optional (blank → null):
street2, country, email. `RestExceptionHandler` maps the exception to 400 for the JSON path; the HTML
path re-renders `checkout.html` with the error. Keep the required set exactly — `CheckoutAddressTest`
pins it (14 missing fields when both sides blank).

### Locale / i18n
Cookie-backed `CookieLocaleResolver` (cookie `lang`) + `LocaleChangeInterceptor` (`?lang=ja_JP`) in
`WebConfig`; supported locales are `en_US`, `ja_JP`, `zh_CN` only. Controllers read the active locale
via `LocaleContextHolder.getLocale()` and pass it to catalog-service (locale-split catalog content).
UI labels come from `messages_*.properties`. On sign-on, `SignOnLocaleSuccessHandler` applies the
customer's stored `preferredLanguage` — **but an explicit `?lang=` on the login request wins** (the
handler returns early when the param is present). Preserve that precedence.

### Checkout → JMS
`OrderService.checkout(...)` builds a `PurchaseOrderEvent` (via `Events.meta(PurchaseOrderEvent.TYPE)`,
`OrderIdGenerator.nextId()`, cart lines + total) and calls
`publisher.publish(Destinations.PURCHASE_ORDER, event)` from `petstore-messaging`, then empties the
cart. `shipTo`/`billTo` `ContactInfo` are populated on the event (nullable for the API path). Empty
cart → `EmptyCartException`. Never construct `JmsTemplate` or destination strings by hand — use
`MessagePublisher` + `Destinations`.

## Hard rules (don't violate)

- **Never persist orders here.** No JPA, no order table, no status/lookup endpoint. Build + publish
  only; order-processing-service (:8088) is the store. There is deliberately no JPA starter in `pom.xml`.
- **Keep the broker embedded + TCP-open.** `application.yml` (`artemis.mode: embedded`) +
  `EmbeddedBrokerConfig` (acceptor on `tcp://0.0.0.0:61616`); this app starts first in the fleet.
- **Cart is cookie-scoped via cart-lib, not `@SessionScope` and not a remote service.** Resolve the id
  through `CartIdFilter`/`RequestContextHolder`; never key on username, never mint the id client-side.
- **Auth is delegated** to auth-service; hold no credentials. Forward the JWT (the `Authentication`
  credential) as a Bearer token; the customer-service key is `Authentication.getDetails()` (userId),
  not the username.

## Build / test

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd petstore-app-v1 && mvn -q clean package     # or: mvn -q test
mvn spring-boot:run                            # :8080 + broker :61616
```

Tests in `test/com/petstore/`: `OrderCharacterizationTest`, `CheckoutAddressTest`,
`CartServiceAdapterTest`, `SecurityTest`. When testing cart/order services directly, bind a
`MockHttpServletRequest` with `CartIdFilter.REQUEST_ATTR` set (see those tests) or `CartService`
throws `IllegalStateException`.
