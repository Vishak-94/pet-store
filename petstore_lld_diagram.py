#!/usr/bin/env python3
"""
Java Pet Store 1.3.1_02 — Low-Level Design / class-level diagram generator.

Renders a UML-style class diagram grouped by bounded context, showing:
  - Service / business-logic layer (EJB session beans, MDBs, WAF controllers)
  - Domain model (value objects / entities)
  - Data-access layer (DAO + CMP entity beans) and the tables they map to
  - Table "type" (hand-written relational vs CMP container-generated)
  - Cross-context async edges (JMS queues)

All classes, methods, and table names below were extracted from the actual
source tree (components/*, apps/*, sun-j2ee-ri.xml, PopulateSQL.xml,
CatalogDAOSQL.xml), not invented.

Output: petstore_lld.png / petstore_lld.svg
"""

import graphviz

# ─────────────────────────────────────────────────────────────────────────────
# Palette (colour-blind safe, light + readable)
# ─────────────────────────────────────────────────────────────────────────────
C_SERVICE   = "#DCE9F7"   # service / business logic  (blue)
C_SERVICE_B = "#2E6DB4"
C_DOMAIN    = "#E9E1F5"   # domain model              (purple)
C_DOMAIN_B  = "#6B4FA0"
C_DAO       = "#FBE7D0"   # data-access layer         (amber)
C_DAO_B     = "#C97C २F"
C_DAO_B     = "#C97C2F"
C_TABLE_REL = "#D8F0DD"   # hand-written relational table (green)
C_TABLE_REL_B = "#2F8F46"
C_TABLE_CMP = "#F7D9DC"   # CMP container-generated table (red)
C_TABLE_CMP_B = "#B23A48"
C_FRAME     = "#EDEDED"   # framework
C_FRAME_B   = "#666666"


def cls(name, stereotype, methods, fill, border):
    """Build an HTML-like UML class node label (name / stereotype / methods)."""
    rows = [
        f'<TR><TD BGCOLOR="{border}"><FONT COLOR="white" POINT-SIZE="13">'
        f'<B>{name}</B></FONT></TD></TR>',
        f'<TR><TD BGCOLOR="{fill}"><FONT POINT-SIZE="9"><I>{stereotype}</I></FONT></TD></TR>',
    ]
    if methods:
        body = "<BR ALIGN='LEFT'/>".join(f"+ {m}" for m in methods)
        rows.append(
            f'<TR><TD BGCOLOR="white" ALIGN="LEFT"><FONT POINT-SIZE="9">'
            f'{body}<BR ALIGN="LEFT"/></FONT></TD></TR>'
        )
    return (
        '<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="4">'
        + "".join(rows)
        + "</TABLE>>"
    )


def table_node(name, cols, kind):
    """Data table node. kind = 'rel' (hand-written) or 'cmp' (generated)."""
    fill, border = (C_TABLE_REL, C_TABLE_REL_B) if kind == "rel" else (C_TABLE_CMP, C_TABLE_CMP_B)
    tag = "relational · hand-written DDL" if kind == "rel" else "CMP · container-generated"
    body = "<BR ALIGN='LEFT'/>".join(cols)
    return (
        f'<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="4">'
        f'<TR><TD BGCOLOR="{border}"><FONT COLOR="white" POINT-SIZE="12"><B>{name}</B></FONT></TD></TR>'
        f'<TR><TD BGCOLOR="{fill}"><FONT POINT-SIZE="8"><I>&#9635; {tag}</I></FONT></TD></TR>'
        f'<TR><TD BGCOLOR="white" ALIGN="LEFT"><FONT POINT-SIZE="8">{body}<BR ALIGN="LEFT"/></FONT></TD></TR>'
        f'</TABLE>>'
    )


g = graphviz.Digraph("petstore_lld", format="png")
g.attr(rankdir="TB", splines="spline", nodesep="0.5", ranksep="0.9",
       bgcolor="white", fontname="Helvetica",
       label="Java Pet Store 1.3.1_02 — Low-Level Design (class level, by bounded context)\\l"
             "Service/business-logic (blue)  ·  Domain model (purple)  ·  Data access (amber)  ·  "
             "Relational table (green)  ·  CMP-generated table (red)  ·  JMS async edge (dashed)\\l",
       labelloc="t", fontsize="15")
g.attr("node", shape="plaintext", fontname="Helvetica")
g.attr("edge", fontname="Helvetica", fontsize="9", color="#444444")

