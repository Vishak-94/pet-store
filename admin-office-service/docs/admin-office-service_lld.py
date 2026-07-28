#!/usr/bin/env python3
"""LLD diagram generator for admin-office-service (the back-office ADMIN console).

Renders two diagrams into this docs/ folder using the shared house style:
  * admin-office-service_class.png/.svg  — UML class diagram: thin Web layer +
    Config/Security wiring, and the two REUSED client SDKs (order-processing-client,
    auth-client) it delegates every operation to. Owns NO data.
  * admin-office-service_schema.png/.svg — this module has no DB; instead it shows the
    data-aggregation / wire-contract model: the OPC + auth DTOs it consumes (owned by
    other modules) and the in-memory Thymeleaf view-model rows the UI controller builds.

Every class/method/field/DTO/column below is extracted from the real source in this
module and the two client SDK modules it imports — nothing invented.
"""
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND


# ─────────────────────────────────────────────────────────────────────────────
# (a) CLASS DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_class_diagram():
    g = new_graph("admin-office-service — class diagram (thin console delegating via reused client SDKs)", rankdir="TB")

    # ── Web layer (this module) ──────────────────────────────────────────────
    def web(s):
        s.node("app", uml_class(
            "WarehouseServiceApplication", "@SpringBootApplication",
            methods=["main(String[])"], kind="config"))
        s.node("ui", uml_class(
            "WarehouseUiController", "@Controller",
            attrs=["opc: OrderProcessingClient"],
            methods=["orders(req, model)  GET /warehouse/orders",
                     "allOrders(req, model)  GET /warehouse/orders/all",
                     "approve(id, req)  POST .../approve",
                     "deny(id, req)  POST .../deny",
                     "- jwt(req)  «reads jwt-warehouse cookie»"],
            kind="web"))
        s.node("api", uml_class(
            "WarehouseApiController", "@RestController",
            attrs=["opc: OrderProcessingClient"],
            methods=["ordersByStatus(status, req)  GET /api/orders",
                     "order(id, req)  GET /api/orders/{id}",
                     "approve / deny(id, req)",
                     "updateOrders(OrderApprovalDto, req)  batch",
                     "sales(start, end, category, req)",
                     "- bearer(req)  «reads Authorization header»"],
            kind="web"))
        s.node("login", uml_class(
            "WarehouseLoginController", "@Controller",
            attrs=["auth: AuthClient", "cookieSecure: boolean",
                   "JWT_COOKIE = \"jwt-warehouse\""],
            methods=["loginPage()  GET /warehouse/login",
                     "doLogin(user, pass, resp)",
                     "logout(resp)",
                     "- jwtCookie() / evictLegacyRootCookies()"],
            kind="web"))
        s.node("errs", uml_class(
            "ApiExceptionHandler", "@RestControllerAdvice",
            methods=["illegalState(e) → 409", "notFound(e) → 404",
                     "upstreamError(HttpServerError) → 502",
                     "upstreamUnavailable(RestClientEx) → 503"],
            kind="web"))

    # ── Config / Security (this module) ──────────────────────────────────────
    def cfg(s):
        s.node("sec", uml_class(
            "SecurityConfig", "@Configuration",
            methods=["@Bean jwtVerifier(): JwtVerifier",
                     "@Bean authClient(baseUrl): AuthClient",
                     "@Bean orderProcessingClient(baseUrl)",
                     "@Bean filterChain(http, verifier)",
                     "  ADMIN role + STATELESS + JSON/redirect split"],
            note="CSRF disabled (local demo); jwt-warehouse cookie SameSite=Strict",
            kind="config"))
        s.node("rrc", uml_class(
            "ResilientRestClient", "factory (final)",
            methods=["forService(name, baseUrl): RestClient",
                     "  circuit-breaker (all) + GET-only retry"],
            note="Resilience4j; lives in the app, not the SDK jars",
            kind="config"))

    # ── order-processing-client SDK (REUSED, from order-processing-service) ───
    def opc_sdk(s):
        s.node("opcClient", uml_class(
            "OrderProcessingClient", "client SDK",
            attrs=["http: RestClient"],
            methods=["ordersByStatus(status, bearer): OrdersByStatus",
                     "allOrders(bearer): List<OrderSummaryDto>",
                     "getOrder(id, bearer): Optional<OrderView>",
                     "approve / deny(id, bearer): void",
                     "updateOrders(OrderApprovalDto, bearer): void",
                     "sales(start, end, category, bearer): SalesReportDto"],
            kind="client"))
        s.node("opcEnd", uml_class(
            "OrderProcessingEndpoints", "contract constants",
            attrs=["ORDERS = /api/orders", "ORDER_BY_ID / ORDER_APPROVE / ORDER_DENY",
                   "ORDER_APPROVALS / SALES", "PARAM_STATUS / _START / _END / _CATEGORY"],
            kind="client"))
        s.node("opcDtos", uml_class(
            "OrderDtos", "wire DTOs",
            attrs=["OrderView / OrderSummaryDto / LineDto", "OrdersByStatus",
                   "OrderApprovalDto / OrderStatusChangeDto", "SalesReportDto / SalesBucketDto"],
            kind="client"))

    # ── auth-client SDK (REUSED, from auth-service) ──────────────────────────
    def auth_sdk(s):
        s.node("authClient", uml_class(
            "AuthClient", "client SDK",
            attrs=["http: RestClient", "LOGIN = /auth/login"],
            methods=["login(user, pass): Optional<LoginResult>"],
            kind="client"))
        s.node("authFilter", uml_class(
            "AuthJwtFilter", "OncePerRequestFilter",
            attrs=["verifier: JwtVerifier", "cookieName"],
            methods=["doFilterInternal(...)  Bearer/cookie → ROLE_*"],
            kind="framework"))
        s.node("verifier", uml_class(
            "JwtVerifier", "verify-only",
            methods=["verify(token): AuthClaims  «public key»"],
            kind="framework"))

    # ── External services (owned elsewhere) ──────────────────────────────────
    def ext(s):
        s.node("opcSvc", uml_class(
            "order-processing-service", ":8088 — OPCAdminFacade",
            attrs=["owns order data + workflow", "re-enforces ROLE_ADMIN"],
            kind="external"))
        s.node("authSvc", uml_class(
            "auth-service", ":8086 — IdP",
            attrs=["mints RS256 tokens", "the only credential store"],
            kind="external"))

    cluster(g, "web", "Web layer  (com.petstore.warehouse.web)", web, "#EAF2FB", "#2E6DB4")
    cluster(g, "cfg", "Config / Security  (config + security)", cfg, "#F0F0F0", "#666666")
    cluster(g, "opc", "REUSED SDK · order-processing-client", opc_sdk, "#E7F1FB", "#4A7FB5")
    cluster(g, "auth", "REUSED SDK · auth-client", auth_sdk, "#E7F1FB", "#4A7FB5")
    cluster(g, "ext", "External services (owned elsewhere)", ext, "#F5F5F5", "#AAAAAA")

    # Delegation edges (this console owns no data → everything is a delegate call)
    edge(g, "app", "sec", "depends", "boots")
    edge(g, "ui", "opcClient", "depends", "delegates")
    edge(g, "api", "opcClient", "depends", "delegates")
    edge(g, "api", "opcEnd", "depends", "path/param constants")
    edge(g, "api", "opcDtos", "depends", "DTOs")
    edge(g, "login", "authClient", "depends", "delegates login")
    edge(g, "errs", "opcClient", "flow", "maps RestClient faults")

    # Bean wiring
    edge(g, "sec", "opcClient", "compose", "@Bean")
    edge(g, "sec", "authClient", "compose", "@Bean")
    edge(g, "sec", "rrc", "depends", "forService(...)")
    edge(g, "sec", "verifier", "compose", "@Bean")
    edge(g, "sec", "authFilter", "depends", "addFilterBefore")
    edge(g, "rrc", "opcClient", "flow", "resilient RestClient")
    edge(g, "rrc", "authClient", "flow", "resilient RestClient")

    # SDK internals
    edge(g, "opcClient", "opcEnd", "depends")
    edge(g, "opcClient", "opcDtos", "depends")
    edge(g, "authFilter", "verifier", "depends")

    # SDK → real service over HTTP + Bearer
    edge(g, "opcClient", "opcSvc", "flow", "HTTP + Bearer")
    edge(g, "authClient", "authSvc", "flow", "HTTP POST")

    legend(g, [
        (PALETTE["web"][0], "Web (controllers / advice)"),
        (PALETTE["config"][0], "Config / Security wiring"),
        (PALETTE["client"][0], "Reused client SDK"),
        (PALETTE["framework"][0], "auth-client filter/verifier"),
        (PALETTE["external"][0], "External service (owned elsewhere)"),
        ("#FFFFFF", "→ depends   ⋯> flow/HTTP   ◆ @Bean compose"),
    ])
    render(g, "admin-office-service_class")


