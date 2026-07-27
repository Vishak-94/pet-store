#!/usr/bin/env python3
import os
"""
Java Pet Store — CURRENT architecture (as-built).
8 Spring Boot services + standalone Artemis broker + shared libraries/SDKs.
Output: petstore_architecture.png / .svg

Legend:  solid black = HTTP/REST   ·   dashed red = JMS   ·   dotted grey = imports (library/SDK)
"""
import graphviz

# ── palette ───────────────────────────────────────────────────────────────
STORE=("#DCE9F7","#2E6DB4"); CUST=("#E9E1F5","#6B4FA0"); CAT=("#D8ECF6","#2E86AB")
ADMIN=("#F7D9DC","#B23A48"); INV=("#FBE7D0","#C97C2F"); AUTH=("#E5E0D8","#6B5B3E")
NOTE=("#D8F0DD","#2F8F46"); OPC=("#FDECC8","#B8860B"); LIB=("#EDEDED","#555555")
DB=("#FFF3CD","#B8860B"); BROKER=("#F0D9F0","#8E44AD")

g = graphviz.Digraph("petstore", format="png")
g.attr(rankdir="TB", splines="spline", nodesep="0.5", ranksep="0.9", bgcolor="white",
       fontname="Helvetica", fontsize="16", labelloc="t",
       label="Java Pet Store — Migrated Architecture (Spring Boot 3.3.5 / Java 21)\\l"
             "8 services + standalone Artemis broker (container) + shared libraries · "
             "solid=HTTP/REST  dashed red=JMS  dotted grey=imports\\l")
g.attr("node", fontname="Helvetica")


def svc(nid, title, sub, eps, c, db_label=None):
    f, b = c
    ep = "<BR ALIGN='LEFT'/>".join(eps)
    dbrow = (f'<TR><TD BGCOLOR="{DB[0]}"><FONT POINT-SIZE="8">🗄 {db_label}</FONT></TD></TR>'
             if db_label else "")
    g.node(nid,
        f'<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="5">'
        f'<TR><TD BGCOLOR="{b}"><FONT COLOR="white" POINT-SIZE="13"><B>{title}</B></FONT>'
        f'<BR/><FONT COLOR="white" POINT-SIZE="8">{sub}</FONT></TD></TR>'
        f'<TR><TD BGCOLOR="{f}" ALIGN="LEFT"><FONT POINT-SIZE="8.5">{ep}<BR ALIGN="LEFT"/></FONT></TD></TR>'
        f'{dbrow}</TABLE>>', shape="plaintext")


def lib(nid, title, sub):
    f, b = LIB
    g.node(nid,
        f'<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="4">'
        f'<TR><TD BGCOLOR="{b}"><FONT COLOR="white" POINT-SIZE="10"><B>{title}</B></FONT></TD></TR>'
        f'<TR><TD BGCOLOR="{f}"><FONT POINT-SIZE="8">{sub}</FONT></TD></TR></TABLE>>',
        shape="plaintext")


# ── actors ──────────────────────────────────────────────────────────────
g.node("browser", "🌐 Customer\\n(browser)", shape="oval", style="filled",
       fillcolor="#F5F5F5", color="#333333", fontsize="11")
g.node("staff", "👤 Staff\\n(ADMIN / SUPPLIER)", shape="oval", style="filled",
       fillcolor="#F5F5F5", color="#333333", fontsize="11")

# ── services ───────────────────────────────────────────────────────────
svc("store", "petstore-app-v1", ":8080 · storefront (broker client)",
    ["GET / /category /product /item /search", "cart add/set/update/delete",
     "GET/POST /checkout → sync REST intake to OPC", "live stock badge (inventory SDK)",
     "embeds cart-lib · i18n en/ja/zh"],
    STORE)

svc("auth", "auth-service", ":8086 · central IdP (RS256 issuer)",
    ["POST /auth/login  → mint JWT", "POST /auth/accounts (provision)", "ONLY holder of private key + creds"],
    AUTH, db_label="account (all users)")

svc("customer", "customer-service", ":8081 · customer domain",
    ["POST /register", "GET /customer/{id}", "PUT /account /profile /card"],
    CUST, db_label="customer (profile/card)")

svc("catalog", "catalog-service", ":8083 · catalog (multi-locale)",
    ["GET /api/categories /products /items", "GET /api/items?keyword", "locale-split tables"],
    CAT, db_label="H2 or MongoDB · category/product/item")

svc("opc", "order-processing-service", ":8088 · Order Processing Center (opc.ear)",
    ["POST /api/orders/intake (sync REST checkout)", "auto-approve / PENDING · outbox → ApprovedOrderQueue",
     "consumes InvoiceTopic → COMPLETED", "RestockTopic re-drives backorders (H2)",
     "facade: /api/orders approve|deny|status|sales"],
    OPC, db_label="H2 or MongoDB · orders + outbox (authoritative)")