# ─────────────────────────────────────────────────────────────────────────────
# FRAMEWORK — WAF (Web Application Framework), the MVC engine
# ─────────────────────────────────────────────────────────────────────────────
with g.subgraph(name="cluster_waf") as c:
    c.attr(label="WAF — Web Application Framework (petstore.ear)", style="rounded,filled",
           color=C_FRAME_B, fillcolor="#FAFAFA", fontsize="13")
    c.node("MainServlet", cls("MainServlet", "«front controller» HttpServlet",
        ["init(config)", "doGet(req,res)", "doPost(req,res)", "doProcess()"], C_FRAME, C_FRAME_B))
    c.node("RequestProcessor", cls("RequestProcessor", "«web tier»",
        ["init()", "processRequest(req)"], C_FRAME, C_FRAME_B))
    c.node("ScreenFlowManager", cls("ScreenFlowManager", "«view flow»",
        ["forwardToNextScreen()", "getCurrentScreen()"], C_FRAME, C_FRAME_B))
    c.node("EJBControllerLocalEJB", cls("EJBControllerLocalEJB", "«session façade» StatefulSessionBean",
        ["processEvent(Event): EventResponse", "getStateMachine()"], C_SERVICE, C_SERVICE_B))
    c.node("StateMachine", cls("StateMachine", "«command dispatch»",
        ["processEvent(e)", "getAction(e): EJBAction"], C_SERVICE, C_SERVICE_B))

    c.edge("MainServlet", "RequestProcessor")
    c.edge("MainServlet", "ScreenFlowManager")
    c.edge("RequestProcessor", "EJBControllerLocalEJB", label="fires Event")
    c.edge("EJBControllerLocalEJB", "StateMachine")

# ─────────────────────────────────────────────────────────────────────────────
# CATALOG context — the ONLY clean DAO + relational schema
# ─────────────────────────────────────────────────────────────────────────────
with g.subgraph(name="cluster_catalog") as c:
    c.attr(label="Catalog context  (component: catalog)", style="rounded,filled",
           color=C_DAO_B, fillcolor="#FFFDF9", fontsize="13")
    c.node("CatalogEJB", cls("CatalogEJB", "«session bean» service",
        ["getCategory(id,locale)", "getProducts(catId,..)", "getItem(id,locale)",
         "searchItems(query,..)"], C_SERVICE, C_SERVICE_B))
    c.node("CatalogDAO", cls("CatalogDAO", "«interface» DAO port",
        ["getCategory()", "getProduct()", "getItem()", "getItems()", "searchItems()"], C_DAO, C_DAO_B))
    c.node("CloudscapeCatalogDAO", cls("CloudscapeCatalogDAO", "«adapter» impl",
        ["+ vendor SQL from CatalogDAOSQL.xml"], C_DAO, C_DAO_B))
    c.node("GenericCatalogDAO", cls("GenericCatalogDAO", "«adapter» impl",
        ["+ portable SQL"], C_DAO, C_DAO_B))
    c.node("CatalogModel", cls("Category / Product / Item / Page", "«domain value objects»",
        ["ids, name, descn, price, locale"], C_DOMAIN, C_DOMAIN_B))

    c.node("t_category", table_node("category / category_details",
        ["catid PK", "name, image, descn", "locale (i18n split)"], "rel"))
    c.node("t_product", table_node("product / product_details",
        ["productid PK, catid FK", "name, image, descn, locale"], "rel"))
    c.node("t_item", table_node("item / item_details",
        ["itemid PK, productid FK", "listprice, unitcost DEC(10,2)", "attr1..attr5, locale"], "rel"))

    c.edge("CatalogEJB", "CatalogDAO", label="uses")
    c.edge("CatalogDAO", "CloudscapeCatalogDAO", arrowhead="empty", style="dashed", label="realizes")
    c.edge("CatalogDAO", "GenericCatalogDAO", arrowhead="empty", style="dashed", label="realizes")
    c.edge("CatalogEJB", "CatalogModel", label="returns")
    c.edge("CloudscapeCatalogDAO", "t_category", label="SQL")
    c.edge("CloudscapeCatalogDAO", "t_product", label="SQL")
    c.edge("CloudscapeCatalogDAO", "t_item", label="SQL")