# ─────────────────────────────────────────────────────────────────────────────
# (b) SCHEMA / DATA-MODEL DIAGRAM  (no DB — wire contract + view model)
# ─────────────────────────────────────────────────────────────────────────────
def build_schema_diagram():
    g = new_graph("admin-office-service — data-aggregation model (no DB: consumed DTOs + Thymeleaf view rows)", rankdir="LR")

    # ── DTOs consumed from order-processing-client (owned by OPC) ─────────────
    def opc_dtos(s):
        s.node("ordersByStatus", table_node("OrdersByStatus", [
            ("status", "String", ""),
            ("orderIds", "List<String>", ""),
            ("count", "int", ""),
        ], kind="external"))
        s.node("orderView", table_node("OrderView", [
            ("orderId", "String", "pk"),
            ("userId", "String", ""),
            ("emailId", "String", ""),
            ("locale", "String", ""),
            ("totalPrice", "double", ""),
            ("status", "String", ""),
            ("lines", "List<LineDto>", ""),
            ("currency", "String", ""),
        ], kind="external"))
        s.node("orderSummary", table_node("OrderSummaryDto", [
            ("orderId", "String", "pk"),
            ("userId", "String", ""),
            ("totalPrice", "double", ""),
            ("status", "String", ""),
            ("created", "Instant", ""),
            ("lineCount", "int", ""),
        ], kind="external"))
        s.node("lineDto", table_node("LineDto", [
            ("itemId", "String", "fk"),
            ("productId", "String", "fk"),
            ("categoryId", "String", "fk"),
            ("quantity", "int", ""),
            ("unitPrice", "double", ""),
        ], kind="external"))
        s.node("approvalDto", table_node("OrderApprovalDto", [
            ("orders", "List<OrderStatusChangeDto>", ""),
        ], kind="external"))
        s.node("statusChange", table_node("OrderStatusChangeDto", [
            ("orderId", "String", "fk"),
            ("newStatus", "String", ""),
        ], kind="external"))
        s.node("salesReport", table_node("SalesReportDto", [
            ("groupBy", "String (category|item)", ""),
            ("buckets", "List<SalesBucketDto>", ""),
        ], kind="external"))
        s.node("salesBucket", table_node("SalesBucketDto", [
            ("key", "String (category|item id)", ""),
            ("revenue", "double", ""),
            ("quantity", "int", ""),
        ], kind="external"))

    # ── DTOs consumed from auth-client (owned by auth-service) ───────────────
    def auth_dtos(s):
        s.node("loginResult", table_node("AuthClient.LoginResult", [
            ("token", "String (RS256 JWT)", "pk"),
            ("userId", "String", ""),
            ("roles", "List<String>", ""),
        ], kind="external"))
        s.node("authClaims", table_node("AuthClaims", [
            ("userId", "String (uid)", "pk"),
            ("username", "String (sub)", ""),
            ("roles", "List<String>", ""),
        ], kind="external"))

    # ── In-memory Thymeleaf view rows built by WarehouseUiController (owned) ──
    def view_model(s):
        s.node("pendingRow", table_node("pending[]  (Map row → orders.html)", [
            ("orderId", "from OrderView.orderId", "fk"),
            ("user", "from OrderView.userId", ""),
            ("total", "from OrderView.totalPrice", ""),
            ("lines", "OrderView.lines.size()", ""),
        ], kind="owned"))
        s.node("allRow", table_node("orders[]  (Map row → all_orders.html)", [
            ("orderId", "from OrderSummaryDto.orderId", "fk"),
            ("user", "from OrderSummaryDto.userId", ""),
            ("received", "RECEIVED_FMT(created)", ""),
            ("status", "from OrderSummaryDto.status", ""),
            ("lines", "OrderSummaryDto.lineCount", ""),
            ("total", "OrderSummaryDto.totalPrice", ""),
        ], kind="owned"))
        s.node("jwtCookie", table_node("jwt-warehouse cookie  (owned)", [
            ("name", "jwt-warehouse", "pk"),
            ("value", "RS256 token (from LoginResult)", ""),
            ("path", "/warehouse", ""),
            ("flags", "HttpOnly · SameSite=Strict · Secure?", ""),
        ], kind="owned"))

    cluster(g, "opc", "Consumed from order-processing-client (owned by OPC :8088)", opc_dtos, "#F5F5F5", "#AAAAAA")
    cluster(g, "auth", "Consumed from auth-client (owned by auth-service :8086)", auth_dtos, "#F5F5F5", "#AAAAAA")
    cluster(g, "vm", "In-memory view model — built by WarehouseUiController (owned here)", view_model, "#EAF7EE", "#2F8F46")

    # DTO composition (real record nesting)
    edge(g, "orderView", "lineDto", "fk", "lines[]")
    edge(g, "orderSummary", "lineDto", "fk", "lineCount")
    edge(g, "approvalDto", "statusChange", "fk", "orders[]")
    edge(g, "salesReport", "salesBucket", "fk", "buckets[]")

    # Aggregation: view rows are projected from the consumed DTOs
    edge(g, "orderView", "pendingRow", "flow", "getOrder → row")
    edge(g, "orderSummary", "allRow", "flow", "allOrders → row")
    edge(g, "ordersByStatus", "pendingRow", "flow", "id list drives fetch")
    edge(g, "loginResult", "jwtCookie", "flow", "token → cookie")
    edge(g, "loginResult", "authClaims", "flow", "verified per request")

    legend(g, [
        (TABLE_KIND["external"][0], "DTO owned by another module (consumed via SDK)"),
        (TABLE_KIND["owned"][0], "In-memory view model owned by this console"),
        ("#FFFFFF", "→ nested record / projection (no DB — nothing persisted here)"),
    ])
    render(g, "admin-office-service_schema")


if __name__ == "__main__":
    build_class_diagram()
    build_schema_diagram()
    print("wrote admin-office-service_class.{png,svg} and admin-office-service_schema.{png,svg}")
