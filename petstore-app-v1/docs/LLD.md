# petstore-app-v1 — Low-Level Design

The **:8080 storefront**: HTML shopping UI (browse → cart → checkout), sign-on/registration,
account self-service, and the **host of the shared embedded Artemis broker** (`:61616`).
Package root `com.petstore`; Spring Boot 3.3.5 / Java 21. See the repo `petstore-dev` skill for
shared conventions and [`../CLAUDE.md`](../CLAUDE.md) for the invariant list.

## 1. Responsibilities

- Render the storefront (Thymeleaf): catalog browse/search, cart, checkout, login, registration,
  account edit — localised en/ja/zh.
- Own the shopping cart lifecycle in-process via **cart-lib**, keyed by an anonymous cart-id cookie.
- Delegate all domain data: catalog → catalog-service, customer → customer-service, auth → auth-service.
- **Publish-only** for orders: build a `PurchaseOrderEvent` from the cart and publish it to
  `PurchaseOrderQueue`; it persists **nothing** (the OPC on :8088 is the order store).
- Host the embedded ActiveMQ Artemis broker (TCP acceptor) all services share.

## 2. Class diagram

```mermaid
classDiagram
    direction LR

    class PetStoreApplication {
        +main(String[]) void
    }

    %% ---- web (HTML storefront) ----
    class StorefrontController {
        -CustomerServiceClient customerClient
        -CartService cart
        -OrderService orders
        +registerForm(String, String, Model) String
        +register(...) String
        +checkoutPage(Authentication, Model) String
        +placeOrder(Authentication, CheckoutForm, Model) String
    }
    class GlobalModelAdvice {
        -CartService cart
        +cartCount() int
    }
    class RestExceptionHandler {
        +handleEmptyCart(EmptyCartException) ResponseEntity
        +handleMissingFormData(MissingFormDataException) ResponseEntity
    }

    %% ---- catalog ----
    class CatalogController {
        -CatalogServiceClient catalog
        -CartService cart
        +main(Model) String
        +category(String, int, Model) String
        +product(String, int, Model) String
        +item(String, Model) String
        +search(String, Model) String
    }
    class CatalogViewMapper {
        +toCategory(CategoryDto) Category
        +toProduct(ProductDto) Product
        +toItem(ItemDto) Item
    }
    class Category {
        -String id
        -String name
        -String description
    }
    class Product {
        -String id
        -String name
        -String description
    }
    class Item {
        -String itemId
        -String productId
        -double listPrice
        +getListCost() double
        +getAttribute() String
    }

    %% ---- cart ----
    class CartController {
        -CartService cart
        +view(Model) String
        +setQuantity(String, int) Map
        +add(String) String
        +update(String, int) String
        +delete(String) String
    }
    class CartService {
        -CartOperations cart
        -cartId() String
        +addItem(String) void
        +addItem(String, int) void
        +updateItemQuantity(String, int) void
        +getItems() List~CartItem~
        +getSubTotal() double
        +getCount() int
        +quantityOf(String) int
        +empty() void
    }
    class CartIdFilter {
        +COOKIE String
        +REQUEST_ATTR String
        +doFilterInternal(req, res, chain) void
    }
    class CartConfig {
        +cartStore(long, long) CartStore
        +cartOperations(CartStore, CatalogServiceClient) CartOperations
    }
    class CartItem {
        -String itemId
        -int quantity
        -double unitCost
        +getTotalCost() double
    }

    %% ---- order ----
    class CheckoutController {
        -OrderService orderService
        +checkout(String, String, CheckoutForm) ResponseEntity
    }
    class OrderService {
        -CartService cart
        -MessagePublisher publisher
        -OrderIdGenerator ids
        +checkout(userId, email, ContactInfo, ContactInfo) OrderPlaced
        +checkout(userId, email) OrderPlaced
    }
    class OrderPlaced {
        +String orderId
        +double total
    }
    class OrderIdGenerator {
        +nextId() String
    }
    class CheckoutForm {
        -ContactInfoForm shipTo
        -ContactInfoForm billTo
    }
    class ContactInfoForm {
        -String familyName
        -String givenName
        -String streetName1
        -String city
        -String state
        -String zipCode
        -String telephone
        +missingRequiredFields(String) List~String~
        +requireValid(ContactInfoForm, ContactInfoForm)$ void
        +toContactInfo() ContactInfo
    }
    class MissingFormDataException {
        -List~String~ missingFields
    }
    class EmptyCartException
    class EmbeddedBrokerConfig {
        +customize(Configuration) void
    }

    %% ---- customer ----
    class CustomerController {
        -CustomerServiceClient customerClient
        +editForm(Authentication, Model) String
        +update(Authentication, ...) String
    }

    %% ---- security ----
    class SecurityConfig {
        +authenticationManager(provider) AuthenticationManager
        +filterChain(http, mgr, handler) SecurityFilterChain
    }
    class CustomerServiceAuthProvider {
        -AuthClient auth
        +authenticate(Authentication) Authentication
    }
    class SignOnLocaleSuccessHandler {
        -CustomerServiceClient customerClient
        -LocaleResolver localeResolver
        +onAuthenticationSuccess(req, res, auth) void
    }
    class LoginController {
        +login() String
    }

    %% ---- config ----
    class WebConfig {
        +SUPPORTED List~Locale~
        +localeResolver() LocaleResolver
        +localeChangeInterceptor() LocaleChangeInterceptor
        +messageSource() MessageSource
    }
    class HttpClientConfig {
        +customerServiceClient(ServiceEndpoints) CustomerServiceClient
        +catalogServiceClient(ServiceEndpoints) CatalogServiceClient
        +authClient(String) AuthClient
    }
    class ServiceEndpoints {
        -Service customer
        -Service catalog
    }

    %% ---- external (imported SDKs / libs) ----
    class CustomerServiceClient
    class CatalogServiceClient
    class AuthClient
    class CartOperations
    class MessagePublisher
    class PurchaseOrderEvent

    OrderPlaced --* OrderService
    CheckoutForm *-- ContactInfoForm
    ContactInfoForm ..> MissingFormDataException : throws
    ContactInfoForm ..> PurchaseOrderEvent : builds ContactInfo

    StorefrontController ..> CartService
    StorefrontController ..> OrderService
    StorefrontController ..> CustomerServiceClient
    StorefrontController ..> CheckoutForm
    GlobalModelAdvice ..> CartService
    CheckoutController ..> OrderService
    CheckoutController ..> CheckoutForm

    CatalogController ..> CatalogServiceClient
    CatalogController ..> CartService
    CatalogController ..> CatalogViewMapper
    CatalogViewMapper ..> Item
    CatalogViewMapper ..> Category
    CatalogViewMapper ..> Product

    CartController ..> CartService
    CartService ..> CartOperations
    CartService ..> CartItem
    CartService ..> CartIdFilter : reads REQUEST_ATTR
    CartConfig ..> CartOperations
    CartConfig ..> CatalogServiceClient

    OrderService ..> CartService
    OrderService ..> MessagePublisher
    OrderService ..> OrderIdGenerator
    OrderService ..> PurchaseOrderEvent

    CustomerController ..> CustomerServiceClient

    SecurityConfig ..> CustomerServiceAuthProvider
    SecurityConfig ..> SignOnLocaleSuccessHandler
    CustomerServiceAuthProvider ..> AuthClient
    SignOnLocaleSuccessHandler ..> CustomerServiceClient

    HttpClientConfig ..> ServiceEndpoints
    HttpClientConfig ..> CustomerServiceClient
    HttpClientConfig ..> CatalogServiceClient
    HttpClientConfig ..> AuthClient
```

