#!/usr/bin/env python3
"""
Java Pet Store 1.3.1_02 (LEGACY) — DETAILED high-level architecture.

One diagram, but with real detail (source-verified from PopulateSQL.xml,
sun-j2ee-ri.xml, ejb-jar / web deployment descriptors):
  · the actors and how they enter
  · the 4 deployable EARs WITH their key endpoints / SOAP APIs / MDBs
  · every named JMS destination (queues + the one InvoiceTopic — fan-out)
  · the single shared Cloudscape DB, SEGREGATED by bounded context
    (catalog relational · customer/order/supplier CMP · shared value tables)

The box-by-box responsibility map is petstore_legacy_components.py; the full
column-level ER is petstore_er_diagram.py. This sits between them.

Output: petstore_legacy_highlevel.png / .svg
"""
import os
import graphviz

# ── palette (shared with the other legacy generators: fill, border) ───────────
ACTOR = ("#ECEFF1", "#455A64")
PET   = ("#DCE9F7", "#2E6DB4")   # petstore.ear
OPC   = ("#FDECC8", "#B8860B")   # opc.ear
SUP   = ("#FBE7D0", "#C97C2F")   # supplier.ear
ADM   = ("#F7D9DC", "#B23A48")   # admin.ear
JMSC  = ("#F3D9D4", "#C0392B")   # JMS destinations
REL   = ("#D8F0DD", "#2F8F46")   # relational (catalog)
CMP   = ("#F7D9DC", "#B23A48")   # CMP-generated tables

g = graphviz.Digraph("legacy_highlevel", format="png")
g.attr(rankdir="TB", splines="spline", nodesep="0.7", ranksep="1.3", bgcolor="white",
       fontname="Helvetica", fontsize="16", labelloc="t", compound="true", newrank="true",
       label="Java Pet Store 1.3.1_02 — LEGACY Architecture (detailed)\\l"
             "4 deployable EARs on one J2EE 1.3 app server · coupled ONLY through JMS (7 queues + 1 topic) · "
             "one shared Cloudscape DB, segregated by context\\l"
             "solid black = HTTP / SOAP / EJB call    ·    dashed red = JMS    ·    dotted green = JDBC / CMP persistence\\l")
g.attr("node", fontname="Helvetica", shape="plaintext")


def box(nid, title, lines, colors, title_size=14, body_size=11):
    """A titled component box. Each body line is its OWN padded table row, so the
    text gets real line spacing instead of being crammed together by <BR/>."""
    f, b = colors
    body_rows = "".join(
        f'<TR><TD BGCOLOR="{f}" ALIGN="LEFT" CELLPADDING="4">'
        f'<FONT POINT-SIZE="{body_size}">{ln}</FONT></TD></TR>'
        for ln in lines)
    g.node(nid,
        f'<<TABLE BORDER="1" CELLBORDER="0" CELLSPACING="0" CELLPADDING="9">'
        f'<TR><TD BGCOLOR="{b}" CELLPADDING="9"><FONT COLOR="white" POINT-SIZE="{title_size}"><B>{title}</B></FONT></TD></TR>'
        f'{body_rows}'
        f'</TABLE>>')


def dest(nid, name, kind, colors=JMSC):
    """A single JMS destination node (queue or topic)."""
    f, b = colors
    tag = "TOPIC — pub/sub fan-out" if kind == "topic" else "queue"
    g.node(nid,
        f'<<TABLE BORDER="1" CELLBORDER="0" CELLSPACING="0" CELLPADDING="8">'
        f'<TR><TD BGCOLOR="{b}" CELLPADDING="7"><FONT COLOR="white" POINT-SIZE="11"><B>{name}</B></FONT></TD></TR>'
        f'<TR><TD BGCOLOR="{f}" CELLPADDING="5"><FONT POINT-SIZE="9"><I>{tag}</I></FONT></TD></TR>'
        f'</TABLE>>')


# ══ actors ════════════════════════════════════════════════════════════════
box("shopper", "Shopper — Web Browser",
    ["HTTP → *.do / *.screen", "browse · cart · sign-on · checkout"], ACTOR, 14, 11)
