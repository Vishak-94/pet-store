#!/usr/bin/env python3
"""
Low-Level-Design diagram generator for the **order-processing-service** (OPC) module.

Emits two diagrams into this docs/ folder using the shared house-style library:
  * order-processing-service_class.png/svg  — UML class diagram (layers, port/adapter
    seams, transactional-outbox pattern, gateways, client-SDK reuse, @Profile store swap).
  * order-processing-service_schema.png/svg — data-model diagram: the H2/JPA relational
    schema (wh_order + wh_line + outbox) AND the mongo-profile document model, plus the
    outbound event/message contract the outbox carries.

Every class, field, table, column, event and destination below is extracted from the real
source under app/src + client/src + resources/db/migration (no invented names).
"""

import sys, os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND


# ══════════════════════════════════════════════════════════════════════════════
#  (a) CLASS DIAGRAM
# ══════════════════════════════════════════════════════════════════════════════
def build_class_diagram():
    g = new_graph("order-processing-service — class design (hexagonal + transactional outbox)", rankdir="TB")

    # ── Web layer ───────────────────────────────────────────────────────────────
    def web(s):
        s.node("Ctl", uml_class(
            "OrderProcessingApiController", "RestController",
            methods=["intake(CheckoutRequest)", "byStatus(status)", "allOrders()",
                     "getOrder(id)", "status(id)", "approve(id)", "deny(id)",
                     "updateOrders(OrderApprovalDto)", "sales(start,end,category)"],
            kind="web", note="reuses client-SDK DTOs + endpoint constants (single-sourced contract)"))
        s.node("ExHandler", uml_class(
            "ApiExceptionHandler", "RestControllerAdvice",
            methods=["handleBadInput → 400", "handleValidation → 400",
                     "handleIllegalState → 409", "handleOptimisticLock → 409"],
            kind="web"))
        s.node("CidFilter", uml_class(
            "CorrelationIdFilter", "OncePerRequestFilter",
            methods=["doFilterInternal(...)"], kind="web",
            note="seeds Correlation MDC (X-Correlation-Id) for the whole trace"))

    # ── Service layer ─────────────────────────────────────────────────────────────
    def service(s):
        s.node("Fulfil", uml_class(
            "FulfilmentService", "Service",
            methods=["@Transactional receiveOrder(WarehouseOrder)"],
            kind="service", note="intake + auto-approve decision; idempotent on findById"))
        s.node("Admin", uml_class(
            "AdminService", "Service",
            methods=["ordersByStatus(status)", "allOrders()", "approve(id)", "deny(id)",
                     "updateOrders(List<OrderStatusChange>)",
                     "redriveApprovedForFulfilment()", "statusOf(id)",
                     "salesReport(start,end,cat)", "-applyStatusChange(id,target)"],
            kind="service"))
        s.node("ApGw", uml_class(
            "ApprovalGateway", "Component",
            methods=["dispatchForFulfilment(WarehouseOrder)"],
            kind="service", note="builds OrderApprovedEvent → outbox (NOT direct JMS)"))
        s.node("StGw", uml_class(
            "OrderStatusGateway", "Component",
            methods=["announce(WarehouseOrder, OrderStatus)"],
            kind="service", note="builds OrderStatusEvent → outbox"))
        s.node("OutW", uml_class(
            "OutboxWriter", "Component",
            attrs=["TYPE_BY_CLASS (from MessagingConfig.TYPE_IDS)", "ObjectMapper"],
            methods=["enqueue(Destination, event, orderId)"],
            kind="service", note="freezes event→JSON in the business txn (write side)"))
        s.node("OutR", uml_class(
            "OutboxRelay", "Component @Scheduled",
            attrs=["batchSize", "maxAttempts"],
            methods=["@Scheduled publishPending()", "-publishOne(msg)"],
            kind="service", note="polls unsent rows → MessagePublisher; at-least-once; parks poison rows (read side)"))

    # ── Domain (framework-free) ───────────────────────────────────────────────────
    def domain(s):
        s.node("WhOrder", uml_class(
            "WarehouseOrder", "record",
            attrs=["orderId", "userId", "emailId", "locale", "currency", "totalPrice",
                   "status: OrderStatus", "lines: List<OrderLine>",
                   "shipTo/billTo: ContactInfo", "created: Instant"],
            kind="domain"))
        s.node("OStatus", uml_class(
            "OrderStatus", "enum",
            attrs=["PENDING", "APPROVED", "DENIED", "COMPLETED"],
            methods=["canGoTo(target): boolean"], kind="domain",
            note="PENDING→{APPROVED,DENIED}; APPROVED→COMPLETED; DENIED/COMPLETED terminal"))
        s.node("OLine", uml_class(
            "OrderLine", "record",
            attrs=["itemId", "productId", "categoryId", "quantity", "unitPrice"], kind="domain"))
        s.node("Contact", uml_class(
            "ContactInfo", "record",
            attrs=["familyName", "givenName", "street1/2", "city/state/zip", "country", "telephone", "email"],
            kind="domain"))
        s.node("OSChange", uml_class(
            "OrderStatusChange", "record",
            attrs=["orderId", "newStatus: OrderStatus"], kind="domain"))
        s.node("Sales", uml_class(
            "SalesReport (+ SalesBucket)", "record",
            attrs=["groupBy: category|item", "buckets: List<SalesBucket{key,revenue,quantity}>"],
            kind="domain"))
        s.node("Policy", uml_class(
            "ApprovalPolicy", "Component",
            methods=["canAutoApprove(totalPrice, currency)"],
            kind="domain", note="USD<500, JPY<50000 else PENDING (legacy canIApprove)"))

    # ── Messaging (JMS listeners) ─────────────────────────────────────────────────
    def messaging(s):
        s.node("OrderL", uml_class(
            "OrderListener", "@JmsListener @Deprecated",
            methods=["onOrder(PurchaseOrderEvent)"], kind="messaging",
            note="PurchaseOrderQueue (queue); superseded by REST /intake, kept as fallback"))
        s.node("InvL", uml_class(
            "InvoiceListener", "@JmsListener",
            methods=["onInvoice(InvoiceEvent)"], kind="messaging",
            note="InvoiceTopic sub 'opc-invoice'; shipped → COMPLETED"))
        s.node("RestockL", uml_class(
            "RestockListener", "@JmsListener",
            methods=["onRestock(RestockEvent)"], kind="messaging",
            note="RestockTopic sub 'opc-restock'; re-drives APPROVED backorders"))

    # ── Ports (SPI seams) ─────────────────────────────────────────────────────────
    def ports(s):
        s.node("OStore", uml_class(
            "OrderStore", "interface (port)",
            methods=["save(WarehouseOrder)", "findById(id)", "updateStatus(id,status)",
                     "statusOf(id)", "orderIdsByStatus(status)", "findAllByCreatedDesc()",
                     "aggregateSales(start,end,cat)"],
            kind="port"))
        s.node("OutStore", uml_class(
            "OutboxStore", "interface (port)",
            methods=["enqueue(OutboxMessage)", "fetchUnpublished(limit,maxAttempts)",
                     "markPublished(id)", "recordFailure(id): int"],
            kind="port"))
        s.node("OutMsg", uml_class(
            "OutboxMessage", "record (port DTO)",
            attrs=["id: String (store-neutral)", "destination", "topic", "eventType",
                   "payload (JSON)", "orderId"],
            methods=["pending(...)"], kind="port"))

    # ── JPA adapter (default profile: !mongo) ──────────────────────────────────────
    def jpa(s):
        s.node("JpaOS", uml_class(
            "JpaOrderStore", 'Repository @Profile("!mongo")',
            methods=["implements OrderStore", "-toDomain/-toEmbeddable"], kind="adapter"))
        s.node("JpaOutS", uml_class(
            "JpaOutboxStore", 'Repository @Profile("!mongo")',
            methods=["implements OutboxStore", "Long↔String id mapping"], kind="adapter"))
        s.node("WhRepo", uml_class(
            "WarehouseOrderJpaRepository", "Spring Data JPA",
            methods=["findByStatus", "findAllByOrderByCreatedDesc",
                     "aggregateByCategory", "aggregateByItem"], kind="adapter"))
        s.node("OutRepo", uml_class(
            "OutboxJpaRepository", "Spring Data JPA",
            methods=["findByPublishedAtIsNullAnd…", "@Modifying markPublished",
                     "@Modifying incrementAttempts"], kind="adapter"))
        s.node("WhEnt", uml_class(
            "WarehouseOrderEntity", "@Entity wh_order",
            attrs=["@Id orderId", "@Version version", "status", "created",
                   "@OneToMany lines", "@Embedded shipTo/billTo"], kind="entity"))
        s.node("WhLine", uml_class(
            "WarehouseLineEntity", "@Entity wh_line",
            attrs=["@Id id", "itemId", "quantity", "unitPrice"], kind="entity"))
        s.node("CInfoE", uml_class(
            "ContactInfoEmbeddable", "@Embeddable",
            attrs=["embedded twice (ship_*/bill_*)"], kind="entity"))
        s.node("OutEnt", uml_class(
            "OutboxEntity", "@Entity outbox",
            attrs=["@Id id", "destination", "payload(@Lob)", "publishedAt", "attempts"],
            kind="entity"))

    # ── Mongo adapter (mongo profile) ──────────────────────────────────────────────
    def mongo(s):
        s.node("MgOS", uml_class(
            "MongoOrderStore", 'Repository @Profile("mongo")',
            methods=["implements OrderStore", "$unwind/$group sales pipeline"], kind="adapter"))
        s.node("MgOutS", uml_class(
            "MongoOutboxStore", 'Repository @Profile("mongo")',
            methods=["implements OutboxStore", "findAndModify recordFailure"], kind="adapter"))
        s.node("WhDoc", uml_class(
            "WarehouseOrderDocument", "@Document orders",
            attrs=["@Id orderId", "@Version version", "embedded lines[]",
                   "embedded shipTo/billTo"], kind="entity"))
        s.node("OutDoc", uml_class(
            "OutboxDocument", "@Document outbox", attrs=["@Id id", "payload", "attempts"], kind="entity"))
        s.node("MgSchema", uml_class(
            "MongoSchema", "final constants",
            attrs=["collection/field/index names", "ORDER_STATUSES ← OrderStatus"], kind="config"))
        s.node("MgSchemaCfg", uml_class(
            "MongoSchemaConfig", "@Configuration @Profile(mongo)",
            methods=["applySchema() @ApplicationReady", "$jsonSchema validators + indexes"], kind="config"))
        s.node("MgTxCfg", uml_class(
            "MongoTransactionConfig", "@Configuration @Profile(mongo)",
            methods=["MongoTransactionManager bean"], kind="config",
            note="multi-doc txn → outbox atomicity (needs rs0)"))

    # ── Client SDK (separate artifact) ──────────────────────────────────────────────
    def client(s):
        s.node("Client", uml_class(
            "OrderProcessingClient", "client SDK (RestClient)",
            methods=["checkout(req, bearer)", "ordersByStatus", "allOrders",
                     "getOrder", "approve", "deny", "updateOrders", "sales"],
            kind="client", note="imported by admin-office-service; forwards admin Bearer"))
        s.node("Dtos", uml_class(
            "OrderDtos", "wire DTOs",
            attrs=["CheckoutRequest/Response", "OrderView", "OrderSummaryDto", "StatusView",
                   "OrdersByStatus", "OrderApprovalDto", "SalesReportDto"], kind="client"))
        s.node("Endp", uml_class(
            "OrderProcessingEndpoints", "path constants",
            attrs=["ORDER_INTAKE", "ORDERS", "ORDER_BY_ID", "ORDER_APPROVE/DENY",
                   "ORDER_APPROVALS", "SALES"], kind="client"))

    # ── Config / Security ────────────────────────────────────────────────────────
    def config(s):
        s.node("SecCfg", uml_class(
            "SecurityConfig", "@Configuration",
            methods=["filterChain(...)", "JwtVerifier bean (AuthPublicKey.bundled)"],
            kind="config", note="verify-only; /intake = USER|ADMIN, /api/orders/** = ADMIN"))
        s.node("App", uml_class(
            "OrderProcessingApplication", "@SpringBootApplication @EnableScheduling",
            kind="config", note="@EnableScheduling drives OutboxRelay"))

    # ── Shared library (petstore-messaging) ──────────────────────────────────────
    def shared(s):
        s.node("Pub", uml_class(
            "MessagePublisher", "shared lib @Component",
            methods=["publish(Destination, event)"], kind="framework"))
        s.node("Dest", uml_class(
            "Destinations / Events / MessagingConfig", "shared contract",
            attrs=["PURCHASE_ORDER, APPROVED_ORDER (queues)",
                   "INVOICE, ORDER_STATUS, RESTOCK (topics)", "TYPE_IDS, EventMeta"],
            kind="framework"))
        s.node("Auth", uml_class(
            "auth-client (JwtVerifier, AuthJwtFilter)", "shared lib",
            kind="framework", note="RS256 verify with bundled public key"))

    cluster(g, "web", "Web  (REST facade + cross-cutting)", web, "#EAF2FB")
    cluster(g, "svc", "Service  (business logic + outbox write/read)", service, "#E7F4EF")
    cluster(g, "dom", "Domain  (framework-free records / enum / policy)", domain, "#F1EAFB")
    cluster(g, "msg", "Messaging  (JMS consumers)", messaging, "#E4F3E7")
    cluster(g, "port", "Ports  (persistence SPI seams)", ports, "#FFF7DF")
    cluster(g, "jpa", 'Adapters · JPA  (default profile "!mongo")', jpa, "#FBEEDD")
    cluster(g, "mongo", 'Adapters · MongoDB  (profile "mongo")', mongo, "#FBEEDD")
    cluster(g, "cli", "Client SDK  (order-processing-client — separate artifact)", client, "#E4EFF9")
    cluster(g, "cfg", "Config / Security", config, "#ECECEC")
    cluster(g, "sh", "Shared libraries (reused fleet-wide)", shared, "#F0F0F0")

    # ── relationships ─────────────────────────────────────────────────────────────
    # web → service / port
    edge(g, "Ctl", "Fulfil", "depends", "intake")
    edge(g, "Ctl", "Admin", "depends")
    edge(g, "Ctl", "OStore", "depends", "findById")
    edge(g, "Ctl", "Dtos", "flow", "reuses")
    edge(g, "Ctl", "Endp", "flow", "maps")

    # service internal
    edge(g, "Fulfil", "Policy", "depends")
    edge(g, "Fulfil", "ApGw", "depends")
    edge(g, "Fulfil", "OStore", "depends")
    edge(g, "Admin", "OStore", "depends")
    edge(g, "Admin", "ApGw", "depends")
    edge(g, "Admin", "StGw", "depends")
    edge(g, "ApGw", "OutW", "depends", "enqueue")
    edge(g, "StGw", "OutW", "depends", "enqueue")
    edge(g, "OutW", "OutStore", "depends", "write")
    edge(g, "OutR", "OutStore", "depends", "drain")
    edge(g, "OutR", "Pub", "depends", "publish")

    # domain composition
    edge(g, "WhOrder", "OStatus", "depends")
    edge(g, "WhOrder", "OLine", "compose")
    edge(g, "WhOrder", "Contact", "compose", "ship/bill")

    # messaging → service/port
    edge(g, "OrderL", "Fulfil", "depends")
    edge(g, "InvL", "OStore", "depends")
    edge(g, "InvL", "StGw", "depends")
    edge(g, "RestockL", "Admin", "depends", "re-drive")

    # port ↔ adapters (the two-adapter-per-port seam)
    edge(g, "JpaOS", "OStore", "impl")
    edge(g, "MgOS", "OStore", "impl")
    edge(g, "JpaOutS", "OutStore", "impl")
    edge(g, "MgOutS", "OutStore", "impl")
    edge(g, "OutW", "OutMsg", "flow")
    edge(g, "OutR", "OutMsg", "flow")

    # jpa adapter internals
    edge(g, "JpaOS", "WhRepo", "depends")
    edge(g, "JpaOutS", "OutRepo", "depends")
    edge(g, "WhRepo", "WhEnt", "depends")
    edge(g, "OutRepo", "OutEnt", "depends")
    edge(g, "WhEnt", "WhLine", "compose")
    edge(g, "WhEnt", "CInfoE", "compose")
    edge(g, "JpaOS", "WhOrder", "flow", "maps")

    # mongo adapter internals
    edge(g, "MgOS", "WhDoc", "depends")
    edge(g, "MgOutS", "OutDoc", "depends")
    edge(g, "MgSchemaCfg", "MgSchema", "depends")
    edge(g, "MgOS", "MgSchema", "depends")

    # client sdk internal + reuse
    edge(g, "Client", "Dtos", "depends")
    edge(g, "Client", "Endp", "depends")

    # config / security
    edge(g, "SecCfg", "Auth", "depends", "verify")
    edge(g, "App", "OutR", "flow", "@EnableScheduling")

    # gateways build shared events
    edge(g, "ApGw", "Dest", "flow", "OrderApprovedEvent")
    edge(g, "StGw", "Dest", "flow", "OrderStatusEvent")

    legend(g, [
        (PALETTE["web"][0], "Web / REST"),
        (PALETTE["service"][0], "Service / outbox"),
        (PALETTE["domain"][0], "Domain (framework-free)"),
        (PALETTE["messaging"][0], "JMS listener"),
        (PALETTE["port"][0], "Port (interface / SPI seam)"),
        (PALETTE["adapter"][0], "Adapter (JPA / Mongo)"),
        (PALETTE["entity"][0], "Entity / Document"),
        (PALETTE["client"][0], "Client SDK (reused)"),
        (PALETTE["config"][0], "Config / Security"),
        (PALETTE["framework"][0], "Shared library"),
        ("#FFFFFF", "──▷ realizes port   ─▶ depends   ◆ composes   ⇢ flow/reuse"),
    ])
    render(g, "order-processing-service_class")
    print("wrote class diagram")


