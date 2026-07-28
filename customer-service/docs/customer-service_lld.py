#!/usr/bin/env python3
"""LLD diagram generator for the customer-service module.

Renders two diagrams into this docs/ folder using the shared house-style lib:
  * customer-service_class.{png,svg} — UML class diagram (Web / Service / Domain /
    Ports & Adapters / Client SDK / Config-Security / shared auth-client), showing
    the hexagonal port/adapter seam, the single-sourced client SDK contract, and
    the reuse of the shared auth-client library.
  * customer-service_schema.{png,svg} — the persistence schema (single flattened
    `customer` table) alongside the wire/DTO contract exposed by the client SDK.

Everything below is extracted from the real source under
customer-service/{app,client}/ — no invented names.
"""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND


# ─────────────────────────────────────────────────────────────────────────────
# (a) CLASS DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_class_diagram():
    g = new_graph("customer-service — class diagram (hexagonal: web / service / domain / ports+adapters / client SDK)", rankdir="TB")

    # ---- Web (inbound adapter) ----
    def _web(s):
        s.node("Controller", uml_class(
            "CustomerController", "@RestController",
            methods=[
                "register(RegisterRequest) : 201 {userId,status}",
                "get(id, Authentication) : CustomerView",
                "updateAccount(id, AccountDto, auth) : CustomerView",
                "updateProfile(id, ProfileDto, auth) : CustomerView",
                "updateCard(id, CardDto, auth) : CustomerView",
                "- requireOwnerOrAdmin(id, auth)  [IDOR guard]",
                "- requireRegistrationFields(req)",
                "- toView / toAccount / toCard / mask",
            ], kind="web"))
        s.node("ExHandler", uml_class(
            "RestExceptionHandler", "@RestControllerAdvice",
            methods=[
                "handleValidation(...) : 400 validation_failed",
                "handleDuplicate(...) : 409 duplicate_account",
                "handleIllegalArg(...) : 404 not_found",
                "handleStatus(...) : status + correlationId",
            ], kind="web"))

    # ---- Service (application layer) ----
    def _svc(s):
        s.node("Service", uml_class(
            "CustomerService", "@Service",
            attrs=["auth : AuthClient", "customers : CustomerRepository",
                   "CUSTOMER_ROLE = \"USER\""],
            methods=[
                "register(userName, pwd, Account, CreditCard) : Customer",
                "register(userName, pwd, Account) : Customer",
                "findByUserId(userId) : Optional<Customer>",
                "updateAccount(userId, Account) : Customer",
                "updateProfile(userId, Profile) : Customer",
                "updateCreditCard(userId, CreditCard) : Customer",
                "- require(userId) : Customer",
            ], kind="service"))
        s.node("DupEx", uml_class(
            "DuplicateAccountException", "extends RuntimeException",
            kind="service", note="thrown on 409 from auth-service"))

    # ---- Domain (framework-free value objects) ----
    def _domain(s):
        s.node("Customer", uml_class(
            "Customer", "aggregate root (final)",
            attrs=["userId", "account", "profile", "creditCard"],
            methods=["getUserId / getAccount / getProfile / getCreditCard"],
            kind="domain"))
        s.node("Account", uml_class(
            "Account", "value object (final)",
            attrs=["ACTIVE=\"active\"  DISABLED=\"disabled\"",
                   "givenName, familyName, email, telephone",
                   "streetName1, streetName2, city, state",
                   "zipCode, country, status"],
            kind="domain"))
        s.node("Profile", uml_class(
            "Profile", "value object (final)",
            attrs=["preferredLanguage, favoriteCategory",
                   "myListPreference, bannerPreference"],
            methods=["defaults() : (en_US, null, true, true)"],
            kind="domain"))
        s.node("CreditCard", uml_class(
            "CreditCard", "value object (final)",
            attrs=["cardNumber, cardType, expiryDate"],
            kind="domain"))

    # ---- Ports & Adapters (outbound persistence) ----
    def _port(s):
        s.node("Repo", uml_class(
            "CustomerRepository", "interface (port)",
            methods=["findByUserId(userId) : Optional<Customer>",
                     "save(Customer) : Customer"], kind="port"))
        s.node("JpaAdapter", uml_class(
            "JpaCustomerRepository", "@Repository (adapter)",
            attrs=["jpa : CustomerJpaRepository"],
            methods=["findByUserId(userId)", "save(Customer)"], kind="adapter"))
        s.node("Entity", uml_class(
            "CustomerEntity", "@Entity @Table(customer)",
            attrs=["@Id user_id + 18 mapped columns"],
            methods=["fromDomain(Customer)$", "toDomain() : Customer"],
            kind="entity"))
        s.node("SpringData", uml_class(
            "CustomerJpaRepository", "Spring Data JpaRepository",
            note="extends JpaRepository<CustomerEntity,String>", kind="adapter"))

    # ---- Client SDK (separate customer-service-client module) ----
    def _client(s):
        s.node("SdkClient", uml_class(
            "CustomerServiceClient", "client SDK (RestClient)",
            attrs=["http : RestClient", "connect=2s / read=5s timeouts"],
            methods=["login(user, pwd) : Optional<AuthResult>",
                     "register(RegisterRequest) : boolean",
                     "getCustomer(id, bearer) : Optional<CustomerView>",
                     "updateAccount/Profile/Card(id, dto, bearer)"],
            kind="client"))
        s.node("Endpoints", uml_class(
            "CustomerServiceEndpoints", "contract constants",
            attrs=["DEFAULT_BASE_URL=:8081", "REGISTER /register",
                   "CUSTOMER /customer/{id}", "ACCOUNT|PROFILE|CARD suffixes",
                   "FIELD_USER_ID / FIELD_STATUS ..."], kind="client"))
        s.node("Dtos", uml_class(
            "CustomerDtos", "DTO records",
            attrs=["RegisterRequest, AccountDto, CardDto",
                   "ProfileDto, CustomerView, AuthResult",
                   "jakarta.validation: @NotBlank/@Size/@Email"], kind="client"))

    # ---- Config / Security ----
    def _cfg(s):
        s.node("Security", uml_class(
            "SecurityConfig", "@Configuration (verify-only)",
            methods=["jwtVerifier() : JwtVerifier",
                     "authClient(baseUrl) : AuthClient",
                     "filterChain(...) : SecurityFilterChain"],
            note="STATELESS; /register+/actuator public; h2-console @Profile(dev)",
            kind="config"))
        s.node("Cid", uml_class(
            "CorrelationIdFilter", "@Component OncePerRequestFilter",
            attrs=["HEADER=X-Correlation-Id", "MDC_KEY=correlationId"],
            note="HIGHEST_PRECEDENCE; sets/echoes cid into MDC", kind="config"))
        s.node("App", uml_class(
            "CustomerServiceApplication", "@SpringBootApplication",
            kind="config"))

    # ---- shared auth-client library (reused across services) ----
    def _auth(s):
        s.node("AuthClient", uml_class(
            "AuthClient", "auth-client lib (shared)",
            methods=["provision(userName, password, role) : userId"],
            note="credential store lives in auth-service :8086", kind="external"))
        s.node("AuthJwtFilter", uml_class(
            "AuthJwtFilter", "auth-client lib (shared)",
            methods=["verify RS256, set AuthClaims on Authentication"],
            kind="external"))
        s.node("JwtVerifier", uml_class(
            "JwtVerifier", "auth-client lib (shared)",
            attrs=["AuthPublicKey.bundled()"], kind="external"))
        s.node("AuthClaims", uml_class(
            "AuthClaims", "auth-client record (shared)",
            attrs=["userId, username, roles"],
            note="controller reads claims.userId() for owner check", kind="external"))

    cluster(g, "web", "Web  (inbound adapter)", _web, PALETTE["web"][0], PALETTE["web"][1])
    cluster(g, "svc", "Service  (application layer)", _svc, PALETTE["service"][0], PALETTE["service"][1])
    cluster(g, "dom", "Domain  (framework-free value objects)", _domain, PALETTE["domain"][0], PALETTE["domain"][1])
    cluster(g, "pa", "Ports & Adapters  (persistence — H2)", _port, PALETTE["port"][0], PALETTE["port"][1])
    cluster(g, "sdk", "Client SDK  (customer-service-client — reused by server + callers)", _client, PALETTE["client"][0], PALETTE["client"][1])
    cluster(g, "cfg", "Config / Security / Observability", _cfg, PALETTE["config"][0], PALETTE["config"][1])
    cluster(g, "auth", "auth-client  (shared library — reused across all services)", _auth, PALETTE["external"][0], PALETTE["external"][1])

    # relationships
    edge(g, "Controller", "Service", "depends")
    edge(g, "Controller", "Dtos", "depends", "reuses DTOs")
    edge(g, "Controller", "Endpoints", "depends", "reuses paths")
    edge(g, "Controller", "Customer", "flow", "maps DTO<->domain")
    edge(g, "Controller", "AuthClaims", "depends", "owner check")
    edge(g, "ExHandler", "DupEx", "depends")
    edge(g, "ExHandler", "Cid", "depends", "correlationId")

    edge(g, "Service", "Repo", "depends")
    edge(g, "Service", "AuthClient", "depends", "provision USER")
    edge(g, "Service", "DupEx", "flow")
    edge(g, "Service", "Customer", "depends")

    edge(g, "Customer", "Account", "compose")
    edge(g, "Customer", "Profile", "compose")
    edge(g, "Customer", "CreditCard", "compose")

    edge(g, "JpaAdapter", "Repo", "impl")
    edge(g, "JpaAdapter", "SpringData", "depends")
    edge(g, "JpaAdapter", "Entity", "flow", "from/toDomain")
    edge(g, "SpringData", "Entity", "depends")

    edge(g, "SdkClient", "Endpoints", "depends")
    edge(g, "SdkClient", "Dtos", "depends")

    edge(g, "Security", "JwtVerifier", "depends")
    edge(g, "Security", "AuthClient", "depends", "@Bean")
    edge(g, "Security", "AuthJwtFilter", "compose", "filter chain")
    edge(g, "AuthJwtFilter", "JwtVerifier", "depends")
    edge(g, "AuthJwtFilter", "AuthClaims", "flow")

    legend(g, [
        (PALETTE["web"][0], "Web / inbound adapter"),
        (PALETTE["service"][0], "Service / application"),
        (PALETTE["domain"][0], "Domain value object"),
        (PALETTE["port"][0], "Port (interface / SPI seam)"),
        (PALETTE["adapter"][0], "Adapter (persistence)"),
        (PALETTE["entity"][0], "JPA entity"),
        (PALETTE["client"][0], "Client SDK (reused contract)"),
        (PALETTE["config"][0], "Config / security"),
        (PALETTE["external"][0], "Shared auth-client library"),
        ("#FFFFFF", "--|>  impl   -->  depends   <>  compose   ..>  flow"),
    ])

    png, svg = render(g, "customer-service_class")
    print("class:", png, svg)