svc("admin", "admin-office-service", ":8082 · ADMIN console (admin.ear)",
    ["GET /warehouse/orders (UI)", "POST approve|deny", "owns NO data → delegates to OPC"],
    ADMIN)

svc("inventory", "inventory-service", ":8085 · fulfilment (supplier.ear)",
    ["consumes ApprovedOrderQueue → reserve+ship", "publishes InvoiceTopic + RestockTopic",
     "GET/POST /api/inventory restock (SUPPLIER)", "publishes inventory-service-client SDK"],
    INV, db_label="inventory (pessimistic lock) + dedup ledger")

svc("notify", "notification-service", ":8087 · customer emails",
    ["subscribes InvoiceTopic + OrderStatusTopic", "shipped / approval / denial / completed emails",
     "DLQ + ExpiryQueue observer", "legacy Mail*MDB"],
    NOTE)

# ── broker ───────────────────────────────────────────────────────────────
g.node("broker",
    f'<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="5">'
    f'<TR><TD BGCOLOR="{BROKER[1]}"><FONT COLOR="white"><B>ActiveMQ Artemis</B></FONT>'
    f'<BR/><FONT COLOR="white" POINT-SIZE="8">:61616 · standalone container (docker-compose)</FONT></TD></TR>'
    f'<TR><TD BGCOLOR="{BROKER[0]}"><FONT POINT-SIZE="8.5">'
    f'PurchaseOrderQueue (queue)<BR/>ApprovedOrderQueue (queue)<BR/>InvoiceTopic (topic)<BR/>'
    f'OrderStatusTopic (topic)<BR/>RestockTopic (topic)<BR/>DLQ · ExpiryQueue (safety nets)</FONT></TD></TR>'
    f'</TABLE>>', shape="plaintext")

# ── libraries / SDKs ───────────────────────────────────────────────────
lib("cartlib", "cart-lib", "in-process cart + 15m TTL")
lib("msg", "petstore-messaging", "destinations + event envelope")
lib("authclient", "auth-client", "RS256 verify + login (public key)")
lib("custsdk", "customer-service-client", "customer API SDK")
lib("catsdk", "catalog-service-client", "catalog API SDK")
lib("opcsdk", "order-processing-client", "OPC facade + intake SDK")
lib("invsdk", "inventory-service-client", "stock read SDK + single-flight cache")

# ── HTTP/REST edges (solid black) ──────────────────────────────────────
def http(a, b, lbl=""):
    g.edge(a, b, label=lbl, color="#222222", fontsize="8", fontcolor="#222222")

http("browser", "store", "shop / checkout")
http("staff", "admin", "approve orders")
http("staff", "inventory", "restock")
http("store", "auth", "login")
http("store", "customer", "register / profile")
http("store", "catalog", "browse / cart resolve")
http("store", "opc", "checkout intake (JWT)")
http("store", "inventory", "stock badge / cap")
http("admin", "auth", "login")
http("admin", "opc", "list / approve / deny / sales")
http("inventory", "auth", "verify")
http("customer", "auth", "provision cred")

# ── JMS edges (dashed red) ─────────────────────────────────────────────
def jms(a, b, lbl):
    g.edge(a, b, label=lbl, color="#C0392B", style="dashed", fontsize="8", fontcolor="#C0392B")

jms("opc", "broker", "OrderApproved (outbox)")
jms("broker", "inventory", "ApprovedOrderQueue")
jms("inventory", "broker", "Invoice / Restock")
jms("broker", "opc", "InvoiceTopic + RestockTopic")
jms("broker", "notify", "InvoiceTopic + OrderStatusTopic")
jms("opc", "broker", "OrderStatus")

# ── import edges (dotted grey) ─────────────────────────────────────────
def imp(a, b):
    g.edge(a, b, color="#999999", style="dotted", arrowhead="empty", fontsize="7")

imp("store", "cartlib"); imp("store", "msg"); imp("store", "authclient")
imp("store", "custsdk"); imp("store", "catsdk"); imp("store", "opcsdk"); imp("store", "invsdk")
imp("opc", "msg"); imp("opc", "authclient")
imp("admin", "authclient"); imp("admin", "opcsdk")
imp("inventory", "msg"); imp("inventory", "authclient")
imp("notify", "msg")
imp("cartlib", "catsdk")

g.render(os.path.join(os.path.dirname(os.path.abspath(__file__)), "petstore_architecture"), cleanup=True)
print("Wrote petstore_architecture.png / .svg")