## 3. Sequence diagrams

### 3.1 Browse → add to cart (in-page stepper)

```mermaid
sequenceDiagram
    autonumber
    actor Shopper
    participant Filter as CartIdFilter
    participant Cat as CatalogController
    participant CatSDK as CatalogServiceClient
    participant CartCtl as CartController
    participant Cart as CartService
    participant Ops as CartOperations (cart-lib)

    Shopper->>Filter: GET /product?id=FI-SW-01
    Filter->>Filter: read/mint cartId cookie, set request attr
    Filter->>Cat: forward
    Cat->>CatSDK: getProduct / getItems(locale)
    CatSDK-->>Cat: DTOs
    Cat-->>Shopper: product.html (items + cartQty)

    Shopper->>Filter: POST /cart/set?itemId=EST-1&qty=2
    Filter->>CartCtl: forward (cartId on request)
    CartCtl->>Cart: updateItemQuantity("EST-1", 2)
    Cart->>Cart: cartId() from RequestContextHolder
    Cart->>Ops: setQuantity(cartId, "EST-1", 2)
    CartCtl->>Cart: quantityOf / getCount
    Cart->>Ops: view(cartId)
    Ops-->>Cart: CartView
    CartCtl-->>Shopper: JSON {itemId, qty, count}
```

### 3.2 Checkout → publish PurchaseOrderEvent (with address validation)

