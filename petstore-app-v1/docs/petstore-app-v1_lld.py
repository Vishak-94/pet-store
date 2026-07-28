#!/usr/bin/env python3
"""
Low-Level-Design diagram generator for the petstore-app-v1 module (the :8080 Thymeleaf
STOREFRONT). Emits two diagrams into this directory using the shared house-style lib:

  petstore-app-v1_class.png/svg   — layered UML class diagram (web / rest / service /
                                     view-models / security / config / reused SDKs+libs),
                                     making the client-SDK + cart-lib REUSE story and the
                                     ResilientRestClient / @ConfigurationProperties
                                     EXTENSIBILITY seams visually obvious.
  petstore-app-v1_schema.png/svg  — the module has NO database. Instead this renders the
                                     in-memory / session data model (cart-id cookie,
                                     cart-lib CartEntry, idempotency Reservation, AES/GCM
                                     token) plus the DTO/wire contracts it composes from
                                     the imported client SDKs.

Every class, field, method, cookie and DTO below is taken verbatim from the real source
under petstore-app-v1/src (and the imported *-client SDK / cart-lib / petstore-messaging
jars it consumes) — nothing invented.
"""

import sys, os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND


# ───────────────────────────── CLASS DIAGRAM ──────────────────────────────
def build_class_diagram():
    g = new_graph("petstore-app-v1 — Storefront (:8080) · class & layering", rankdir="TB")

    # ---- Bootstrap ----
    def _boot(s):
        s.node("App", uml_class(
            "PetStoreApplication", "@SpringBootApplication",
            attrs=["@EnableJms", "@ConfigurationPropertiesScan"],
            methods=["main(String[])"], kind="config"))

    # ---- Web: HTML @Controller ----
    def _html(s):
        s.node("Storefront", uml_class(
            "StorefrontController", "@Controller",
            attrs=["customerClient", "cart", "orders", "keyStore", "orderKeyCipher"],
            methods=["checkoutPage(auth, model)", "placeOrder(auth, form, binding, model)"],
            kind="web"))
        s.node("Registration", uml_class(
            "RegistrationController", "@Controller",
            attrs=["customerClient"],
            methods=["registerForm(returnUrl, referer)", "register(userName, ...)"],
            kind="web"))
        s.node("CustomerCtl", uml_class(
            "CustomerController", "@Controller",
            attrs=["customerClient"],
            methods=["editForm(auth, model)", "update(auth, ...)"],
            kind="web"))
        s.node("Catalog", uml_class(
            "CatalogController", "@Controller",
            attrs=["catalog", "cart", "inventory"],
            methods=["main / category / product", "item / search", "resolveStock(itemId)"],
            kind="web"))
        s.node("CartCtl", uml_class(
            "CartController", "@Controller",
            attrs=["cart"],
            methods=["view", "setQuantity", "add/update/delete"], kind="web"))
        s.node("Advice", uml_class(
            "GlobalModelAdvice", "@ControllerAdvice",
            attrs=["cart"],
            methods=["cartCount()", "langSwitchBase()"], kind="web"))
        s.node("Login", uml_class("LoginController", "@Controller",
                                  methods=["login()"], kind="web"))

    # ---- Web: REST @RestController ----
    def _rest(s):
        s.node("CheckoutCtl", uml_class(
            "CheckoutController", "@RestController",
            attrs=["orderService", "keyStore", "orderKeyCipher"],
            methods=["checkout(auth, form) : POST /api/checkout"], kind="web"))
        s.node("PreCheckoutCtl", uml_class(
            "PreCheckoutController", "@RestController",
            attrs=["keyStore", "cipher"],
            methods=["reserve(auth) : POST /pre-checkout"], kind="web"))
        s.node("Stock", uml_class(
            "StockController", "@RestController",
            attrs=["inventory"],
            methods=["stock(itemId) : GET /api/stock/{id}"], kind="web"))

    # ---- Application service layer ----
    def _svc(s):
        s.node("OrderSvc", uml_class(
            "OrderService", "@Service",
            attrs=["cart", "orderProcessing", "ids", "LOCALE=en_US", "CURRENCY=USD"],
            methods=["checkout(bearer, orderId, userId,", "  email, shipTo, billTo) : OrderPlaced",
                     "record OrderPlaced(orderId, total)"], kind="service"))
        s.node("CartSvc", uml_class(
            "CartService", "@Service",
            attrs=["cart : CartOperations", "cartId() from RequestContextHolder"],
            methods=["addItem / updateItemQuantity", "getItems / getSubTotal / getCount",
                     "quantityOf / empty"], kind="service"))
        s.node("IdGen", uml_class(
            "OrderIdGenerator", "@Component",
            methods=["nextId() : String  (snowflake)"], kind="service"))
        s.node("KeyStore", uml_class(
            "IdempotencyKeyStore", "@Component · AutoCloseable",
            attrs=["byCustomer : Map<id,Reservation>", "ttl · daemon sweeper"],
            methods=["reserve(customerId)", "consumeIfMatches(customerId, orderId)"],
            kind="service"))
        s.node("Cipher", uml_class(
            "OrderKeyCipher", "@Component",
            attrs=["key : AES-256/GCM"],
            methods=["encrypt(id)", "decrypt(token) : Optional"], kind="service"))

    # ---- View models / forms (framework-free) ----
    def _dom(s):
        s.node("Item", uml_class(
            "Item", "value object",
            attrs=["itemId, productId, listPrice", "unitCost, attribute1..5"],
            methods=["getListCost()", "getAttribute()"], kind="domain"))
        s.node("Category", uml_class("Category", "value object",
                                     attrs=["id, name, description"], kind="domain"))
        s.node("Product", uml_class("Product", "value object",
                                    attrs=["id, name, description"], kind="domain"))
        s.node("CartItem", uml_class(
            "CartItem", "value object",
            attrs=["itemId, productId, category", "quantity, unitCost (=list price)"],
            methods=["getTotalCost()"], kind="domain"))
        s.node("Mapper", uml_class(
            "CatalogViewMapper", "mapper (static)",
            methods=["toCategory(dto)", "toProduct(dto)", "toItem(dto)"], kind="domain"))
        s.node("CheckoutForm", uml_class(
            "CheckoutForm", "command object",
            attrs=["orderKey", "@Valid shipTo", "@Valid billTo"], kind="domain"))
        s.node("ContactForm", uml_class(
            "ContactInfoForm", "command object",
            attrs=["familyName, givenName, street1..2", "city, state, zipCode, telephone, email"],
            methods=["missingRequiredFields(who)", "requireValid(shipTo, billTo)$",
                     "toContactInfo()"], kind="domain"))

    # ---- Security ----
    def _sec(s):
        s.node("SecConfig", uml_class(
            "SecurityConfig", "@Configuration",
            methods=["authenticationManager(provider)", "filterChain(http, mgr, handler)",
                     "signOnLocaleSuccessHandler(...)"], kind="config"))
        s.node("AuthProvider", uml_class(
            "CustomerServiceAuthProvider", "AuthenticationProvider",
            attrs=["auth : AuthClient"],
            methods=["authenticate(auth)", "supports(cls)"], kind="config"))
        s.node("LocaleHandler", uml_class(
            "SignOnLocaleSuccessHandler", "SimpleUrlAuthSuccessHandler",
            attrs=["customerClient", "localeResolver"],
            methods=["onAuthenticationSuccess(...)"], kind="config"))
        s.node("AuthUser", uml_class(
            "AuthenticatedUser", "util (DRY seam)",
            methods=["userId(auth) : String"], kind="config"))
        s.node("CartFilter", uml_class(
            "CartIdFilter", "@Component · OncePerRequestFilter",
            attrs=["COOKIE = cartId", "REQUEST_ATTR = cartId"],
            methods=["doFilterInternal(...)", "newCartId() : 128-bit SecureRandom"],
            kind="config"))

    # ---- Config / resilience ----
    def _cfg(s):
        s.node("WebConfig", uml_class(
            "WebConfig", "@Configuration · WebMvcConfigurer",
            attrs=["SUPPORTED = en_US/ja_JP/zh_CN", "LOCALE_PARAM = lang"],
            methods=["localeResolver()", "localeChangeInterceptor()", "messageSource()"],
            kind="config"))
        s.node("HttpCfg", uml_class(
            "HttpClientConfig", "@Configuration",
            methods=["customerServiceClient(ep)", "catalogServiceClient(ep)",
                     "inventoryClient(ep, ttl)", "orderProcessingClient(ep)", "authClient(url)"],
            kind="config"))
        s.node("Endpoints", uml_class(
            "ServiceEndpoints", "@ConfigurationProperties(\"services\")",
            attrs=["customer, catalog", "orderProcessing, inventory : Service"],
            methods=["Service.url(name)"], kind="config"))
        s.node("Resilient", uml_class(
            "ResilientRestClient", "factory (static)",
            attrs=["CircuitBreaker (all methods)", "Retry (GET/HEAD/OPTIONS only)"],
            methods=["forService(name, baseUrl) : RestClient"], kind="config"))
        s.node("CartCfg", uml_class(
            "CartConfig", "@Configuration",
            methods=["cartStore(ttl, sweep)", "cartOperations(store, catalog)"], kind="config"))

    # ---- Reused client SDKs (imported jars) ----
    def _sdk(s):
        s.node("CatalogSDK", uml_class(
            "CatalogServiceClient", "catalog-service-client",
            methods=["getCategories/getCategory", "getProducts/getItems", "getItem/searchItems"],
            kind="client", note="imported SDK jar"))
        s.node("CustomerSDK", uml_class(
            "CustomerServiceClient", "customer-service-client",
            methods=["register", "getCustomer(id, jwt)", "updateAccount/Profile/Card"],
            kind="client", note="imported SDK jar"))
        s.node("AuthSDK", uml_class(
            "AuthClient", "auth-client",
            methods=["login(user, pw) : LoginResult"], kind="client", note="imported SDK jar"))
        s.node("InvSDK", uml_class(
            "InventoryClient", "inventory-service-client",
            attrs=["SingleFlightStockCache (TTL)"],
            methods=["stockFor(itemId) : Optional<Integer>"], kind="client",
            note="imported SDK jar (+ in-proc cache)"))
        s.node("OpcSDK", uml_class(
            "OrderProcessingClient", "order-processing-client",
            methods=["checkout(request, bearer)", " → POST /api/orders/intake"], kind="client",
            note="imported SDK jar"))

    # ---- Reused libraries ----
    def _lib(s):
        s.node("CartOps", uml_class(
            "CartOperations", "cart-lib (in-process)",
            methods=["addItem/setQuantity/deleteItem", "empty/view(cartId) · count(cartId)"],
            kind="framework", note="embeddable lib jar"))
        s.node("CartStore", uml_class(
            "CartStore", "cart-lib · AutoCloseable",
            attrs=["cartId → CartEntry", "15-min sliding TTL sweeper"], kind="framework",
            note="embeddable lib jar"))
        s.node("POEvent", uml_class(
            "PurchaseOrderEvent.ContactInfo", "petstore-messaging",
            attrs=["familyName..email (10 fields)"], kind="messaging",
            note="shared contract type"))

    cluster(g, "boot", "Bootstrap", _boot, "#F2F2F2")
    cluster(g, "html", "Web · HTML (@Controller → Thymeleaf)", _html, "#EAF2FB")
    cluster(g, "rest", "Web · JSON (@RestController)", _rest, "#EAF2FB")
    cluster(g, "svc", "Application service", _svc, "#E7F4EF")
    cluster(g, "dom", "View models & forms (framework-free)", _dom, "#F0EAF9")
    cluster(g, "sec", "Security (delegated auth · cart-id)", _sec, "#EFEFEF")
    cluster(g, "cfg", "Config · i18n · resilience", _cfg, "#EFEFEF")
    cluster(g, "sdk", "Reused client SDKs (imported jars)", _sdk, "#E6F0FA")
    cluster(g, "lib", "Reused libraries (cart-lib · messaging)", _lib, "#EDF6EE")

    # ---- relationships ----
    # HTML controllers → services / SDKs
    edge(g, "Storefront", "OrderSvc", "depends")
    edge(g, "Storefront", "CartSvc", "depends")
    edge(g, "Storefront", "KeyStore", "depends")
    edge(g, "Storefront", "Cipher", "depends")
    edge(g, "Storefront", "CustomerSDK", "depends")
    edge(g, "Storefront", "CheckoutForm", "depends", "binds")
    edge(g, "Registration", "CustomerSDK", "depends")
    edge(g, "CustomerCtl", "CustomerSDK", "depends")
    edge(g, "Catalog", "CatalogSDK", "depends")
    edge(g, "Catalog", "CartSvc", "depends")
    edge(g, "Catalog", "Mapper", "depends")
    edge(g, "Catalog", "InvSDK", "depends", "resolveStock")
    edge(g, "CartCtl", "CartSvc", "depends")
    edge(g, "Advice", "CartSvc", "depends")

    # REST controllers
    edge(g, "CheckoutCtl", "OrderSvc", "depends")
    edge(g, "CheckoutCtl", "KeyStore", "depends")
    edge(g, "CheckoutCtl", "Cipher", "depends")
    edge(g, "CheckoutCtl", "CheckoutForm", "depends", "binds")
    edge(g, "PreCheckoutCtl", "KeyStore", "depends")
    edge(g, "PreCheckoutCtl", "Cipher", "depends")
    edge(g, "Stock", "InvSDK", "depends")

    # service internals + composition
    edge(g, "OrderSvc", "CartSvc", "depends")
    edge(g, "OrderSvc", "IdGen", "depends")
    edge(g, "OrderSvc", "OpcSDK", "depends", "POST intake")
    edge(g, "KeyStore", "IdGen", "depends")
    edge(g, "CartSvc", "CartOps", "depends")
    edge(g, "CartSvc", "CartItem", "compose")
    edge(g, "CartSvc", "CartFilter", "flow", "reads REQUEST_ATTR")

    # view models
    edge(g, "Mapper", "Item", "depends")
    edge(g, "Mapper", "Category", "depends")
    edge(g, "Mapper", "Product", "depends")
    edge(g, "CheckoutForm", "ContactForm", "compose")
    edge(g, "ContactForm", "POEvent", "depends", "toContactInfo")

    # security wiring
    edge(g, "SecConfig", "AuthProvider", "depends")
    edge(g, "SecConfig", "LocaleHandler", "depends")
    edge(g, "AuthProvider", "AuthSDK", "depends")
    edge(g, "LocaleHandler", "CustomerSDK", "depends")
    edge(g, "AuthProvider", "AuthUser", "flow", "sets userId")

    # config wiring — the reuse/extensibility seam
    edge(g, "HttpCfg", "Endpoints", "depends")
    edge(g, "HttpCfg", "Resilient", "depends")
    edge(g, "HttpCfg", "CustomerSDK", "compose")
    edge(g, "HttpCfg", "CatalogSDK", "compose")
    edge(g, "HttpCfg", "InvSDK", "compose")
    edge(g, "HttpCfg", "OpcSDK", "compose")
    edge(g, "HttpCfg", "AuthSDK", "compose")
    edge(g, "CartCfg", "CartOps", "compose")
    edge(g, "CartCfg", "CartStore", "compose")
    edge(g, "CartOps", "CartStore", "depends")
    edge(g, "CartCfg", "CatalogSDK", "depends")

    legend(g, [
        (PALETTE["web"][0], "Web (MVC controller / advice)"),
        (PALETTE["service"][0], "Application service"),
        (PALETTE["domain"][0], "View model / form (framework-free)"),
        (PALETTE["config"][0], "Config · security · resilience"),
        (PALETTE["client"][0], "Reused client SDK (imported jar)"),
        (PALETTE["framework"][0], "Reused library (cart-lib)"),
        (PALETTE["messaging"][0], "Shared messaging contract"),
        ("#FFFFFF", "→ depends · ◆ composes · ⇢ flow"),
    ])
    render(g, "petstore-app-v1_class")