box("admin", "Admin — Swing / Java Web Start",
    ["rich client → AdminRequestProcessor", "approve / deny orders"], ACTOR, 14, 11)

# ══ the 4 EARs (with real endpoints / APIs / MDBs) ══════════════════════════
box("pet", "petstore.ear — Storefront",
    ["<B>Web (WAF / MainServlet):</B>",
     "category · product · item · search.do (read catalog)",
     "signon · createcustomer · customer.do (account)",
     "cart.do (in-session, no DB)",
     "<B>order.do</B> — checkout: build PO, get id (Counter++),",
     "  publish PO as XML → AsyncSenderQueue  (NO order persist)"], PET)

box("opc", "opc.ear — Order Processing Center",
    ["<B>MDBs (workflow owner):</B>",
     "PurchaseOrderMDB — persist PO, Manager=PENDING",
     "OrderApprovalMDB — apply approve/deny transition",
     "InvoiceMDB — supplier invoice → SHIPPED / COMPLETED",
     "Mail{Invoice,OrderApproval,CompletedOrder}MDB",
     "<B>SOAP:</B> OPCService.submitInvoice",
     "<B>EJB:</B> OPCAdminFacade (orders-by-status / detail)"], OPC)

box("sup", "supplier.ear — Fulfilment + Inventory",
    ["<B>SupplierOrderMDB</B> — consume approved PO,",
     "  reserve from InventoryEJB, ship, invoice back",
     "<B>SOAP:</B> SupplierService.submitOrder",
     "<B>Web:</B> RcvrRequestProcessor — 'receiver' restock UI",
     "  (qty_&lt;item&gt; params → InventoryEJB.setQuantity)"], SUP)

box("adm", "admin.ear — Back-Office Console",
    ["Thin GUI — owns NO data",
     "AdminRequestProcessor → OPCAdminFacade",
     "list orders by status · submit approve / deny"], ADM)

# ══ JMS backbone — every named destination ══════════════════════════════════
with g.subgraph(name="cluster_jms") as c:
    c.attr(label="JMS backbone — the ONLY coupling between the 4 EARs  (at-least-once delivery → MDBs must be idempotent)",
           style="rounded,filled", fillcolor="#FCF0EE", color=JMSC[1], fontsize="11", rank="same")
    dest("q_async",   "AsyncSenderQueue", "queue")
    dest("q_po",      "jms/PurchaseOrderQueue", "queue")
    dest("q_appr",    "jms/OrderApprovalQueue", "queue")
    dest("t_invoice", "jms/InvoiceTopic", "topic")
    dest("q_mail",    "jms/MailQueue", "queue")
    dest("q_mailappr","jms/OrderApprovalMailQueue", "queue")
    dest("q_mailcomp","jms/CompletedOrderMailQueue", "queue")
    # keep the destinations in a single tidy row, left→right in workflow order
    c.edge("q_async", "q_po", style="invis")
    c.edge("q_po", "q_appr", style="invis")
    c.edge("q_appr", "t_invoice", style="invis")
    c.edge("t_invoice", "q_mail", style="invis")
    c.edge("q_mail", "q_mailappr", style="invis")
    c.edge("q_mailappr", "q_mailcomp", style="invis")

