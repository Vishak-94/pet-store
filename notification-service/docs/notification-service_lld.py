#!/usr/bin/env python3
"""Low-Level-Design diagram generator for notification-service.

Emits two diagrams into this docs/ folder, using the shared house-style library:
  * notification-service_class.png/.svg  — UML class diagram (layers, port/adapter seam,
    shared-lib reuse, extensibility points).
  * notification-service_schema.png/.svg — message-schema / data-model diagram (this
    module has NO database): the consumed event envelope + records, the Email DTO, and the
    listener -> composer -> mailer flow, plus the DLQ/ExpiryQueue raw-message sink.

Everything below is extracted from the real source under
notification-service/src/com/petstore/notification and the shared petstore-messaging
contract — no invented classes, fields, methods, destinations, or events.
"""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import (  # noqa: E402
    new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND,
)


# ─────────────────────────────────────────────────────────────────────────────
# (a) CLASS DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_class_diagram():
    g = new_graph("notification-service — Class Diagram (JMS observer + mail port/adapter)", rankdir="TB")

    # ── Shared contract library (reused across the whole fleet) ──────────────
    def _shared(s):
        s.node("InvoiceEvent", uml_class(
            "InvoiceEvent", "record · petstore-messaging",
            attrs=["EventMeta meta", "String orderId", "String userId",
                   "String emailId", "boolean shipped", "double totalPrice"],
            methods=['TYPE = "Invoice"'],
            kind="external", note="Consumed off InvoiceTopic (own copy)"))
        s.node("OrderStatusEvent", uml_class(
            "OrderStatusEvent", "record · petstore-messaging",
            attrs=["EventMeta meta", "String orderId", "String userId",
                   "String emailId", "String status", "double totalPrice"],
            methods=['TYPE = "OrderStatus"'],
            kind="external", note="status = APPROVED / DENIED / COMPLETED"))
        s.node("Destinations", uml_class(
            "Destinations", "constants · petstore-messaging",
            methods=['INVOICE_NAME = "InvoiceTopic"',
                     'ORDER_STATUS_NAME = "OrderStatusTopic"'],
            kind="external"))
        s.node("MessagingConfig", uml_class(
            "MessagingConfig", "@Configuration · petstore-messaging",
            methods=["topicFactory()  (pub/sub, durable+shared)",
                     "queueFactory()  (point-to-point)",
                     'TYPE_ID_PROPERTY = "_type"'],
            kind="external", note="Shared JMS wiring — no per-service JmsConfig"))

    # ── Inbound JMS adapters (the only entry points; no controller) ──────────
    def _messaging(s):
        s.node("InvoiceNotificationListener", uml_class(
            "InvoiceNotificationListener", "@Component · @JmsListener",
            attrs=["OrderMailComposer composer", "MailSender mailSender"],
            methods=['onInvoice(InvoiceEvent)  [InvoiceTopic, topicFactory,',
                     '   subscription="notification-invoice"]'],
            kind="messaging"))
        s.node("OrderStatusNotificationListener", uml_class(
            "OrderStatusNotificationListener", "@Component · @JmsListener",
            attrs=["OrderMailComposer composer", "MailSender mailSender"],
            methods=['onStatus(OrderStatusEvent)  [OrderStatusTopic, topicFactory,',
                     '   subscription="notification-order-status"]'],
            kind="messaging"))
        s.node("DlqListener", uml_class(
            "DlqListener", "@Component · @JmsListener",
            methods=["onDeadLetter(Message)   [DLQ, queueFactory]",
                     "onExpired(Message)      [ExpiryQueue, queueFactory]"],
            kind="messaging", note="raw Message; never deserialize, never throw"))

    # ── Service layer: composition (no transport) ────────────────────────────
    def _service(s):
        s.node("OrderMailComposer", uml_class(
            "OrderMailComposer", "@Component",
            attrs=['SHIPPED_SUBJECT', 'BACKORDER_SUBJECT',
                   'STATUS_SUBJECT', 'COMPLETED_SUBJECT'],
            methods=["fromInvoice(InvoiceEvent) : Email",
                     "fromStatus(OrderStatusEvent) : Email",
                     "recipient(emailId, userId) : String"],
            kind="service", note="pure — legacy subject parity strings"))

    # ── Domain value object ──────────────────────────────────────────────────
    def _domain(s):
        s.node("Email", uml_class(
            "Email", "record",
            attrs=["String to", "String subject", "String body"],
            kind="domain", note="legacy mailer.ejb.Mail value object"))

    # ── Outbound port + adapter seam ─────────────────────────────────────────
    def _ports(s):
        s.node("MailSender", uml_class(
            "MailSender", "interface (port)",
            methods=["send(Email) : void"],
            kind="port", note="swap transport by config only"))
        s.node("LoggingMailSender", uml_class(
            "LoggingMailSender", "@Component (default adapter)",
            methods=["send(Email) : void  — logs boxed email"],
            kind="adapter",
            note='@ConditionalOnMissingBean(name="smtpMailSender")'))

    # ── Config / bootstrap ───────────────────────────────────────────────────
    def _config(s):
        s.node("NotificationServiceApplication", uml_class(
            "NotificationServiceApplication", "@SpringBootApplication",
            methods=["main(String[])"],
            kind="config",
            note="scans com.petstore.notification + com.petstore.messaging"))

    cluster(g, "shared", "Shared contract library — petstore-messaging (reused fleet-wide)",
            _shared, PALETTE["external"][0], PALETTE["external"][1])
    cluster(g, "msg", "Inbound JMS adapters (only entry points)",
            _messaging, PALETTE["messaging"][0], PALETTE["messaging"][1])
    cluster(g, "svc", "Service — composition (transport-free, unit-testable)",
            _service, PALETTE["service"][0], PALETTE["service"][1])
    cluster(g, "dom", "Domain", _domain, PALETTE["domain"][0], PALETTE["domain"][1])
    cluster(g, "port", "Outbound port & adapter (swap seam)",
            _ports, PALETTE["port"][0], PALETTE["port"][1])
    cluster(g, "cfg", "Config / bootstrap", _config, PALETTE["config"][0], PALETTE["config"][1])

    # Relationships ----------------------------------------------------------
    # Adapter realizes the port (the reuse/extensibility seam)
    edge(g, "LoggingMailSender", "MailSender", "impl", "implements")

    # Listeners depend on composer + port
    edge(g, "InvoiceNotificationListener", "OrderMailComposer", "depends", "fromInvoice")
    edge(g, "OrderStatusNotificationListener", "OrderMailComposer", "depends", "fromStatus")
    edge(g, "InvoiceNotificationListener", "MailSender", "depends", "send")
    edge(g, "OrderStatusNotificationListener", "MailSender", "depends", "send")

    # Listeners consume shared events (JMS async)
    edge(g, "InvoiceEvent", "InvoiceNotificationListener", "async", "InvoiceTopic")
    edge(g, "OrderStatusEvent", "OrderStatusNotificationListener", "async", "OrderStatusTopic")

    # Listeners reference shared destination names + container factories
    edge(g, "InvoiceNotificationListener", "Destinations", "depends", "INVOICE_NAME")
    edge(g, "OrderStatusNotificationListener", "Destinations", "depends", "ORDER_STATUS_NAME")
    edge(g, "InvoiceNotificationListener", "MessagingConfig", "depends", "topicFactory")
    edge(g, "DlqListener", "MessagingConfig", "depends", "queueFactory")

    # Composer builds Email; port sends Email
    edge(g, "OrderMailComposer", "Email", "compose", "builds")
    edge(g, "MailSender", "Email", "depends", "sends")

    legend(g, [
        (PALETTE["external"][0], "Shared library (petstore-messaging) — reused"),
        (PALETTE["messaging"][0], "Inbound JMS adapter (@JmsListener)"),
        (PALETTE["service"][0], "Service (composition)"),
        (PALETTE["domain"][0], "Domain value object"),
        (PALETTE["port"][0], "Port (interface / SPI seam)"),
        (PALETTE["adapter"][0], "Adapter (default: logging)"),
        (PALETTE["config"][0], "Config / bootstrap"),
        ("#3E9B54", "async = JMS delivery   —   dashed empty = implements"),
    ])

    render(g, "notification-service_class")