# ───────────────────────────── SCHEMA / DATA-MODEL DIAGRAM ──────────────────────────────
def build_schema_diagram():
    g = new_graph("petstore-app-v1 — data model (no DB): session state + composed wire contracts",
                  rankdir="LR")

    # ---- In-memory / session-scoped state (this module owns) ----
    def _session(s):
        s.node("Cookies", table_node("Browser cookies (session)", [
            ("cartId", "HttpOnly · 128-bit SecureRandom hex", "pk"),
            ("JSESSIONID", "servlet session (auth)", ""),
            ("lang", "CookieLocaleResolver (en_US/ja_JP/zh_CN)", ""),
        ], kind="owned"))
        s.node("CartEntry", table_node("CartStore.CartEntry  (cart-lib, in-memory)", [
            ("cartId", "map key (from cartId cookie)", "pk"),
            ("items", "insertion-ordered itemId → qty", ""),
            ("lastAccess", "Instant · 15-min sliding TTL", ""),
        ], kind="owned"))
        s.node("Reservation", table_node("IdempotencyKeyStore.Reservation", [
            ("customerId", "map key (Authentication userId)", "pk"),
            ("orderId", "server-minted (OrderIdGenerator)", ""),
            ("issuedAt", "Instant · 30-min TTL evict", ""),
        ], kind="owned"))
        s.node("Token", table_node("orderKey token (OrderKeyCipher)", [
            ("iv", "12-byte GCM nonce", ""),
            ("ciphertext", "AES-256/GCM(orderId)", ""),
            ("tag", "128-bit auth tag (tamper-evident)", ""),
        ], kind="owned"))

    # ---- Composed OUTBOUND wire contract: storefront → OPC intake ----
    def _intake(s):
        s.node("CheckoutRequest", table_node("CheckoutRequest  → POST /api/orders/intake", [
            ("orderId", "@NotBlank (reserved token)", "pk"),
            ("userId", "@NotBlank", ""),
            ("emailId", "String", ""),
            ("locale", "en_US (hardcoded)", ""),
            ("currency", "USD (hardcoded)", ""),
            ("totalPrice", "double", ""),
            ("lines", "@NotEmpty List<LineDto>", "fk"),
            ("shipTo / billTo", "ContactInfoDto", "fk"),
        ], kind="external"))
        s.node("LineDto", table_node("LineDto", [
            ("itemId", "String", "pk"),
            ("productId / categoryId", "String", ""),
            ("quantity", "int", ""),
            ("unitPrice", "double (catalog list price)", ""),
        ], kind="external"))
        s.node("ContactInfoDto", table_node("ContactInfoDto", [
            ("familyName / givenName", "String", ""),
            ("streetName1 / streetName2", "String", ""),
            ("city / state / zipCode", "String", ""),
            ("country / telephone / email", "String", ""),
        ], kind="external"))
        s.node("CheckoutResponse", table_node("CheckoutResponse  (← OPC)", [
            ("orderId", "persisted id", "pk"),
            ("status", "PENDING | APPROVED", ""),
            ("totalPrice", "double", ""),
        ], kind="external"))

    # ---- Composed customer SDK contracts (register / account) ----
    def _customer(s):
        s.node("RegisterRequest", table_node("RegisterRequest  (customer SDK)", [
            ("userName", "@NotBlank ≤25", "pk"),
            ("password", "@NotBlank 4..25", ""),
            ("account", "AccountDto", "fk"),
            ("creditCard", "CardDto", "fk"),
        ], kind="external"))
        s.node("AccountDto", table_node("AccountDto", [
            ("givenName / familyName", "≤60", ""),
            ("email", "@Email ≤120", ""),
            ("streetName1..2 / city / state", "String", ""),
            ("zipCode / country / telephone", "String", ""),
        ], kind="external"))
        s.node("CardDto", table_node("CardDto", [
            ("cardNumber", "≤24", ""),
            ("cardType / expiryDate", "String", ""),
        ], kind="external"))
        s.node("CustomerView", table_node("CustomerView  (← read)", [
            ("userId", "stable key", "pk"),
            ("account", "Map (email, address)", ""),
            ("profile", "Map (preferredLanguage)", ""),
            ("cardMasked", "String", ""),
        ], kind="external"))

    # ---- Other SDK read shapes composed by the UI ----
    def _reads(s):
        s.node("LoginResult", table_node("AuthClient.LoginResult  (← login)", [
            ("token", "RS256 JWT (kept as credential)", "pk"),
            ("userId", "stable customer id → getDetails()", ""),
            ("roles", "List<String> → ROLE_*", ""),
        ], kind="external"))
        s.node("CartView", table_node("CartView  (cart-lib read model)", [
            ("items", "List<CartItemView>", "fk"),
            ("subTotal", "Σ(unitCost × qty)", ""),
            ("count", "distinct line items", ""),
        ], kind="external"))
        s.node("CartItemView", table_node("CartItemView", [
            ("itemId / productId / category", "String", "pk"),
            ("productName / attribute", "String", ""),
            ("quantity", "int", ""),
            ("unitCost", "catalog list price", ""),
        ], kind="external"))
        s.node("Stock", table_node("GET /api/stock/{itemId} + item badge", [
            ("itemId", "path var", "pk"),
            ("quantity", "Optional<Integer> (204 if none)", ""),
        ], kind="external"))

    cluster(g, "sess", "Session / in-memory state (owned by this module)", _session,
            "#E9F6EC", TABLE_KIND["owned"][1])
    cluster(g, "intake", "Outbound intake contract → order-processing-client", _intake, "#F3F3F3")
    cluster(g, "cust", "Customer SDK contracts (register / account)", _customer, "#F3F3F3")
    cluster(g, "reads", "Composed read shapes (auth · cart-lib · inventory)", _reads, "#F3F3F3")

    # composition (fk) edges
    edge(g, "CheckoutRequest", "LineDto", "fk", "lines[]")
    edge(g, "CheckoutRequest", "ContactInfoDto", "fk", "shipTo/billTo")
    edge(g, "RegisterRequest", "AccountDto", "fk", "account")
    edge(g, "RegisterRequest", "CardDto", "fk", "creditCard")
    edge(g, "CartView", "CartItemView", "fk", "items[]")

    # how session state flows into the composed contracts
    edge(g, "Reservation", "CheckoutRequest", "flow", "orderId")
    edge(g, "CartEntry", "CartView", "flow", "resolve+price")
    edge(g, "CartView", "CheckoutRequest", "flow", "lines+total")
    edge(g, "Token", "Reservation", "flow", "decrypt→consume")

    legend(g, [
        (TABLE_KIND["owned"][0], "Owned in-memory / session state"),
        (TABLE_KIND["external"][0], "Composed SDK / wire contract (no local DB)"),
        ("#FFFFFF", "⤙ composes (fk) · ⇢ data flow"),
    ])
    render(g, "petstore-app-v1_schema")


if __name__ == "__main__":
    build_class_diagram()
    build_schema_diagram()
    print("wrote petstore-app-v1_class.{png,svg} and petstore-app-v1_schema.{png,svg}")
