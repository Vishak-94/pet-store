#!/usr/bin/env python3
import os
"""
Java Pet Store 1.3.1_02 — full database ER diagram.

Shows all 22 tables grouped by bounded context, their columns (PK/FK marked),
and the relationships / mappings:
  - Catalog: hand-written relational, real FKs, locale-split (green)
  - CMP-generated tables: __PMPrimaryKey / __reverse_* synthetic mapping (red)
  - duplicated shared tables (ContactInfo/Address/CreditCard/LineItem) shown once
    per context they appear in, with a note.

All columns are source-verified (PopulateSQL.xml, sun-j2ee-ri.xml).

Output: petstore_er.png / .svg
"""
import graphviz

C_REL   = "#2F8F46"   # relational (catalog)
C_REL_F = "#D8F0DD"
C_CMP   = "#B23A48"   # CMP-generated
C_CMP_F = "#F7D9DC"
C_JOIN  = "#C97C2F"   # join tables
C_JOIN_F= "#FBE7D0"

g = graphviz.Digraph("petstore_er", format="png")
g.attr(rankdir="LR", splines="spline", nodesep="0.4", ranksep="1.1",
       bgcolor="white", fontname="Helvetica", fontsize="15",
       label="Java Pet Store 1.3.1_02 — Database Schema & Mappings (22 tables)\\l"
             "Green = hand-written relational (Catalog)   ·   Red = CMP container-generated   ·   "
             "Amber = join table   ·   ◆ PK   ·   ▸ FK / relationship mapping\\l",
       labelloc="t")
g.attr("node", shape="plaintext", fontname="Helvetica")


def tbl(nid, name, cols, fill, border, note=None):
    """cols = list of (colname, tag) where tag in {'pk','fk','',...}"""
    rows = [f'<TR><TD BGCOLOR="{border}" COLSPAN="2"><FONT COLOR="white" POINT-SIZE="12"><B>{name}</B></FONT></TD></TR>']
    if note:
        rows.append(f'<TR><TD BGCOLOR="{fill}" COLSPAN="2"><FONT POINT-SIZE="8"><I>{note}</I></FONT></TD></TR>')
    for col, tag in cols:
        mark = {"pk": "◆", "fk": "▸", "pkfk": "◆▸"}.get(tag, "")
        port = col.replace('_','').replace('(','').replace(')','')
        bg = "#FFF6D5" if tag in ("pk","pkfk") else "white"
        rows.append(
            f'<TR><TD BGCOLOR="{bg}" WIDTH="18">{mark}</TD>'
            f'<TD BGCOLOR="{bg}" ALIGN="LEFT" PORT="{port}"><FONT POINT-SIZE="9">{col}</FONT></TD></TR>'
        )
    g.node(nid, '<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="3">'
                + "".join(rows) + "</TABLE>>")


# ── CATALOG (relational) ──────────────────────────────────────────────────────
with g.subgraph(name="cluster_cat") as c:
    c.attr(label="Catalog schema (hand-written relational, locale-split)",
           style="rounded,filled", color=C_REL, fillcolor="#FBFEFB", fontsize="13")
    tbl("category", "category", [("catid char(10)", "pk")], C_REL_F, C_REL)
    tbl("category_details", "category_details",
        [("catid char(10)", "pkfk"), ("locale char(10)", "pk"),
         ("name varchar(80)", ""), ("image varchar(255)", ""), ("descn varchar(255)", "")], C_REL_F, C_REL)
    tbl("product", "product", [("productid char(10)", "pk"), ("catid char(10)", "fk")], C_REL_F, C_REL)
    tbl("product_details", "product_details",
        [("productid char(10)", "pkfk"), ("locale char(10)", "pk"),
         ("name varchar(80)", ""), ("image varchar(255)", ""), ("descn varchar(255)", "")], C_REL_F, C_REL)
    tbl("item", "item", [("itemid char(10)", "pk"), ("productid char(10)", "fk")], C_REL_F, C_REL)
    tbl("item_details", "item_details",
        [("itemid char(10)", "pkfk"), ("locale char(10)", "pk"),
         ("listprice decimal(10,2)", ""), ("unitcost decimal(10,2)", ""),
         ("image / descn", ""), ("attr1..attr5 varchar(80)", "")], C_REL_F, C_REL)

# ── CUSTOMER (CMP) ────────────────────────────────────────────────────────────
with g.subgraph(name="cluster_cust") as c:
    c.attr(label="Customer context (CMP — petstore.ear)",
           style="rounded,filled", color=C_CMP, fillcolor="#FFFAFB", fontsize="13")
    tbl("user", "UserEJBTable", [("userName", "pk"), ("password", "")], C_CMP_F, C_CMP)
    tbl("customer", "CustomerEJBTable",
        [("userId", "pk"), ("_account__PMPrimaryKey", "fk"), ("_profile__PMPrimaryKey", "fk")], C_CMP_F, C_CMP)
    tbl("account", "AccountEJBTable",
        [("__PMPrimaryKey", "pk"), ("__reverse_account_userId", "fk"),
         ("_contactInfo__PMPrimaryKey", "fk"), ("_creditCard__PMPrimaryKey", "fk"), ("status", "")], C_CMP_F, C_CMP)
    tbl("profile", "ProfileEJBTable",
        [("__PMPrimaryKey", "pk"), ("preferredLanguage", ""), ("favoriteCategory", ""),
         ("myListPreference bool", ""), ("bannerPreference bool", "")], C_CMP_F, C_CMP)
    tbl("counter", "CounterEJBTable", [("name", "pk"), ("counter int", "")], C_CMP_F, C_CMP,
        note="uidgen — order-id sequence")