# ─────────────────────────────────────────────────────────────────────────────
# (b) SCHEMA + WIRE-CONTRACT DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_schema_diagram():
    g = new_graph("customer-service — persistence schema (customer table) + wire/DTO contract", rankdir="LR")

    # ---- owned DB table ----
    def _db(s):
        s.node("customer", table_node("customer  (owned, H2)", [
            ("user_id", "VARCHAR(40) NOT NULL", "pk"),
            ("given_name", "VARCHAR(80)", ""),
            ("family_name", "VARCHAR(80)", ""),
            ("email", "VARCHAR(120)", ""),
            ("telephone", "VARCHAR(40)", ""),
            ("street1", "VARCHAR(120)", ""),
            ("street2", "VARCHAR(120)", ""),
            ("city", "VARCHAR(80)", ""),
            ("state", "VARCHAR(80)", ""),
            ("zip_code", "VARCHAR(20)", ""),
            ("country", "VARCHAR(80)", ""),
            ("status", "VARCHAR(20) NOT NULL DEFAULT 'active'", ""),
            ("preferred_language", "VARCHAR(20)", ""),
            ("favorite_category", "VARCHAR(20)", ""),
            ("my_list_pref", "BOOLEAN NOT NULL DEFAULT FALSE", ""),
            ("banner_pref", "BOOLEAN NOT NULL DEFAULT FALSE", ""),
            ("card_number", "VARCHAR(30)", ""),
            ("card_type", "VARCHAR(30)", ""),
            ("card_expiry", "VARCHAR(10)", ""),
        ], kind="owned"))

    # ---- externally-owned credential store (referenced, not owned) ----
    def _ext(s):
        s.node("app_user", table_node("app_user  (owned by auth-service :8086)", [
            ("user_id", "UUID  (minted here, used as customer.user_id)", "pk"),
            ("user_name", "credential — NOT stored in customer-service", ""),
            ("password_hash", "credential — NOT stored in customer-service", ""),
            ("role", "USER / ADMIN", ""),
        ], kind="external"))

    # ---- request DTO contract (client SDK) ----
    def _req(s):
        s.node("RegisterRequest", table_node("RegisterRequest  (POST /register)", [
            ("userName", "@NotBlank @Size(max=25)", ""),
            ("password", "@NotBlank @Size(4..25)", ""),
            ("account", "@Valid AccountDto", ""),
            ("creditCard", "@Valid CardDto", ""),
        ], kind="owned"))
        s.node("AccountDto", table_node("AccountDto  (register / PUT .../account)", [
            ("givenName", "@Size(max=60)", ""),
            ("familyName", "@Size(max=60)", ""),
            ("email", "@Email @Size(max=120)  (optional)", ""),
            ("telephone", "@Size(max=30)", ""),
            ("streetName1", "@Size(max=120)", ""),
            ("streetName2", "@Size(max=120)  (optional)", ""),
            ("city / state", "@Size(max=60)", ""),
            ("zipCode", "@Size(max=12)", ""),
            ("country", "@Size(max=60)  (optional)", ""),
        ], kind="owned"))
        s.node("ProfileDto", table_node("ProfileDto  (PUT .../profile)", [
            ("preferredLanguage", "@Size(max=10)", ""),
            ("favoriteCategory", "@Size(max=60)", ""),
            ("myListPreference", "boolean", ""),
            ("bannerPreference", "boolean", ""),
        ], kind="owned"))
        s.node("CardDto", table_node("CardDto  (register / PUT .../card)", [
            ("cardNumber", "@Size(max=24)", ""),
            ("cardType", "@Size(max=30)", ""),
            ("expiryDate", "@Size(max=10)", ""),
        ], kind="owned"))

    # ---- response DTO contract (client SDK) ----
    def _resp(s):
        s.node("CustomerView", table_node("CustomerView  (GET/PUT response)", [
            ("userId", "String", "pk"),
            ("account", "Map<String,Object> (incl. status)", ""),
            ("profile", "Map<String,Object>", ""),
            ("cardMasked", "**** **** **** 1111  (never raw PAN)", ""),
        ], kind="owned"))
        s.node("AuthResult", table_node("AuthResult  (login response)", [
            ("token", "JWT (issued by auth-service)", ""),
            ("customerId", "String", ""),
            ("roles", "List<String>  admin->ADMIN else USER", ""),
        ], kind="owned"))

    cluster(g, "db", "Persistence  (H2 — jdbc:h2:file:./data/customer)", _db, TABLE_KIND["owned"][0], TABLE_KIND["owned"][1])
    cluster(g, "ext", "External credential store", _ext, TABLE_KIND["external"][0], TABLE_KIND["external"][1])
    cluster(g, "req", "Request DTOs  (customer-service-client)", _req, PALETTE["client"][0], PALETTE["client"][1])
    cluster(g, "resp", "Response DTOs  (customer-service-client)", _resp, PALETTE["client"][0], PALETTE["client"][1])

    # mapping / key-sharing edges
    edge(g, "app_user", "customer", "fk", "user_id shared (opaque key)")
    edge(g, "RegisterRequest", "customer", "flow", "register -> row")
    edge(g, "AccountDto", "customer", "flow", "account cols")
    edge(g, "ProfileDto", "customer", "flow", "profile cols")
    edge(g, "CardDto", "customer", "flow", "card cols (stored plaintext)")
    edge(g, "customer", "CustomerView", "flow", "toView (card masked)")

    legend(g, [
        (TABLE_KIND["owned"][0], "Table owned by customer-service"),
        (TABLE_KIND["external"][0], "Owned by auth-service (referenced)"),
        (PALETTE["client"][0], "Wire DTO (client SDK contract)"),
        ("#FFFFFF", "crow = shared key    ..> = field mapping"),
    ])

    png, svg = render(g, "customer-service_schema")
    print("schema:", png, svg)


if __name__ == "__main__":
    build_class_diagram()
    build_schema_diagram()