# ══ the single shared Cloudscape DB — one box PER EAR, showing the data each owns ═
#    (source-verified from each EAR's application.xml module list + each component's
#     sun-j2ee-ri.xml CMP table mappings; catalog is hand-written relational, the rest
#     are CMP container-generated. The value tables marked ⚠ are DUPLICATED across EARs.)
with g.subgraph(name="cluster_db") as c:
    c.attr(label="Cloudscape DB — one physical schema, but each EAR's CMP maps to its OWN tables (22 total). "
                 "Green = hand-written relational · Red = CMP-generated · ⚠ = value table DUPLICATED across EARs (no single owner)",
           style="rounded,filled", fillcolor="#F4FBF6", color=REL[1], fontsize="11", rank="same")
    box("db_pet", "petstore.ear data",
        ["<B>Identity/customer (CMP):</B> UserEJBTable ·",
         "  CustomerEJBTable · AccountEJBTable · ProfileEJBTable",
         "<B>uidgen:</B> CounterEJBTable (order-id sequence)",
         "<B>Catalog (relational, locale-split):</B> category(_details)",
         "  · product(_details) · item(_details)",
         "⚠ ContactInfo · Address · CreditCard EJBTable",
         "<I>(cart = in-memory session bean — NO table)</I>"], REL, 12, 10)
    box("db_opc", "opc.ear data",
        ["<B>Order (CMP):</B> PurchaseOrderEJBTable",
         "  · LineItemEJBTable · PO↔LineItem join table",
         "<B>Workflow spine:</B> ManagerEJBTable (order status)",
         "⚠ ContactInfo · Address · CreditCard EJBTable"], CMP, 12, 10)
    box("db_sup", "supplier.ear data",
        ["<B>Inventory (CMP):</B> InventoryEJBTable (itemId, quantity)",
         "<B>Fulfilment:</B> SupplierOrderEJBTable + join",
         "⚠ ContactInfo · Address · LineItem EJBTable"], CMP, 12, 10)
    box("db_adm", "admin.ear data",
        ["<B>NONE.</B> Owns no tables.",
         "Reads orders indirectly via OPCAdminFacade (EJB).",
         "A thin GUI over opc.ear's data."], ADM, 12, 10)
    # keep the per-EAR DB boxes in one tidy row (same left→right order as the EARs above)
    c.edge("db_pet", "db_opc", style="invis")
    c.edge("db_opc", "db_sup", style="invis")
    c.edge("db_sup", "db_adm", style="invis")

# ── edges ─────────────────────────────────────────────────────────────────
def call(a, b, lbl="", **kw):
    g.edge(a, b, label=lbl, color="#333333", fontsize="8.5", fontcolor="#333333",
           penwidth="1.3", **kw)

def jms(a, b, lbl="", **kw):
    g.edge(a, b, label=lbl, color="#C0392B", style="dashed", fontsize="8",
           fontcolor="#C0392B", penwidth="1.5", **kw)

def persist(a, b):
    g.edge(a, b, color="#2F8F46", style="dotted", arrowsize="0.6", penwidth="1.1")

# entry
call("shopper", "pet", "HTTP")
call("admin", "adm", "Web Start")
call("adm", "opc", "OPCAdminFacade (EJB)")

# the async order workflow across the JMS backbone
jms("pet", "q_async", "PO as XML")
jms("q_async", "q_po")
jms("q_po", "opc", "PurchaseOrderMDB")
jms("opc", "q_appr", "route to approval")
jms("q_appr", "opc", "OrderApprovalMDB")
jms("opc", "sup", "approved PO")          # opc → supplier (approved order to fulfil)
jms("sup", "t_invoice", "submitInvoice")
jms("t_invoice", "opc", "InvoiceMDB (fan-out)")
jms("opc", "q_mail", "MailInvoiceMDB")
jms("opc", "q_mailappr", "MailOrderApprovalMDB")
jms("opc", "q_mailcomp", "MailCompletedOrderMDB")

# persistence — each EAR maps to ITS OWN tables (JDBC / CMP)
persist("pet", "db_pet")
persist("opc", "db_opc")
persist("sup", "db_sup")
# admin.ear owns no data — it has no persist edge (reads via OPCAdminFacade, drawn above)

# ── keep tiers in tidy rows ─────────────────────────────────────────────────
with g.subgraph() as row:
    row.attr(rank="same"); row.node("shopper"); row.node("admin")
with g.subgraph() as row:
    row.attr(rank="same"); row.node("pet"); row.node("opc"); row.node("sup"); row.node("adm")

# ── invisible ordering edges: force the 4 tiers into stacked bands ──────────
#    actors → EARs → JMS backbone → DB (top to bottom)
g.edge("shopper", "pet", style="invis")
g.edge("opc", "q_po", style="invis")       # EAR band sits above the JMS band
g.edge("q_po", "db_opc", style="invis")    # JMS band sits above the DB band

for fmt in ("png", "svg"):
    g.format = fmt
    g.render(os.path.join(os.path.dirname(os.path.abspath(__file__)), "petstore_legacy_highlevel"),
             cleanup=True)
print("Wrote petstore_legacy_highlevel.png / .svg")
