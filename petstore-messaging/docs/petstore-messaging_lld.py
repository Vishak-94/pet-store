#!/usr/bin/env python3
"""LLD diagram generator for petstore-messaging — the shared JMS contract LIBRARY.

Emits two diagrams into this docs/ folder:
  * petstore-messaging_class.png/.svg  — UML class diagram (contract, config, events,
    the transport-only publisher, and the reuse seam across the fleet).
  * petstore-messaging_schema.png/.svg — message-schema / data-model diagram (this
    module has NO database): the EventMeta envelope, every event record + nested
    records, and the destination registry with its queue/topic kind + _type routing.

Every class, field, constant, destination and event below is extracted verbatim from
the real source under src/com/petstore/messaging (house rule: never invent).
"""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND


# ─────────────────────────────────────────────────────────────────────────────
# (a) CLASS DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_class_diagram():
    g = new_graph("petstore-messaging — shared JMS contract library (class design)", rankdir="TB")

    # ── Config / wiring layer ────────────────────────────────────────────────
    def _config(s):
        s.node("MessagingConfig", uml_class(
            "MessagingConfig", "@Configuration @EnableJms",
            attrs=[
                'TYPE_ID_PROPERTY = "_type" : String',
                "TYPE_IDS : Map<String,Class<?>>  (source of truth)",
            ],
            methods=[
                "jacksonJmsMessageConverter() : MessageConverter",
                "queueFactory(cf, conv) : ...ListenerContainerFactory  (pubSub=false)",
                "topicFactory(cf, conv) : ...ListenerContainerFactory  (durable+shared)",
            ],
            kind="config",
            note="ONE JMS config for the whole fleet — replaces per-service JmsConfig copies"))
        s.node("MessagePublisher", uml_class(
            "MessagePublisher", "@Component",
            attrs=[
                "TYPE_BY_CLASS : Map<Class<?>,String>  (inverted TYPE_IDS)",
                "queueTemplate : JmsTemplate  (pubSub=false)",
                "topicTemplate : JmsTemplate  (pubSub=true)",
            ],
            methods=[
                "publish(Destination dest, Object event) : void",
                "typeOf(Object event) : String",
            ],
            kind="messaging",
            note="Transport-only: routes by Destination.topic(), stamps _type. No tx logic."))

    # ── Contract: envelope + correlation + destination registry ──────────────
    def _contract(s):
        s.node("EventMeta", uml_class(
            "EventMeta", "record",
            attrs=["eventId : String", "type : String",
                   "occurredAt : String", "correlationId : String"],
            kind="domain",
            note="Envelope mixin embedded in every event (no generic wrapper)"))
        s.node("Events", uml_class(
            "Events", "final utility",
            methods=["meta(String type, String correlationId)$ : EventMeta",
                     "meta(String type)$ : EventMeta  (pulls Correlation.current())"],
            kind="domain"))
        s.node("Correlation", uml_class(
            "Correlation", "final utility",
            attrs=['MDC_KEY = "correlationId" : String'],
            methods=["current()$ : String", "set(String)$ : void", "clear()$ : void"],
            kind="domain",
            note="HTTP<->JMS trace bridge via SLF4J MDC (framework-free)"))
        s.node("Destination", uml_class(
            "Destination", "record",
            attrs=["name : String", "topic : boolean"],
            methods=["queue(String name)$ : Destination",
                     "topic(String name)$ : Destination"],
            kind="domain",
            note="Kind is DATA not naming — publisher/factories read topic() to route"))
        s.node("Destinations", uml_class(
            "Destinations", "final registry",
            attrs=[
                "PURCHASE_ORDER = queue(PurchaseOrderQueue)",
                "APPROVED_ORDER = queue(ApprovedOrderQueue)",
                "INVOICE = topic(InvoiceTopic)",
                "ORDER_STATUS = topic(OrderStatusTopic)",
                "RESTOCK = topic(RestockTopic)",
                "+ *_NAME String constants (for @JmsListener)",
            ],
            kind="domain",
            note="The ONE place destination names live — no scattered literals"))

    # ── Event records (events/ package) ──────────────────────────────────────
    def _events(s):
        s.node("PurchaseOrderEvent", uml_class(
            "PurchaseOrderEvent", "record",
            attrs=['TYPE = "PurchaseOrder"$', "meta : EventMeta", "orderId : String",
                   "userId : String", "emailId : String", "locale : String",
                   "totalPrice : double", "lines : List<Line>",
                   "shipTo : ContactInfo  (nullable)", "billTo : ContactInfo  (nullable)",
                   "currency : String  (nullable, additive)"],
            kind="entity"))
        s.node("PO_Line", uml_class(
            "PurchaseOrderEvent.Line", "record",
            attrs=["itemId", "productId", "categoryId", "quantity : int", "unitPrice : double"],
            kind="entity"))
        s.node("PO_ContactInfo", uml_class(
            "PurchaseOrderEvent.ContactInfo", "record",
            attrs=["familyName", "givenName", "streetName1", "streetName2 (nullable)",
                   "city", "state", "zipCode", "country", "telephone", "email"],
            kind="entity"))
        s.node("OrderApprovedEvent", uml_class(
            "OrderApprovedEvent", "record",
            attrs=['TYPE = "OrderApproved"$', "meta : EventMeta", "orderId", "userId",
                   "emailId", "locale", "lines : List<Line>"],
            kind="entity"))
        s.node("OA_Line", uml_class(
            "OrderApprovedEvent.Line", "record",
            attrs=["itemId", "productId", "categoryId", "quantity : int", "unitPrice : double"],
            kind="entity"))
        s.node("InvoiceEvent", uml_class(
            "InvoiceEvent", "record",
            attrs=['TYPE = "Invoice"$', "meta : EventMeta", "orderId", "userId",
                   "emailId", "shipped : boolean", "totalPrice : double"],
            kind="entity"))
        s.node("OrderStatusEvent", uml_class(
            "OrderStatusEvent", "record",
            attrs=['TYPE = "OrderStatus"$', "meta : EventMeta", "orderId", "userId",
                   "emailId", "status : String", "totalPrice : double"],
            kind="entity"))
        s.node("RestockEvent", uml_class(
            "RestockEvent", "record",
            attrs=['TYPE = "Restock"$', "meta : EventMeta", "itemId : String",
                   "quantityAdded : int"],
            kind="entity"))

    # ── Reuse seam: the fleet importing this library ─────────────────────────
    def _consumers(s):
        s.node("Producers", uml_class(
            "Producing services", "reuse (import 1.0.0)",
            attrs=["petstore-app-v1 (checkout)", "order-processing-service",
                   "inventory-service"],
            kind="external",
            note="call MessagePublisher.publish(...) from their after-commit gateways"))
        s.node("Consumers", uml_class(
            "Consuming services", "reuse (import 1.0.0)",
            attrs=["order-processing-service", "inventory-service",
                   "notification-service"],
            kind="external",
            note="@JmsListener(containerFactory = queueFactory|topicFactory)"))

    # ── Spring/JMS framework the config sits on ──────────────────────────────
    def _framework(s):
        s.node("Spring", uml_class(
            "Spring JMS / Jackson", "framework",
            attrs=["JmsTemplate", "MappingJackson2MessageConverter",
                   "DefaultJmsListenerContainerFactory", "ConnectionFactory (autoconfigured)"],
            kind="framework",
            note="Artemis broker :61616 provided by each service, not this lib"))

    cluster(g, "cfg", "Config & transport  (com.petstore.messaging)", _config, "#EDEDED", "#666666")
    cluster(g, "con", "Contract: envelope, correlation, destination registry", _contract, "#EFE9F8", "#6B4FA0")
    cluster(g, "evt", "Event records  (com.petstore.messaging.events)", _events, "#FBEEF0", "#B23A48")
    cluster(g, "reuse", "Fleet reuse seam  (every service imports petstore-messaging:1.0.0)", _consumers, "#F5F5F5", "#AAAAAA")
    cluster(g, "fw", "Underlying framework", _framework, "#F0F0F0", "#888888")

    # ── Relationships ────────────────────────────────────────────────────────
    # Config internals
    edge(g, "MessagePublisher", "MessagingConfig", "depends", "TYPE_IDS / TYPE_ID_PROPERTY")
    edge(g, "MessagePublisher", "Destination", "depends", "routes by topic()")
    edge(g, "MessagingConfig", "Spring", "depends", "builds converter + factories")
    edge(g, "MessagePublisher", "Spring", "depends", "JmsTemplate")

    # Contract composition / usage
    edge(g, "Destinations", "Destination", "compose", "5 constants")
    edge(g, "Events", "EventMeta", "flow", "builds")
    edge(g, "Events", "Correlation", "depends", "current()")

    # Events embed the envelope + nested records
    for e in ["PurchaseOrderEvent", "OrderApprovedEvent", "InvoiceEvent",
              "OrderStatusEvent", "RestockEvent"]:
        edge(g, e, "EventMeta", "compose")
    edge(g, "PurchaseOrderEvent", "PO_Line", "compose")
    edge(g, "PurchaseOrderEvent", "PO_ContactInfo", "compose", "shipTo/billTo")
    edge(g, "OrderApprovedEvent", "OA_Line", "compose")

    # Config knows every event via TYPE_IDS
    edge(g, "MessagingConfig", "PurchaseOrderEvent", "depends", "TYPE_IDS")
    edge(g, "MessagingConfig", "RestockEvent", "depends", "TYPE_IDS")

    # Reuse seam
    edge(g, "Producers", "MessagePublisher", "flow", "publish(dest, event)")
    edge(g, "Consumers", "MessagingConfig", "flow", "import config")
    edge(g, "Consumers", "PurchaseOrderEvent", "flow", "consume records")

    legend(g, [
        (PALETTE["config"][0],    "Config / transport"),
        (PALETTE["messaging"][0], "Publisher (JMS)"),
        (PALETTE["domain"][0],    "Contract (envelope/registry)"),
        (PALETTE["entity"][0],    "Event record"),
        (PALETTE["external"][0],  "Reusing service (import)"),
        (PALETTE["framework"][0], "Spring/JMS framework"),
        ("#3E9B54",               "async/flow (JMS)"),
    ])

    render(g, "petstore-messaging_class")