```mermaid
sequenceDiagram
    autonumber
    actor Shopper
    participant SC as StorefrontController
    participant CustSDK as CustomerServiceClient
    participant Form as ContactInfoForm
    participant OS as OrderService
    participant Cart as CartService
    participant Pub as MessagePublisher
    participant Q as PurchaseOrderQueue (Artemis)

    Shopper->>SC: POST /checkout (shipTo.*, billTo.*) [authenticated]
    SC->>CustSDK: getCustomer(userId, JWT)
    CustSDK-->>SC: CustomerView (email)
    SC->>Form: requireValid(shipTo, billTo)
    alt any required field blank
        Form-->>SC: throw MissingFormDataException(missing)
        SC-->>Shopper: re-render checkout.html with error
    else all required present
        Form-->>SC: ok (blank optionals -> null)
        SC->>OS: checkout(userId, email, shipTo.toContactInfo(), billTo.toContactInfo())
        OS->>Cart: getItems()
        alt cart empty
            Cart-->>OS: []
            OS-->>SC: throw EmptyCartException
            SC-->>Shopper: checkout.html "cart is empty"
        else has items
            OS->>OS: OrderIdGenerator.nextId(); build lines + total; locale=US
            OS->>Pub: publish(PURCHASE_ORDER, PurchaseOrderEvent)
            Pub->>Q: convertAndSend (_type=PurchaseOrder)
            OS->>Cart: empty()
            OS-->>SC: OrderPlaced(orderId, total)
            SC-->>Shopper: order_complete.html (status=SUBMITTED)
        end
    end
```

### 3.3 Sign-on → apply locale

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant SF as Spring Security filter chain
    participant AP as CustomerServiceAuthProvider
    participant Auth as AuthClient (auth-service)
    participant H as SignOnLocaleSuccessHandler
    participant CustSDK as CustomerServiceClient
    participant LR as LocaleResolver

    User->>SF: POST /login (username, password [, ?lang=])
    SF->>AP: authenticate(token)
    AP->>Auth: login(username, password)
    Auth-->>AP: LoginResult(jwt, userId, roles)
    AP-->>SF: Authentication (credentials=jwt, details=userId, ROLE_*)
    SF->>H: onAuthenticationSuccess(req, res, auth)
    alt request has ?lang=
        H->>H: return early (explicit override wins)
    else no ?lang=
        H->>CustSDK: getCustomer(userId, jwt)
        CustSDK-->>H: CustomerView (profile.preferredLanguage)
        H->>H: parse "en_US" -> Locale
        H->>LR: setLocale(req, res, locale)
    end
    H-->>User: redirect "/" (alwaysUseDefaultTargetUrl)
