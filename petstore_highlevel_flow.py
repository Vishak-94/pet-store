#!/usr/bin/env python3
"""
Java Pet Store 1.3.1_02 — HIGH-LEVEL flow diagram.

Shows the four apps, their endpoints/APIs grouped by actor, and the
synchronous (HTTP/SOAP) + asynchronous (JMS) flows between them.
Source-verified against mappings.xml, web.xml, the SEIs, and the MDB set.

Output: petstore_highlevel_flow.png / .svg
"""
import graphviz

# palette
C_CUST   = "#DCE9F7"; C_CUST_B  = "#2E6DB4"   # customer
C_SUPP   = "#FBE7D0"; C_SUPP_B  = "#C97C2F"   # supplier
C_ADMIN  = "#E9E1F5"; C_ADMIN_B = "#6B4FA0"   # admin
C_SYS    = "#D8F0DD"; C_SYS_B   = "#2F8F46"   # system/opc
C_OPS    = "#EDEDED"; C_OPS_B   = "#666666"   # ops
C_DB     = "#F7D9DC"; C_DB_B    = "#B23A48"   # datastore

g = graphviz.Digraph("petstore_hl", format="png")
g.attr(rankdir="LR", splines="spline", nodesep="0.35", ranksep="1.1",
       bgcolor="white", fontname="Helvetica", fontsize="15",
       label="Java Pet Store 1.3.1_02 — High-Level Flow (apps · endpoints · APIs · JMS)\\l"
             "Solid = synchronous (HTTP / SOAP)   ·   Dashed red = asynchronous (JMS)   ·   "
             "Customer (blue) · Supplier (amber) · Admin (purple) · System/OPC (green)\\l",
       labelloc="t")
g.attr("node", fontname="Helvetica")
g.attr("edge", fontname="Helvetica", fontsize="10")


def actor(name, fill, border):
    g.node(name, name, shape="oval", style="filled", fillcolor=fill,
           color=border, fontsize="13", fontcolor=border)


def app(nid, title, endpoints, fill, border):
    ep = "<BR ALIGN='LEFT'/>".join(endpoints)
    label = (
        f'<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="6">'
        f'<TR><TD BGCOLOR="{border}"><FONT COLOR="white" POINT-SIZE="14"><B>{title}</B></FONT></TD></TR>'
        f'<TR><TD BGCOLOR="white" ALIGN="LEFT"><FONT POINT-SIZE="10">{ep}<BR ALIGN="LEFT"/></FONT></TD></TR>'
        f'</TABLE>>'
    )
    g.node(nid, label, shape="plaintext")


def store(nid, label):
    g.node(nid, label, shape="cylinder", style="filled", fillcolor=C_DB,
           color=C_DB_B, fontsize="10", fontcolor=C_DB_B)


# ── actors ───────────────────────────────────────────────────────────────────
actor("Customer", C_CUST, C_CUST_B)
actor("WarehouseStaff", C_SUPP, C_SUPP_B)
actor("AdminStaff", C_ADMIN, C_ADMIN_B)
actor("Operator", C_OPS, C_OPS_B)

# ── apps with their endpoints ─────────────────────────────────────────────────
app("petstore", "petstore.ear  (Storefront)", [
    "Browse: main/category/product/item/search.screen",
    "cart.do   (in-memory)",
    "signon.do · createcustomer.do",
    "customer.do  [login]",
    "order.do  [login] → checkout",
    "/Populate  (seed)",
], C_CUST, C_CUST_B)

app("opc", "opc.ear  (Order Processing Center)", [
    "PurchaseOrderMDB  (consume PO)",
    "OrderApprovalMDB  (approve/deny)",
    "InvoiceMDB  (invoice → shipped)",
    "Mail MDBs → email",
    "SOAP: OPCService.submitInvoice",
], C_SYS, C_SYS_B)

app("supplier", "supplier.ear  (Fulfilment)", [
    "SupplierOrderMDB  (fulfil PO)",
    "/RcvrRequestProcessor  (inventory UI)",
    "SOAP: SupplierService.submitOrder / queryOrderStatus",
    "/Populate  (seed inventory)",
], C_SUPP, C_SUPP_B)

app("admin", "admin.ear  (Back-office)", [
    "/AdminRequestProcessor  (approve orders)",
    "/ApplRequestProcessor  (JWS launcher)",
    "Swing client  (dead on modern JVM)",
], C_ADMIN, C_ADMIN_B)

# ── datastores (database-per-app) ─────────────────────────────────────────────
store("db_ps", "Catalog + Customer\\n(category/product/item,\\nCustomer/Account/...)")
store("db_opc", "Orders\\n(PurchaseOrder, LineItem,\\nManager status)")
store("db_sup", "Inventory\\n(Inventory, SupplierOrder)")

# ── actor → app (synchronous HTTP) ────────────────────────────────────────────
g.attr("edge", color=C_CUST_B, style="solid", penwidth="1.6")
g.edge("Customer", "petstore", label="HTTP  browse / cart / checkout")
g.attr("edge", color=C_SUPP_B)
g.edge("WarehouseStaff", "supplier", label="HTTP  inventory UI")
g.attr("edge", color=C_ADMIN_B)
g.edge("AdminStaff", "admin", label="HTTP  approve orders")
g.attr("edge", color=C_OPS_B, style="dotted")
g.edge("Operator", "petstore", label="setup /Populate")
g.edge("Operator", "supplier", label="setup /Populate")

# ── app → datastore ───────────────────────────────────────────────────────────
g.attr("edge", color=C_DB_B, style="solid", penwidth="1.3")
g.edge("petstore", "db_ps", label="JDBC")
g.edge("opc", "db_opc", label="JDBC (writes order/status)")
g.edge("supplier", "db_sup", label="JDBC (inventory)")

# ── asynchronous JMS flows (the backbone) ─────────────────────────────────────
g.attr("edge", color="#B23A48", style="dashed", fontcolor="#B23A48", penwidth="2.0")
g.edge("petstore", "opc", label="JMS  PurchaseOrderQueue  (PO as XML)")
g.edge("opc", "opc", label="JMS  OrderApprovalQueue")
g.edge("opc", "supplier", label="JMS/SOAP  submitOrder  (forward PO)")
g.edge("supplier", "opc", label="JMS  InvoiceTopic  (pub/sub) / SOAP submitInvoice")
g.edge("admin", "opc", label="JMS  approve → OrderApproval", constraint="false")

out = "/Users/vishakvj/Downloads/pet-project/petstore_highlevel_flow"
g.render(out, format="png", cleanup=True)
g.render(out, format="svg", cleanup=True)
print("Wrote", out + ".png / .svg")