# ─────────────────────────────────────────────────────────────────────────────
# CART + SIGNON (session-scoped, storefront)
# ─────────────────────────────────────────────────────────────────────────────
with g.subgraph(name="cluster_cart") as c:
    c.attr(label="Cart + SignOn contexts", style="rounded,filled",
           color=C_SERVICE_B, fillcolor="#F8FBFF", fontsize="13")
    c.node("ShoppingCartLocalEJB", cls("ShoppingCartLocalEJB", "«stateful session bean» service",
        ["addItem(id)", "updateItemQuantity(id,n)", "deleteItem(id)",
         "getItems(): Collection", "getSubTotal()", "empty()"], C_SERVICE, C_SERVICE_B))
    c.node("CartItem", cls("CartItem / Cart", "«domain value object»",
        ["itemId, productId, qty, unitCost"], C_DOMAIN, C_DOMAIN_B))
    c.node("SignOnEJB", cls("SignOnEJB", "«session bean» service",
        ["authenticate(user,pwd): bool", "createUser(user,pwd)"], C_SERVICE, C_SERVICE_B))
    c.node("UserEJB", cls("UserEJB", "«CMP entity bean»",
        ["userName PK, password"], C_DAO, C_DAO_B))
    c.node("t_user", table_node("UserEJBTable", ["userName PK", "password"], "cmp"))

    c.edge("ShoppingCartLocalEJB", "CartItem", label="holds")
    c.edge("SignOnEJB", "UserEJB", label="uses")
    c.edge("UserEJB", "t_user", label="CMP maps")

# ─────────────────────────────────────────────────────────────────────────────
# CUSTOMER context — CMP entity beans + generated tables
# ─────────────────────────────────────────────────────────────────────────────
with g.subgraph(name="cluster_customer") as c:
    c.attr(label="Customer context  (component: customer)", style="rounded,filled",
           color=C_TABLE_CMP_B, fillcolor="#FFFAFB", fontsize="13")
    c.node("CustomerEJB", cls("CustomerEJB", "«CMP entity bean»",
        ["userId PK", "→ Account, → Profile"], C_DAO, C_DAO_B))
    c.node("AccountEJB", cls("AccountEJB", "«CMP entity bean»",
        ["status", "→ ContactInfo, → CreditCard"], C_DAO, C_DAO_B))
    c.node("ProfileEJB", cls("ProfileEJB", "«CMP entity bean»",
        ["preferredLanguage, favoriteCategory", "myListPreference, bannerPreference"], C_DAO, C_DAO_B))
    c.node("ContactInfoEJB", cls("ContactInfoEJB / AddressEJB / CreditCardEJB", "«CMP entity beans» (shared)",
        ["names, email, phone", "street, city, state, zip", "cardNumber, cardType, expiry"], C_DAO, C_DAO_B))

    c.node("t_customer", table_node("CustomerEJBTable / AccountEJBTable / ProfileEJBTable",
        ["userId PK", "__PMPrimaryKey (synthetic)", "__reverse_* FK cols", "all VARCHAR(255)"], "cmp"))
    c.node("t_contact", table_node("ContactInfoEJBTable / AddressEJBTable / CreditCardEJBTable",
        ["__PMPrimaryKey PK", "givenName, familyName, email", "street, city, state, zip", "cardNumber, cardType"], "cmp"))

    c.edge("CustomerEJB", "AccountEJB", label="1—1")
    c.edge("CustomerEJB", "ProfileEJB", label="1—1")
    c.edge("AccountEJB", "ContactInfoEJB", label="1—1")
    c.edge("CustomerEJB", "t_customer", label="CMP maps")
    c.edge("ContactInfoEJB", "t_contact", label="CMP maps")

