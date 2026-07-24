#!/usr/bin/env python3
"""
Java Pet Store 1.3.1_02 (LEGACY) — annotated component & responsibility map.
Every box says WHAT the component is and its ROLE. Grouped by tier:
WAF (web controller) · 4 deployable EARs · 19 reusable components · infra/tools.
Output: petstore_legacy_components.png / .svg
"""
import os
import graphviz

WAF   = ("#EDE7DE", "#6B5B3E")   # web app framework
PET   = ("#DCE9F7", "#2E6DB4")   # petstore.ear (storefront)
OPC   = ("#FDECC8", "#B8860B")   # opc.ear (order processing)
SUP   = ("#FBE7D0", "#C97C2F")   # supplier.ear
ADM   = ("#F7D9DC", "#B23A48")   # admin.ear
COMP  = ("#E9E1F5", "#6B4FA0")   # reusable component
INFRA = ("#D8F0DD", "#2F8F46")   # infra / tools

g = graphviz.Digraph("legacy", format="png")
g.attr(rankdir="TB", splines="spline", nodesep="0.3", ranksep="0.8", bgcolor="white",
       fontname="Helvetica", fontsize="16", labelloc="t", compound="true",
       newrank="true",
       label="Java Pet Store 1.3.1_02 — LEGACY Components & Their Roles\\l"
             "J2EE 1.3 BluePrints · 4 deployable EARs · WAF web controller · 19 reusable components · "
             "EJB 2.x CMP · JMS · Cloudscape\\l")
g.attr("node", fontname="Helvetica", shape="plaintext")


def box(nid, title, role, colors):
    f, b = colors
    g.node(nid,
        f'<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="5">'
        f'<TR><TD BGCOLOR="{b}"><FONT COLOR="white" POINT-SIZE="11"><B>{title}</B></FONT></TD></TR>'
        f'<TR><TD BGCOLOR="{f}" ALIGN="LEFT"><FONT POINT-SIZE="8.5">{role}</FONT></TD></TR>'
        f'</TABLE>>')


# ══ WAF — Web Application Framework (shared web controller) ══
with g.subgraph(name="cluster_waf") as c:
    c.attr(label="WAF — Web Application Framework (MVC front controller, shared by all web apps)",
           style="rounded,filled", fillcolor="#F7F4EF", color=WAF[1], fontsize="12")
    box("mainservlet", "MainServlet", "Front controller. Single entry for every *.do /\\l*.screen request; dispatches to the flow.", WAF)
    box("reqproc", "RequestProcessor", "Turns an HTTP request into a WAF Event and\\linvokes the matching HTMLAction.", WAF)
    box("webctl", "WebController → EJBControllerLocalEJB", "Session façade: routes Events to server-side\\lEJBActions (business logic).", WAF)
    box("screenflow", "ScreenFlowManager", "Decides the next screen after an action\\l(screen-flow / navigation rules).", WAF)
    box("statemachine", "StateMachine", "Holds per-session state; maps Events → EJBActions.", WAF)
    box("templates", "Templates / JSP + WebActions", "98 locale-aware JSPs render the views;\\lHTMLActions bind form data.", WAF)

# ══ petstore.ear — customer storefront ══
with g.subgraph(name="cluster_pet") as c:
    c.attr(label="petstore.ear — Customer Storefront", style="rounded,filled",
           fillcolor="#F2F7FC", color=PET[1], fontsize="12")
    box("shopfacade", "ShoppingClientFacade (SLSB)", "Per-session façade holding the user's cart +\\lidentity for the web tier.", PET)
    box("ordereaction", "OrderEJBAction", "CHECKOUT: builds a PurchaseOrder from the cart,\\lgets an id (uidgen), publishes it to JMS. No persist.", PET)
    box("catalogaction", "Catalog/Cart/SignOn Actions", "Browse, add-to-cart, register, sign-on request\\lhandlers (call the reusable components).", PET)

# ══ opc.ear — Order Processing Center ══
with g.subgraph(name="cluster_opc") as c:
    c.attr(label="opc.ear — Order Processing Center (order workflow owner)", style="rounded,filled",
           fillcolor="#FEF8EC", color=OPC[1], fontsize="12")
    box("pomdb", "PurchaseOrderMDB", "Consumes the PO off the queue → PERSISTS the\\lorder, starts the approval workflow.", OPC)
    box("approvalmdb", "OrderApprovalMDB", "Approves/denies (auto under threshold), advances\\lthe order state; triggers mails + supplier PO.", OPC)
    box("invoicemdb", "InvoiceMDB", "Consumes supplier invoices → marks the order\\lshipped/COMPLETED.", OPC)
    box("opcfacade", "OPCAdminFacade", "Admin queries: orders-by-status, order detail\\l(called by admin.ear).", OPC)
    box("mailmdbs", "MailInvoice / MailOrderApproval /\\lMailCompletedOrder MDBs", "Customer-relations: build order emails and hand\\lthem to the mailer.", OPC)

# ══ supplier.ear — fulfilment + inventory ══
with g.subgraph(name="cluster_sup") as c:
    c.attr(label="supplier.ear — Supplier / Fulfilment + Inventory", style="rounded,filled",
           fillcolor="#FDF1E6", color=SUP[1], fontsize="12")
    box("suporder", "SupplierOrderMDB + OrderFulfillmentFacade", "Consumes approved orders → fulfils from inventory,\\lships, sends invoice back to OPC.", SUP)
    box("rcvr", "RcvrRequestProcessor (Inventory UI)", "The 'receiver' web UI: view stock + restock\\l(qty_&lt;item&gt; params → InventoryEJB.setQuantity).", SUP)