# ─────────────────────────────────────────────────────────────────────────────
# (b) SCHEMA / MESSAGE-MODEL DIAGRAM  (no DB — consumed message contract + flow)
# ─────────────────────────────────────────────────────────────────────────────
def build_schema_diagram():
    g = new_graph("notification-service — Consumed Message Schema & Notification Flow (no DB)",
                  rankdir="LR")

    # ── Event envelope + records (the wire contract this service reads) ──────
    def _envelope(s):
        s.node("EventMeta", table_node("EventMeta  «envelope»", [
            ("eventId", "String  (dedup key)", "pk"),
            ("type", "String  (=_type id)", ""),
            ("occurredAt", "String  (ISO-8601)", ""),
            ("correlationId", "String  (trace)", ""),
        ], kind="external"))

    def _events(s):
        s.node("InvoiceEvent", table_node("InvoiceEvent  (InvoiceTopic)", [
            ("meta", "EventMeta", "fk"),
            ("orderId", "String", ""),
            ("userId", "String", ""),
            ("emailId", "String", ""),
            ("shipped", "boolean", ""),
            ("totalPrice", "double", ""),
        ], kind="external"))
        s.node("OrderStatusEvent", table_node("OrderStatusEvent  (OrderStatusTopic)", [
            ("meta", "EventMeta", "fk"),
            ("orderId", "String", ""),
            ("userId", "String", ""),
            ("emailId", "String", ""),
            ("status", "String  APPROVED/DENIED/COMPLETED", ""),
            ("totalPrice", "double", ""),
        ], kind="external"))

    # ── Quarantine sinks (raw JMS messages, introspected via headers) ────────
    def _dlq(s):
        s.node("DlqMessage", table_node("DLQ / ExpiryQueue  «raw jakarta.jms.Message»", [
            ("_AMQ_ORIG_ADDRESS", "String header (origin)", ""),
            ("_type", "String header (event type)", ""),
            ("JMSXDeliveryCount", "int header", ""),
            ("JMSMessageID", "String header", ""),
            ("body", "text (never deserialized)", ""),
        ], kind="external"))

    # ── Produced value object (in-memory only, no persistence) ───────────────
    def _email(s):
        s.node("Email", table_node("Email  «record — produced, in-memory»", [
            ("to", "String  (emailId | <userId>@petstore.invalid)", ""),
            ("subject", "String  (legacy parity prefix + orderId)", ""),
            ("body", "String  (composed text)", ""),
        ], kind="owned"))

    # ── The processing pipeline (nodes as classes for the flow story) ────────
    def _flow(s):
        s.node("InvoiceNotificationListener", uml_class(
            "InvoiceNotificationListener", "@JmsListener",
            methods=["onInvoice(InvoiceEvent)"], kind="messaging"))
        s.node("OrderStatusNotificationListener", uml_class(
            "OrderStatusNotificationListener", "@JmsListener",
            methods=["onStatus(OrderStatusEvent)"], kind="messaging"))
        s.node("DlqListener", uml_class(
            "DlqListener", "@JmsListener",
            methods=["onDeadLetter / onExpired", "log.error(...)"], kind="messaging"))
        s.node("OrderMailComposer", uml_class(
            "OrderMailComposer", "@Component",
            methods=["fromInvoice / fromStatus"], kind="service"))
        s.node("MailSender", uml_class(
            "MailSender", "port → LoggingMailSender",
            methods=["send(Email)"], kind="port"))

    cluster(g, "env", "Event envelope (petstore-messaging)", _envelope,
            TABLE_KIND["external"][0], TABLE_KIND["external"][1])
    cluster(g, "evt", "Consumed topic events (own copy, pub/sub)", _events,
            TABLE_KIND["external"][0], TABLE_KIND["external"][1])
    cluster(g, "flow", "Listener → composer → mailer", _flow,
            PALETTE["messaging"][0], PALETTE["messaging"][1])
    cluster(g, "out", "Produced value object", _email,
            TABLE_KIND["owned"][0], TABLE_KIND["owned"][1])
    cluster(g, "dlq", "Broker safety net (queues)", _dlq,
            TABLE_KIND["external"][0], TABLE_KIND["external"][1])

    # envelope embedded in each event
    edge(g, "InvoiceEvent", "EventMeta", "fk", "embeds")
    edge(g, "OrderStatusEvent", "EventMeta", "fk", "embeds")

    # event -> listener (JMS delivery)
    edge(g, "InvoiceEvent", "InvoiceNotificationListener", "async", "deliver")
    edge(g, "OrderStatusEvent", "OrderStatusNotificationListener", "async", "deliver")
    edge(g, "DlqMessage", "DlqListener", "async", "deliver")

    # listener -> composer -> Email -> mailer
    edge(g, "InvoiceNotificationListener", "OrderMailComposer", "flow")
    edge(g, "OrderStatusNotificationListener", "OrderMailComposer", "flow")
    edge(g, "OrderMailComposer", "Email", "flow", "builds")
    edge(g, "Email", "MailSender", "flow", "send")

    legend(g, [
        (TABLE_KIND["external"][0], "Contract read from petstore-messaging / broker"),
        (TABLE_KIND["owned"][0], "Produced in-memory (no persistence)"),
        (PALETTE["messaging"][0], "JMS listener"),
        ("#3E9B54", "async = JMS delivery"),
        ("#2F8F46", "fk = envelope embedded in event"),
    ])

    render(g, "notification-service_schema")


if __name__ == "__main__":
    build_class_diagram()
    build_schema_diagram()
    print("wrote notification-service_class.{png,svg} and notification-service_schema.{png,svg}")