# ─────────────────────────────────────────────────────────────────────────────
# ORDER / OPC context — MDB workflow
# ─────────────────────────────────────────────────────────────────────────────
with g.subgraph(name="cluster_opc") as c:
    c.attr(label="Order Processing Center  (opc.ear)", style="rounded,filled",
           color=C_SERVICE_B, fillcolor="#F8FBFF", fontsize="13")
    c.node("PurchaseOrderMDB", cls("PurchaseOrderMDB", "«message-driven bean» service",
        ["onMessage(msg)", "→ persist PO, start workflow"], C_SERVICE, C_SERVICE_B))
    c.node("OrderApprovalMDB", cls("OrderApprovalMDB / InvoiceMDB", "«message-driven beans»",
        ["onMessage(msg)", "approve / invoice transitions"], C_SERVICE, C_SERVICE_B))
    c.node("ProcessManagerEJB", cls("ProcessManagerEJB", "«session bean» workflow",
        ["createManager(orderId)", "updateStatus(id,status)",
         "getStatus(id)", "getOrdersByStatus(s)"], C_SERVICE, C_SERVICE_B))
    c.node("PurchaseOrderEJB", cls("PurchaseOrderEJB", "«CMP entity bean»",
        ["poId PK, poUserId, poDate, poValue", "addLineItem()", "getData(): PurchaseOrder"], C_DAO, C_DAO_B))
    c.node("LineItemEJB", cls("LineItemEJB", "«CMP entity bean»",
        ["itemId, qty, quantityShipped, unitPrice"], C_DAO, C_DAO_B))
    c.node("PurchaseOrderVO", cls("PurchaseOrder / LineItem", "«domain value objects» (XML)",
        ["marshalled to/from XML for JMS"], C_DOMAIN, C_DOMAIN_B))

    c.node("t_po", table_node("PurchaseOrderEJBTable / LineItemEJBTable (+join)",
        ["poId PK", "PO 1—N LineItem via join table", "→ ContactInfo, → CreditCard"], "cmp"))
    c.node("t_mgr", table_node("ManagerEJBTable", ["orderId PK", "status"], "cmp"))

    c.edge("PurchaseOrderMDB", "ProcessManagerEJB", label="tracks")
    c.edge("PurchaseOrderMDB", "PurchaseOrderEJB", label="persists")
    c.edge("PurchaseOrderEJB", "LineItemEJB", label="1—N")
    c.edge("PurchaseOrderEJB", "PurchaseOrderVO", label="getData()")
    c.edge("PurchaseOrderEJB", "t_po", label="CMP maps")
    c.edge("ProcessManagerEJB", "t_mgr", label="CMP maps")

# ─────────────────────────────────────────────────────────────────────────────
# SUPPLIER context
# ─────────────────────────────────────────────────────────────────────────────
with g.subgraph(name="cluster_supplier") as c:
    c.attr(label="Supplier  (supplier.ear)", style="rounded,filled",
           color=C_SERVICE_B, fillcolor="#F8FBFF", fontsize="13")
    c.node("SupplierOrderMDB", cls("SupplierOrderMDB", "«message-driven bean» service",
        ["onMessage(msg)", "fulfil PO, ship, invoice"], C_SERVICE, C_SERVICE_B))
    c.node("SupplierOrderEJB", cls("SupplierOrderEJB", "«CMP entity bean»",
        ["poId PK, poDate, poStatus"], C_DAO, C_DAO_B))
    c.node("InventoryEJB", cls("InventoryEJB", "«CMP entity bean»",
        ["itemId PK, quantity", "reduceQuantity(n)"], C_DAO, C_DAO_B))
    c.node("t_supplier", table_node("SupplierOrderEJBTable / InventoryEJBTable",
        ["poId PK · itemId PK, quantity", "+ LineItem, ContactInfo (dup)"], "cmp"))

    c.edge("SupplierOrderMDB", "SupplierOrderEJB", label="persists")
    c.edge("SupplierOrderMDB", "InventoryEJB", label="decrements")
    c.edge("SupplierOrderEJB", "t_supplier", label="CMP maps")
    c.edge("InventoryEJB", "t_supplier", style="invis")

# ─────────────────────────────────────────────────────────────────────────────
# CROSS-CONTEXT ASYNC EDGES (JMS) — the distributed backbone
# ─────────────────────────────────────────────────────────────────────────────
g.attr("edge", color="#B23A48", style="dashed", fontcolor="#B23A48", penwidth="2")
g.edge("EJBControllerLocalEJB", "PurchaseOrderMDB",
       label="jms/PurchaseOrderQueue  (checkout → PO as XML)", constraint="false")
g.edge("PurchaseOrderMDB", "SupplierOrderMDB",
       label="jms/supplier  (forward PO)", constraint="false")
g.edge("SupplierOrderMDB", "OrderApprovalMDB",
       label="jms/  invoice (XML)", constraint="false")

out = "/Users/vishakvj/Downloads/pet-project/petstore_lld"
g.render(out, format="png", cleanup=True)
g.render(out, format="svg", cleanup=True)
print("Wrote", out + ".png and .svg")