# ══ admin.ear — back-office console ══
with g.subgraph(name="cluster_adm") as c:
    c.attr(label="admin.ear — Admin Console (thin GUI)", style="rounded,filled",
           fillcolor="#FBEDEF", color=ADM[1], fontsize="12")
    box("adminclient", "Swing/JWS client + AdminRequestProcessor", "Owns NO data. Lists orders by status and\\lsubmits approve/deny by calling OPCAdminFacade.", ADM)

# ══ Reusable components (the 19-component library) ══
with g.subgraph(name="cluster_comp") as c:
    c.attr(label="Reusable Components (shared EJB library, 19 total — key ones)", style="rounded,filled",
           fillcolor="#F5F1FB", color=COMP[1], fontsize="12")
    box("cart", "cart — ShoppingCartLocalEJB", "Stateful session cart (itemId→qty map). Lives in\\lthe HttpSession; 15-min session timeout.", COMP)
    box("catalog", "catalog — CatalogEJB + CatalogDAO", "Read catalog (category/product/item), locale-split,\\lvia hand-written DAO SQL.", COMP)
    box("signon", "signon — SignOnEJB + UserEJB", "Authenticate + create users; protects URLs via\\lsignon-config (checkout requires login).", COMP)
    box("customer", "customer — Customer/Account/Profile/CreditCard", "Customer aggregate: contact, address, profile\\lprefs, credit card (CMP entities).", COMP)
    box("po", "purchaseorder / lineitem", "PurchaseOrder + LineItem CMP entities +\\lXML (toXML) for the JMS message.", COMP)
    box("supplierpo", "supplierpo / uidgen", "Supplier-PO doc; UniqueIdGenerator EJB\\l(persistent order-id counter from 1001).", COMP)
    box("mailer", "mailer — MailerMDB + MailHelper", "Sends email via JavaMail when a Mail message\\larrives on MailQueue.", COMP)
    box("async", "asyncsender / processmanager", "AsyncSender puts the PO (as XML) on the queue;\\lProcessManager tracks order workflow status.", COMP)
    box("xmldocs", "xmldocuments / servicelocator / util", "TPA XML (invoice/PO) marshalling; JNDI lookup\\lcache; shared utilities + encoding filter.", COMP)

# ══ Infrastructure / tools ══
with g.subgraph(name="cluster_infra") as c:
    c.attr(label="Infrastructure & Tools", style="rounded,filled",
           fillcolor="#EAF6EE", color=INFRA[1], fontsize="12")
    box("cloudscape", "Cloudscape DB (→ Derby)", "Relational store: CMP-generated tables +\\lhand-written catalog schema.", INFRA)
    box("jms", "JMS (queues + InvoiceTopic)", "Async backbone between the 4 EARs:\\lPurchaseOrder/Approval/Mail queues + InvoiceTopic.", INFRA)
    box("j2eeri", "Sun J2EE 1.3 RI · JDK 1.4", "App server hosting EJB 2.x, servlets/JSP,\\lJMS, JNDI.", INFRA)

# ── key flows (dashed red = JMS, solid = calls) ──
def call(a, b, lbl=""):
    g.edge(a, b, label=lbl, color="#333333", fontsize="8", fontcolor="#333333")

def jms(a, b, lbl=""):
    g.edge(a, b, label=lbl, color="#C0392B", style="dashed", fontsize="8", fontcolor="#C0392B")

call("mainservlet", "reqproc")
call("reqproc", "webctl")
call("webctl", "ordereaction", "Event→Action")
call("ordereaction", "cart", "reads cart")
call("ordereaction", "async", "PO→XML")
jms("async", "pomdb", "PurchaseOrderQueue")
jms("approvalmdb", "suporder", "supplier PO (queue)")
jms("suporder", "invoicemdb", "InvoiceTopic")
jms("mailmdbs", "mailer", "MailQueue")
call("adminclient", "opcfacade", "approve/deny/list")
call("catalogaction", "catalog", "browse")
call("catalog", "cloudscape", "DAO SQL")
call("pomdb", "cloudscape", "persist (CMP)")

# ── faint "used-by" edges: anchor the otherwise-floating reusable components into
#    the graph so the layout stays compact (dotted grey, no labels) ──
def uses(a, b):
    g.edge(a, b, color="#BBBBBB", style="dotted", arrowsize="0.6")

uses("catalogaction", "signon")       # sign-on used by web actions
uses("customer", "signon")            # customer aggregate ties to the user
uses("ordereaction", "po")            # checkout builds PurchaseOrder/LineItem
uses("po", "xmldocs")                 # PO marshalled to XML
uses("ordereaction", "uidgen") if False else uses("ordereaction", "supplierpo")
uses("suporder", "supplierpo")        # supplier PO doc
uses("mailmdbs", "mailer")            # OPC mail MDBs hand to the mailer
uses("pomdb", "po")                   # OPC persists PurchaseOrder entities
uses("shopfacade", "customer")        # façade holds customer identity

# ── invisible ordering edges: force the tiers into stacked rows (not one wide strip) ──
def order(a, b):
    g.edge(a, b, style="invis")

order("statemachine", "shopfacade")
order("shopfacade", "pomdb")
order("catalogaction", "suporder")
order("invoicemdb", "adminclient")
order("cart", "cloudscape")
order("mailer", "jms")

g.render(os.path.join(os.path.dirname(os.path.abspath(__file__)), "petstore_legacy_components"),
         cleanup=True)
print("Wrote petstore_legacy_components.png")
