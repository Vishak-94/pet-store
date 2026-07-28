#!/usr/bin/env python3
"""
Low-Level-Design diagram generator for inventory-service (fulfilment + inventory).

Renders two diagrams into this module's docs/ using the shared house-style library:
  * inventory-service_class.png/.svg  — UML class diagram (web / service / messaging /
    ports / adapters / client SDK / shared libs), showing the hexagonal port-adapter
    seams, JMS async edges, and the reusable client SDK.
  * inventory-service_schema.png/.svg — the persistence schema (inventory + fulfilled_order
    tables) PLUS the JMS message-schema (event envelope + consumed/produced records) that
    this service is wired to on the broker.

Every class, field, method, table, column, event and destination below is extracted from
the REAL source under inventory-service/ (app + client) and the shared petstore-messaging
library — nothing invented.
"""

import sys, os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND


# ─────────────────────────────────────────────────────────────────────────────
# (a) CLASS DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_class_diagram():
    g = new_graph("inventory-service — class design (hexagonal: web/messaging → service → ports → JPA adapters)", rankdir="TB")

    # ---- Web layer -----------------------------------------------------------
    def _web(s):
        s.node("apiCtl", uml_class(
            "InventoryApiController", "RestController",
            attrs=["InventoryStore inventory", "RestockService restockService"],
            methods=["inventory() : Map<String,Integer>",
                     "availability(itemId) : Map  «public»",
                     "restock(itemId, qty) : ResponseEntity"],
            kind="web"))
        s.node("uiCtl", uml_class(
            "InventoryUiController", "Controller",
            attrs=["InventoryStore inventory", "RestockService restockService"],
            methods=["inventory(Model) : String", "restock(itemId, qty) : String"],
            kind="web"))
        s.node("loginCtl", uml_class(
            "InventoryLoginController", "Controller",
            attrs=["AuthClient auth", "JWT_COOKIE = \"jwt-inventory\""],
            methods=["doLogin(user, pwd, resp, model)", "logout(resp)"],
            kind="web",
            note="drops RS256 in jwt-inventory cookie"))

    # ---- Application / service core -----------------------------------------
    def _service(s):
        s.node("fulfil", uml_class(
            "FulfilmentService", "Service",
            attrs=["InventoryStore inventory", "FulfilledOrderStore fulfilledOrders"],
            methods=["fulfil(OrderApprovedEvent) : boolean  @Transactional"],
            kind="service",
            note="all-or-nothing; orderId dedup; lock 2nd pass"))
        s.node("backorder", uml_class(
            "BackorderException", "RuntimeException",
            kind="service",
            note="rolls back partial reservation"))
        s.node("restock", uml_class(
            "RestockService", "Service",
            attrs=["InventoryStore inventory", "MessagePublisher publisher"],
            methods=["restock(itemId, qty) : int"],
            kind="service",
            note="addQuantity + publish RestockEvent"))

    # ---- Inbound messaging adapter ------------------------------------------
    def _messaging(s):
        s.node("listener", uml_class(
            "OrderApprovedListener", "Component / @JmsListener",
            attrs=["FulfilmentService fulfilment", "MessagePublisher publisher"],
            methods=["onApprovedOrder(OrderApprovedEvent)"],
            kind="messaging",
            note="ApprovedOrderQueue → fulfil → InvoiceTopic"))

    # ---- Ports (SPI seams) ---------------------------------------------------
    def _ports(s):
        s.node("invStore", uml_class(
            "InventoryStore", "interface (port)",
            methods=["quantityOf(itemId) : Optional<Integer>",
                     "tryReserve(itemId, qty) : boolean",
                     "addQuantity(itemId, qty)",
                     "all() : Map<String,Integer>"],
            kind="port"))
        s.node("fulStore", uml_class(
            "FulfilledOrderStore", "interface (port)",
            methods=["isFulfilled(orderId) : boolean",
                     "markFulfilled(orderId)"],
            kind="port",
            note="orderId-keyed dedup ledger"))

    # ---- Persistence adapters ------------------------------------------------
    def _adapters(s):
        s.node("jpaInv", uml_class(
            "JpaInventoryStore", "Repository (adapter)",
            attrs=["InventoryJpaRepository jpa"],
            methods=["tryReserve(..) @Transactional", "addQuantity(..) @Transactional",
                     "quantityOf(..)", "all()"],
            kind="adapter"))
        s.node("jpaFul", uml_class(
            "JpaFulfilledOrderStore", "Repository (adapter)",
            attrs=["FulfilledOrderJpaRepository jpa"],
            methods=["isFulfilled(orderId)", "markFulfilled(orderId)"],
            kind="adapter"))
        s.node("invRepo", uml_class(
            "InventoryJpaRepository", "interface : JpaRepository",
            methods=["findByIdForUpdate(itemId)  @Lock(PESSIMISTIC_WRITE)",
                     "increment(itemId, qty)  @Modifying"],
            kind="adapter"))
        s.node("fulRepo", uml_class(
            "FulfilledOrderJpaRepository", "interface : JpaRepository",
            methods=["existsById(orderId)", "save(entity)"],
            kind="adapter"))
        s.node("invEnt", uml_class(
            "InventoryEntity", "@Entity  inventory",
            attrs=["@Id String itemId", "int quantity"],
            kind="entity"))
        s.node("fulEnt", uml_class(
            "FulfilledOrderEntity", "@Entity  fulfilled_order",
            attrs=["@Id String orderId"],
            kind="entity"))

    # ---- Reusable client SDK jar --------------------------------------------
    def _client(s):
        s.node("invClient", uml_class(
            "InventoryClient", "client SDK (RestClient)",
            attrs=["RestClient http", "SingleFlightStockCache cache", "DEFAULT_TTL = 1h"],
            methods=["stockFor(itemId) : Optional<Integer>"],
            kind="client",
            note="reused by storefront stock badge"))
        s.node("endpoints", uml_class(
            "InventoryServiceEndpoints", "contract constants",
            attrs=["ALL_INVENTORY", "AVAILABILITY", "RESTOCK", "KEY_QUANTITY"],
            kind="client",
            note="single-sourced paths (server maps same)"))
        s.node("cache", uml_class(
            "SingleFlightStockCache", "TTL / single-flight cache",
            attrs=["ttlNanos", "ConcurrentHashMap entries", "locks"],
            methods=["get(itemId, loader) : Optional<Integer>"],
            kind="client"))

    # ---- Config / security ---------------------------------------------------
    def _config(s):
        s.node("secCfg", uml_class(
            "SecurityConfig", "Configuration",
            methods=["jwtVerifier() : JwtVerifier", "authClient(baseUrl) : AuthClient",
                     "filterChain(http, verifier, env)"],
            kind="config",
            note="verify-only RS256; SUPPLIER/ADMIN; dev-only H2 console"))
        s.node("app", uml_class(
            "InventoryServiceApplication", "@SpringBootApplication",
            methods=["main(args)"],
            kind="config",
            note="scans com.petstore.inventory + .messaging"))

    # ---- Shared libs (owned elsewhere) --------------------------------------
    def _shared(s):
        s.node("publisher", uml_class(
            "MessagePublisher", "Component (petstore-messaging)",
            methods=["publish(Destination, event)"],
            kind="framework"))
        s.node("oae", uml_class(
            "OrderApprovedEvent", "record (petstore-messaging)",
            attrs=["orderId, userId, emailId", "List<Line> lines"],
            kind="framework"))
        s.node("ie", uml_class(
            "InvoiceEvent", "record (petstore-messaging)",
            attrs=["orderId, userId, emailId", "boolean shipped, double totalPrice"],
            kind="framework"))
        s.node("re", uml_class(
            "RestockEvent", "record (petstore-messaging)",
            attrs=["itemId, int quantityAdded"],
            kind="framework"))
        s.node("authClient", uml_class(
            "AuthClient / AuthJwtFilter", "auth-client lib",
            methods=["login(user, pwd)", "verify RS256 (public key)"],
            kind="framework"))

    cluster(g, "web", "Web  (com.petstore.inventory.web)", _web, "#EAF2FB")
    cluster(g, "msg", "Messaging  (inbound JMS adapter)", _messaging, "#E7F6EA")
    cluster(g, "svc", "Service  (application core)", _service, "#E7F4EF")
    cluster(g, "port", "Ports  (SPI interfaces)", _ports, "#FFF9E6")
    cluster(g, "adp", "Persistence adapters  (repository.jpa)", _adapters, "#FCEFE0")
    cluster(g, "sdk", "Client SDK jar  (inventory-service-client — reused by callers)", _client, "#E9F1FA")
    cluster(g, "cfg", "Config / Security", _config, "#EEEEEE")
    cluster(g, "shared", "Shared libraries  (petstore-messaging / auth-client)", _shared, "#F3F3F3")

    # ---- Relationships -------------------------------------------------------
    # web → service / port
    edge(g, "apiCtl", "invStore", "depends")
    edge(g, "apiCtl", "restock", "depends")
    edge(g, "apiCtl", "endpoints", "depends", "paths")
    edge(g, "uiCtl", "invStore", "depends")
    edge(g, "uiCtl", "restock", "depends")
    edge(g, "loginCtl", "authClient", "depends", "login")

    # messaging → service, events
    edge(g, "listener", "fulfil", "depends", "fulfil()")
    edge(g, "listener", "publisher", "depends")
    edge(g, "listener", "oae", "async", "consume ApprovedOrderQueue")
    edge(g, "listener", "ie", "async", "publish InvoiceTopic")

    # service → ports + throws
    edge(g, "fulfil", "invStore", "depends", "port")
    edge(g, "fulfil", "fulStore", "depends", "dedup")
    edge(g, "fulfil", "backorder", "depends", "throws")
    edge(g, "restock", "invStore", "depends")
    edge(g, "restock", "publisher", "depends")
    edge(g, "restock", "re", "async", "publish RestockTopic")

    # adapters realize ports
    edge(g, "jpaInv", "invStore", "impl")
    edge(g, "jpaFul", "fulStore", "impl")
    edge(g, "jpaInv", "invRepo", "depends")
    edge(g, "jpaFul", "fulRepo", "depends")
    edge(g, "invRepo", "invEnt", "depends")
    edge(g, "fulRepo", "fulEnt", "depends")

    # client SDK internal
    edge(g, "invClient", "cache", "compose")
    edge(g, "invClient", "endpoints", "depends")

    # config
    edge(g, "secCfg", "authClient", "depends")
    edge(g, "publisher", "re", "depends")

    legend(g, [
        (PALETTE["web"][0], "Web (controllers)"),
        (PALETTE["messaging"][0], "Messaging (JMS listener)"),
        (PALETTE["service"][0], "Service (core)"),
        (PALETTE["port"][0], "Port (interface / SPI seam)"),
        (PALETTE["adapter"][0], "Persistence adapter"),
        (PALETTE["entity"][0], "JPA entity"),
        (PALETTE["client"][0], "Client SDK (reused jar)"),
        (PALETTE["config"][0], "Config / Security"),
        (PALETTE["framework"][0], "Shared library (external)"),
        ("#3E9B54", "async — JMS publish/consume"),
        ("#555555", "depends → · impl ..|> · compose ◆"),
    ])
    return g