```

## 4. Request / route table

| Method | Path | Handler | Auth | Notes |
|--------|------|---------|------|-------|
| GET | `/` | `CatalogController.main` | public | category list (locale-aware) |
| GET | `/category` | `CatalogController.category` | public | `?id=`, `?start=` |
| GET | `/product` | `CatalogController.product` | public | `?id=`, seeds cart qty |
| GET | `/item` | `CatalogController.item` | public | `?id=` |
| GET | `/search` | `CatalogController.search` | public | `?keyword=` |
| GET | `/cart` | `CartController.view` | public | full cart page |
| POST | `/cart/set` | `CartController.setQuantity` | public | JSON; qty≤0 removes |
| POST | `/cart/add` | `CartController.add` | public | redirect to `/cart` |
| POST | `/cart/update` | `CartController.update` | public | redirect to `/cart` |
| POST | `/cart/delete` | `CartController.delete` | public | redirect to `/cart` |
| GET | `/login` | `LoginController.login` | public | renders form; POST `/login` handled by Security |
| POST | `/login` | (Spring Security) | public | success → `SignOnLocaleSuccessHandler` |
| POST | `/logout` | (Spring Security) | any | invalidates session, clears `JSESSIONID` |
| GET | `/register-form` | `StorefrontController.registerForm` | public | captures returnUrl/Referer (L2) |
| POST | `/register-form` | `StorefrontController.register` | public | → customer-service; return to origin |
| GET | `/checkout` | `StorefrontController.checkoutPage` | **authenticated** | summary + saved address |
| POST | `/checkout` | `StorefrontController.placeOrder` | **authenticated** | validate + publish PO |
| POST | `/api/checkout` | `CheckoutController.checkout` | permitAll* | JSON alt; `userId`/`email` params |
| GET | `/customer` | `CustomerController.editForm` | **authenticated** | account edit form (M4) |
| POST | `/customer` | `CustomerController.update` | **authenticated** | update account/profile/card |

\* `/api/checkout` is not matched by the `authenticated()` rule (only exact `/checkout` is), so it
falls through to `anyRequest().permitAll()`. CSRF is disabled for `/checkout`, `/cart/**`, `/admin/**`.

## 5. Key design decisions & invariants

- **Publish-only orders (legacy-faithful).** `OrderService` publishes `PurchaseOrderEvent` and
  never persists. No JPA in `pom.xml`; order status/lookup is owned by the OPC. Do not add
  persistence or status endpoints here. (See `DECISIONS.md`.)
- **Broker host.** Artemis is embedded (`mode: embedded`, non-persistent) and `EmbeddedBrokerConfig`
  opens `tcp://0.0.0.0:61616` so the fleet shares one broker. Start this app first.
- **Cart identity = cookie, not HTTP session.** `CartIdFilter` mints an HttpOnly 128-bit SecureRandom
  `cartId` cookie; `CartService` resolves it per request and delegates to in-process cart-lib. Works
  for logged-out shoppers and survives login. Subtotal uses list price (`CartItem.unitCost` = list cost).
- **H7 address validation.** Ship-to + bill-to each require family/given name, street1, city, state,
  zip, telephone (14 total); street2/country/email optional, blanks → null. Missing →
  `MissingFormDataException` (HTML re-render, or 400 JSON via `RestExceptionHandler`).
- **H9 sign-on locale, `?lang=` wins.** `SignOnLocaleSuccessHandler` applies stored
  `preferredLanguage` only when no `?lang=` param is present.
- **Delegated auth.** No local credentials/`UserDetailsService`; JWT is the `Authentication`
  credential (forwarded as Bearer), stable `userId` on `getDetails()`.
- **i18n.** Locales `en_US`/`ja_JP`/`zh_CN` only; cookie `lang` + `?lang=` interceptor; UI text in
  `messages_*.properties`; catalog text localised by catalog-service. Add new keys to all bundles.