# ── ORDER / OPC (CMP) ─────────────────────────────────────────────────────────
with g.subgraph(name="cluster_opc") as c:
    c.attr(label="Order context (CMP — opc.ear)",
           style="rounded,filled", color=C_CMP, fillcolor="#FFFAFB", fontsize="13")
    tbl("po", "PurchaseOrderEJBTable",
        [("poId", "pk"), ("poUserId / poEmailId", ""), ("poDate longint", ""),
         ("poLocale", ""), ("poValue real", ""),
         ("_contactInfo__PMPrimaryKey", "fk"), ("_creditCard__PMPrimaryKey", "fk")], C_CMP_F, C_CMP)
    tbl("lineitem", "LineItemEJBTable",
        [("__PMPrimaryKey", "pk"), ("itemId / productId", ""), ("categoryId", ""),
         ("lineNumber", ""), ("quantity int", ""), ("quantityShipped int", ""), ("unitPrice real", "")],
        C_CMP_F, C_CMP, note="shared: opc + supplier")
    tbl("po_join", "PurchaseOrderEJB_lineItems_\\nLineItemEJB_Table",
        [("_PurchaseOrderEJB_poId", "fk"), ("_LineItemEJB__PMPrimaryKey", "pkfk")], C_JOIN_F, C_JOIN)
    tbl("manager", "ManagerEJBTable", [("orderId", "pk"), ("status", "")], C_CMP_F, C_CMP,
        note="workflow spine — 3 MDBs write here")

# ── SUPPLIER (CMP) ────────────────────────────────────────────────────────────
with g.subgraph(name="cluster_sup") as c:
    c.attr(label="Supplier context (CMP — supplier.ear)",
           style="rounded,filled", color=C_CMP, fillcolor="#FFFAFB", fontsize="13")
    tbl("inventory", "InventoryEJBTable", [("itemId", "pk"), ("quantity int", "")], C_CMP_F, C_CMP)
    tbl("so", "SupplierOrderEJBTable",
        [("poId", "pk"), ("poDate longint", ""), ("poStatus", ""), ("_contactInfo__PMPrimaryKey", "fk")], C_CMP_F, C_CMP)
    tbl("so_join", "SupplierOrderEJB_lineItems_\\nLineItemEJB_Table",
        [("_SupplierOrderEJB_poId", "fk"), ("_LineItemEJB__PMPrimaryKey", "pkfk")], C_JOIN_F, C_JOIN)

# ── SHARED value objects (duplicated) ─────────────────────────────────────────
with g.subgraph(name="cluster_shared") as c:
    c.attr(label="Shared value-object tables (DUPLICATED across apps)",
           style="rounded,filled", color=C_CMP, fillcolor="#FFF3F4", fontsize="13")
    tbl("contactinfo", "ContactInfoEJBTable",
        [("__PMPrimaryKey", "pk"), ("givenName / familyName", ""), ("email / telephone", ""),
         ("_address__PMPrimaryKey", "fk")], C_CMP_F, C_CMP, note="in petstore + opc + supplier")
    tbl("address", "AddressEJBTable",
        [("__PMPrimaryKey", "pk"), ("streetName1/2", ""), ("city / state", ""),
         ("zipCode / country", "")], C_CMP_F, C_CMP, note="in petstore + opc + supplier")
    tbl("creditcard", "CreditCardEJBTable",
        [("__PMPrimaryKey", "pk"), ("cardNumber", ""), ("cardType", ""), ("expiryDate", "")],
        C_CMP_F, C_CMP, note="in petstore + opc")

# ── relationships (FK mappings) ───────────────────────────────────────────────
def rel(a, b, label, color, style="solid"):
    g.edge(a, b, label=label, color=color, fontcolor=color, fontsize="9",
           style=style, penwidth="1.4", arrowhead="crow", arrowtail="none", dir="both")

# catalog real FKs (green)
rel("category_details", "category", "catid", C_REL)
rel("product", "category", "catid", C_REL)
rel("product_details", "product", "productid", C_REL)
rel("item", "product", "productid", C_REL)
rel("item_details", "item", "itemid", C_REL)

# customer graph (red, container-managed relations)
rel("customer", "account", "1—1", C_CMP)
rel("customer", "profile", "1—1", C_CMP)
rel("account", "contactinfo", "1—1", C_CMP)
rel("account", "creditcard", "1—1", C_CMP)
rel("contactinfo", "address", "1—1", C_CMP)
g.edge("user", "customer", label="userId (login)", color=C_CMP, fontcolor=C_CMP,
       fontsize="9", style="dashed", arrowhead="none")

# order graph
rel("po", "contactinfo", "bill/ship", C_CMP)
rel("po", "creditcard", "1—1", C_CMP)
g.edge("po", "po_join", label="1—N", color=C_JOIN, fontcolor=C_JOIN, fontsize="9", arrowhead="crow")
g.edge("po_join", "lineitem", label="", color=C_JOIN, arrowhead="none")
g.edge("manager", "po", label="orderId≈poId (status)", color=C_CMP, fontcolor=C_CMP,
       fontsize="9", style="dashed", arrowhead="none")

# supplier graph
rel("so", "contactinfo", "ship", C_CMP)
g.edge("so", "so_join", label="1—N", color=C_JOIN, fontcolor=C_JOIN, fontsize="9", arrowhead="crow")
g.edge("so_join", "lineitem", label="", color=C_JOIN, arrowhead="none")
g.edge("so", "inventory", label="fulfils via itemId", color=C_CMP, fontcolor=C_CMP,
       fontsize="9", style="dashed", arrowhead="none")

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "petstore_er")
g.render(out, format="png", cleanup=True)
g.render(out, format="svg", cleanup=True)
print("Wrote", out + ".png / .svg")