# ─────────────────────────────────────────────────────────────────────────────
# (b) SCHEMA + MESSAGE-SCHEMA DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_schema_diagram():
    g = new_graph("inventory-service — persistence schema (H2) + JMS message contract", rankdir="LR")

    # ---- Owned relational tables (resources/schema.sql) ---------------------
    def _tables(s):
        s.node("t_inv", table_node("inventory", [
            ("item_id", "VARCHAR(10)", "pk"),
            ("quantity", "INTEGER NOT NULL  CHECK >= 0", ""),
        ], kind="owned"))
        s.node("t_ful", table_node("fulfilled_order", [
            ("order_id", "VARCHAR(64)", "pk"),
        ], kind="owned"))
        # documentary: the dropped legacy ledger
        s.node("t_dropped", table_node("processed_event  (DROPPED)", [
            ("event_id", "eventId-keyed — removed", ""),
        ], kind="external"))

    # ---- JMS message schema: envelope + records (petstore-messaging) --------
    def _envelope(s):
        s.node("m_meta", table_node("EventMeta  (envelope)", [
            ("eventId", "String — unique per msg", ""),
            ("type", "String — _type id", ""),
            ("occurredAt", "String — ISO-8601", ""),
            ("correlationId", "String — trace", ""),
        ], kind="external"))

    def _consumed(s):
        s.node("m_oae", table_node("OrderApprovedEvent  ⇩ consume", [
            ("meta", "EventMeta", "fk"),
            ("orderId", "String", ""),
            ("userId / emailId", "String", ""),
            ("locale", "String", ""),
            ("lines[]", "Line(itemId, productId, categoryId,", ""),
            ("lines[].*", "quantity, unitPrice)", ""),
        ], kind="external"))

    def _produced(s):
        s.node("m_ie", table_node("InvoiceEvent  ⇧ publish", [
            ("meta", "EventMeta", "fk"),
            ("orderId", "String", ""),
            ("userId / emailId", "String", ""),
            ("shipped", "boolean", ""),
            ("totalPrice", "double", ""),
        ], kind="owned"))
        s.node("m_re", table_node("RestockEvent  ⇧ publish", [
            ("meta", "EventMeta", "fk"),
            ("itemId", "String", ""),
            ("quantityAdded", "int", ""),
        ], kind="owned"))

    # ---- JMS destinations (Destinations registry) ---------------------------
    def _dest(s):
        s.node("d_appr", uml_class("ApprovedOrderQueue", "QUEUE (consume)",
                                   kind="messaging"))
        s.node("d_inv", uml_class("InvoiceTopic", "TOPIC (produce, fan-out)",
                                  kind="messaging"))
        s.node("d_rst", uml_class("RestockTopic", "TOPIC (produce)",
                                  kind="messaging"))

    cluster(g, "db", "Owned H2 tables  (resources/schema.sql · data.sql seed)", _tables, "#EAF7EE")
    cluster(g, "env", "Event envelope  (shared petstore-messaging)", _envelope, "#F3F3F3")
    cluster(g, "cons", "Consumed message", _consumed, "#F3F3F3")
    cluster(g, "prod", "Produced messages", _produced, "#EAF7EE")
    cluster(g, "dst", "JMS destinations  (Destinations registry)", _dest, "#E7F6EA")

    # envelope embedded in every record (compose-like fk)
    edge(g, "m_oae", "m_meta", "fk", "meta")
    edge(g, "m_ie", "m_meta", "fk", "meta")
    edge(g, "m_re", "m_meta", "fk", "meta")

    # message ↔ destination wiring
    edge(g, "d_appr", "m_oae", "async", "deliver")
    edge(g, "m_ie", "d_inv", "async", "publish")
    edge(g, "m_re", "d_rst", "async", "publish")

    # dedup / stock touch points (which table each flow writes)
    edge(g, "m_oae", "t_ful", "depends", "orderId dedup")
    edge(g, "m_oae", "t_inv", "depends", "reserve / decrement")
    edge(g, "m_re", "t_inv", "depends", "addQuantity")

    legend(g, [
        (TABLE_KIND["owned"][0], "Owned table / produced event"),
        (TABLE_KIND["external"][0], "Shared envelope / consumed / dropped"),
        (PALETTE["messaging"][0], "JMS destination (queue/topic)"),
        ("#3E9B54", "async — JMS delivery/publish"),
        ("#2F8F46", "fk — envelope embedded / table touched"),
    ])
    return g


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    cpng, csvg = render(build_class_diagram(), os.path.join(here, "inventory-service_class"))
    spng, ssvg = render(build_schema_diagram(), os.path.join(here, "inventory-service_schema"))
    print("wrote:", cpng, csvg, spng, ssvg)