# ══════════════════════════════════════════════════════════════════════════════
#  (b) SCHEMA / DATA-MODEL DIAGRAM
# ══════════════════════════════════════════════════════════════════════════════
def build_schema_diagram():
    g = new_graph("order-processing-service — persistence schema (H2/JPA + Mongo variant) & outbox event contract",
                  rankdir="LR")

    # ── H2 / JPA relational schema (Flyway V1..V4) ─────────────────────────────────
    def h2(s):
        s.node("wh_order", table_node("wh_order  (Flyway V1/V3/V4)", [
            ("order_id", "VARCHAR(20)", "pk"),
            ("user_id", "VARCHAR(25)", ""),
            ("email_id", "VARCHAR(120)", ""),
            ("locale", "VARCHAR(10)", ""),
            ("currency", "VARCHAR(3)  (V4)", ""),
            ("total_price", "DECIMAL(12,2)", ""),
            ("status", "VARCHAR(20)  enum", ""),
            ("version", "BIGINT  (V3 @Version)", ""),
            ("created", "TIMESTAMP", ""),
            ("ship_* (10 cols)", "embedded ContactInfo", ""),
            ("bill_* (10 cols)", "embedded ContactInfo", ""),
        ], kind="owned"))
        s.node("wh_line", table_node("wh_line  (Flyway V1)", [
            ("id", "BIGINT IDENTITY", "pk"),
            ("order_id", "VARCHAR(20)", "fk"),
            ("item_id", "VARCHAR(10)", ""),
            ("product_id", "VARCHAR(10)", ""),
            ("category_id", "VARCHAR(10)", ""),
            ("quantity", "INTEGER", ""),
            ("unit_price", "DECIMAL(12,2)", ""),
        ], kind="owned"))
        s.node("outbox", table_node("outbox  (Flyway V2)", [
            ("id", "BIGINT IDENTITY", "pk"),
            ("destination", "VARCHAR(60)", ""),
            ("is_topic", "BOOLEAN", ""),
            ("event_type", "VARCHAR(80)", ""),
            ("payload", "CLOB  (event JSON)", ""),
            ("order_id", "VARCHAR(20)", ""),
            ("created_at", "TIMESTAMP", ""),
            ("published_at", "TIMESTAMP  null=unsent", ""),
            ("attempts", "INTEGER  park≥max", ""),
        ], kind="owned"))
        s.node("h2_note", uml_class(
            "H2 file store (default)", "default profile (not mongo)",
            attrs=["jdbc:h2:file:./data/opc", "ddl-auto: none", "schema owned by Flyway",
                   "ix_outbox_unpublished(published_at,id)"],
            kind="config"))

    # ── Mongo document model (profile 'mongo') ─────────────────────────────────────
    def mongo(s):
        s.node("orders_c", table_node("orders  (collection)", [
            ("_id", "string = orderId", "pk"),
            ("userId / emailId / locale", "string", ""),
            ("currency", "string", ""),
            ("totalPrice", "double", ""),
            ("status", "string enum ($jsonSchema)", ""),
            ("version", "long  (@Version)", ""),
            ("created", "date", ""),
            ("lines[]", "embedded array (unwind)", ""),
            ("shipTo / billTo", "embedded sub-doc", ""),
        ], kind="owned"))
        s.node("outbox_c", table_node("outbox  (collection)", [
            ("_id", "ObjectId hex", "pk"),
            ("destination / eventType", "string", ""),
            ("is_topic", "bool", ""),
            ("payload", "string (event JSON)", ""),
            ("orderId", "string", ""),
            ("createdAt / publishedAt", "date", ""),
            ("attempts", "int", ""),
        ], kind="owned"))
        s.node("mongo_note", uml_class(
            "MongoDB (rs0 single-node)", "profile mongo",
            attrs=["lines/contacts EMBEDDED (no join)",
                   "$jsonSchema validators + indexes (MongoSchemaConfig)",
                   "MongoTransactionManager → outbox atomicity",
                   "ix_orders_status / ix_orders_created / ix_outbox_unpublished"],
            kind="config", note="same OrderStore/OutboxStore ports as H2 — swapped by @Profile"))

    # ── Outbound event contract (what the outbox payload carries) ──────────────────
    def events(s):
        s.node("meta", uml_class(
            "EventMeta  (envelope)", "shared record",
            attrs=["eventId  (dedup key)", "type  (= JMS _type)", "occurredAt", "correlationId"],
            kind="messaging"))
        s.node("approved", uml_class(
            "OrderApprovedEvent", 'TYPE="OrderApproved" → ApprovedOrderQueue',
            attrs=["meta", "orderId / userId / emailId / locale", "lines: List<Line>"],
            kind="messaging", note="published on APPROVED → inventory-service fulfils"))
        s.node("status", uml_class(
            "OrderStatusEvent", 'TYPE="OrderStatus" → OrderStatusTopic',
            attrs=["meta", "orderId / userId / emailId", "status", "totalPrice"],
            kind="messaging", note="published on APPROVED/DENIED/COMPLETED → customer email"))
        s.node("inbound", uml_class(
            "Inbound (consumed)", "JMS in",
            attrs=["PurchaseOrderEvent ← PurchaseOrderQueue",
                   "InvoiceEvent ← InvoiceTopic (opc-invoice)",
                   "RestockEvent ← RestockTopic (opc-restock)"],
            kind="messaging"))

    cluster(g, "h2", "Relational schema — H2 / JPA (default profile)", h2, "#EAF6EC")
    cluster(g, "mg", "Document model — MongoDB (mongo profile)", mongo, "#EAF6EC")
    cluster(g, "ev", "Outbound event contract carried in outbox.payload (JSON)", events, "#E4F3E7")

    # relational FK + aggregation
    edge(g, "wh_line", "wh_order", "fk", "order_id")
    edge(g, "outbox", "wh_order", "fk", "order_id (soft, nullable)")

    # payload → events
    edge(g, "outbox", "approved", "flow", "OrderApproved")
    edge(g, "outbox", "status", "flow", "OrderStatus")
    edge(g, "approved", "meta", "compose")
    edge(g, "status", "meta", "compose")

    legend(g, [
        (TABLE_KIND["owned"][0], "table / collection OPC owns"),
        (PALETTE["messaging"][0], "event / message contract"),
        (PALETTE["config"][0], "store config (profile-selected)"),
        ("#FFFFFF", "🔑 PK   🔗 FK   ─▶ FK ref   ⇢ payload → event"),
    ])
    render(g, "order-processing-service_schema")
    print("wrote schema diagram")


if __name__ == "__main__":
    build_class_diagram()
    build_schema_diagram()