# ─────────────────────────────────────────────────────────────────────────────
# (b) MESSAGE-SCHEMA / DATA-MODEL DIAGRAM  (no DB — wire contract instead)
# ─────────────────────────────────────────────────────────────────────────────
def build_schema_diagram():
    g = new_graph("petstore-messaging — message schema (envelope + events + destination map)", rankdir="LR")

    # Envelope embedded in EVERY event
    def _envelope(s):
        s.node("EventMeta", table_node("EventMeta  (envelope)", [
            ("eventId", "String  (unique — dedup)", "pk"),
            ("type", "String  (= _type id)", ""),
            ("occurredAt", "String  (ISO-8601)", ""),
            ("correlationId", "String  (trace id, nullable)", ""),
        ], kind="owned"))

    # The five event records (payload = envelope + domain fields)
    def _events(s):
        s.node("PurchaseOrderEvent", table_node("PurchaseOrderEvent  (_type=PurchaseOrder)", [
            ("meta", "EventMeta", "fk"),
            ("orderId", "String", ""),
            ("userId", "String", ""),
            ("emailId", "String", ""),
            ("locale", "String", ""),
            ("totalPrice", "double", ""),
            ("lines", "List<Line>", ""),
            ("shipTo", "ContactInfo  (nullable)", ""),
            ("billTo", "ContactInfo  (nullable)", ""),
            ("currency", "String  (nullable, additive)", ""),
        ], kind="owned"))
        s.node("PO_Line", table_node("PurchaseOrderEvent.Line", [
            ("itemId", "String", ""),
            ("productId", "String", ""),
            ("categoryId", "String", ""),
            ("quantity", "int", ""),
            ("unitPrice", "double", ""),
        ], kind="owned"))
        s.node("PO_ContactInfo", table_node("PurchaseOrderEvent.ContactInfo", [
            ("familyName", "String", ""),
            ("givenName", "String", ""),
            ("streetName1", "String", ""),
            ("streetName2", "String  (nullable)", ""),
            ("city", "String", ""),
            ("state", "String", ""),
            ("zipCode", "String", ""),
            ("country", "String", ""),
            ("telephone", "String", ""),
            ("email", "String", ""),
        ], kind="owned"))
        s.node("OrderApprovedEvent", table_node("OrderApprovedEvent  (_type=OrderApproved)", [
            ("meta", "EventMeta", "fk"),
            ("orderId", "String", ""),
            ("userId", "String", ""),
            ("emailId", "String", ""),
            ("locale", "String", ""),
            ("lines", "List<Line>", ""),
        ], kind="owned"))
        s.node("OA_Line", table_node("OrderApprovedEvent.Line", [
            ("itemId", "String", ""),
            ("productId", "String", ""),
            ("categoryId", "String", ""),
            ("quantity", "int", ""),
            ("unitPrice", "double", ""),
        ], kind="owned"))
        s.node("InvoiceEvent", table_node("InvoiceEvent  (_type=Invoice)", [
            ("meta", "EventMeta", "fk"),
            ("orderId", "String", ""),
            ("userId", "String", ""),
            ("emailId", "String", ""),
            ("shipped", "boolean  (false = backorder)", ""),
            ("totalPrice", "double", ""),
        ], kind="owned"))
        s.node("OrderStatusEvent", table_node("OrderStatusEvent  (_type=OrderStatus)", [
            ("meta", "EventMeta", "fk"),
            ("orderId", "String", ""),
            ("userId", "String", ""),
            ("emailId", "String", ""),
            ("status", "String  (APPROVED/DENIED/COMPLETED)", ""),
            ("totalPrice", "double", ""),
        ], kind="owned"))
        s.node("RestockEvent", table_node("RestockEvent  (_type=Restock)", [
            ("meta", "EventMeta", "fk"),
            ("itemId", "String", ""),
            ("quantityAdded", "int", ""),
        ], kind="owned"))

    # Destination registry: name -> kind -> event -> producer/consumer routing
    def _destmap(s):
        s.node("DestMap", table_node("Destinations registry  (name -> kind -> _type)", [
            ("PurchaseOrderQueue", "queue -> PurchaseOrder  (checkout->OPC)", "pk"),
            ("ApprovedOrderQueue", "queue -> OrderApproved  (OPC->inventory)", "pk"),
            ("InvoiceTopic", "topic -> Invoice  (inventory->OPC+notif)", "pk"),
            ("OrderStatusTopic", "topic -> OrderStatus  (OPC->notification)", "pk"),
            ("RestockTopic", "topic -> Restock  (inventory->OPC re-drive)", "pk"),
        ], kind="owned"))

    cluster(g, "env", "Envelope (embedded in every event)", _envelope, "#EFE9F8", "#6B4FA0")
    cluster(g, "evt", "Event records — JSON wire payloads", _events, "#FBEEF0", "#B23A48")
    cluster(g, "dst", "Destination map — where each _type flows", _destmap, "#E7F4EF", "#2E8B74")

    # Each event embeds the envelope (fk-style reference)
    for e in ["PurchaseOrderEvent", "OrderApprovedEvent", "InvoiceEvent",
              "OrderStatusEvent", "RestockEvent"]:
        edge(g, e, "EventMeta", "fk", "meta")
    edge(g, "PurchaseOrderEvent", "PO_Line", "fk", "lines[]")
    edge(g, "PurchaseOrderEvent", "PO_ContactInfo", "fk", "shipTo/billTo")
    edge(g, "OrderApprovedEvent", "OA_Line", "fk", "lines[]")

    # Destination map keys each event by its _type id
    edge(g, "DestMap", "PurchaseOrderEvent", "flow", "PurchaseOrder")
    edge(g, "DestMap", "OrderApprovedEvent", "flow", "OrderApproved")
    edge(g, "DestMap", "InvoiceEvent", "flow", "Invoice")
    edge(g, "DestMap", "OrderStatusEvent", "flow", "OrderStatus")
    edge(g, "DestMap", "RestockEvent", "flow", "Restock")

    legend(g, [
        (TABLE_KIND["owned"][0], "Owned by petstore-messaging (contract)"),
        ("#6B4FA0", "Envelope shared by all events"),
        ("#2F8F46", "meta / nested-record reference"),
    ])

    render(g, "petstore-messaging_schema")


if __name__ == "__main__":
    build_class_diagram()
    build_schema_diagram()
    print("wrote petstore-messaging_class.{png,svg} and petstore-messaging_schema.{png,svg}")
