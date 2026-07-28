#!/usr/bin/env python3
"""LLD diagram generator for cart-lib — the embeddable in-process shopping cart.

Renders two diagrams into this docs/ folder:
  * cart-lib_class.png/.svg  — UML class diagram (library core + client-SDK seam
    + host wiring), showing the framework-free domain and the reuse/extensibility seams.
  * cart-lib_schema.png/.svg — the in-memory DATA MODEL + wire (DTO) contract, since
    cart-lib has NO database. Shows the cartId -> CartEntry -> quantities map and the
    qty<=0-removes / sliding-TTL invariants.

Everything here is extracted from the real source under ../src and the host wiring in
petstore-app-v1 — no invented members. Run:  python3 cart-lib_lld.py
"""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND


# ─────────────────────────────────────────────────────────────────────────────
#  (a) CLASS DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_class_diagram():
    g = new_graph("cart-lib — Class diagram (embeddable in-process cart)", rankdir="TB")

    # ---- Library core: com.petstore.cart (framework-free) ----
    def _core(s):
        s.node("Ops", uml_class(
            "CartOperations", "business logic (legacy port)",
            attrs=["store: CartStore",
                   "catalog: CatalogServiceClient",
                   "LOCALE = \"en_US\"  (static)",
                   "MAX_QUANTITY = 999  (static)"],
            methods=["addItem(cartId, itemId, Integer qty): CartView",
                     "setQuantity(cartId, itemId, int qty): CartView",
                     "deleteItem(cartId, itemId): CartView",
                     "empty(cartId): void",
                     "count(cartId): int  (no catalog call)",
                     "view(cartId): CartView",
                     "capQuantity(int): int  (private)"],
            kind="service",
            note="null add RESETS qty=1; qty<=0 removes; qty capped at 999"))

        s.node("Store", uml_class(
            "CartStore", "in-memory state  implements AutoCloseable",
            attrs=["carts: ConcurrentHashMap<String,CartEntry>",
                   "ttl: Duration  (default 15 min)",
                   "sweeper: ScheduledExecutorService (daemon)"],
            methods=["withCart(cartId, Function op): T",
                     "snapshot(cartId): Map<String,Integer>",
                     "remove(cartId): void",
                     "evictExpired(): void  (package)",
                     "sweepQuietly(): void  (package)",
                     "size(): int",
                     "close(): void"],
            kind="adapter",
            note="sliding TTL: any touch refreshes lastAccess"))

        s.node("Entry", uml_class(
            "CartStore.CartEntry", "private static nested",
            attrs=["quantities: LinkedHashMap<String,Integer>",
                   "lastAccess: Instant (volatile)"],
            kind="domain"))

        s.node("View", uml_class(
            "CartDtos.CartView", "record (wire DTO)",
            attrs=["items: List<CartItemView>",
                   "subTotal: double",
                   "count: int  (distinct lines)"],
            methods=["empty(): CartView  (static)"],
            kind="domain"))

        s.node("ItemView", uml_class(
            "CartDtos.CartItemView", "record (wire DTO)",
            attrs=["itemId, productId, category",
                   "productName, attribute: String",
                   "quantity: int",
                   "unitCost: double  (= list price)"],
            methods=["totalCost(): double"],
            kind="domain"))

    # ---- Reused client SDK seam ----
    def _sdk(s):
        s.node("CatClient", uml_class(
            "CatalogServiceClient", "client SDK (RestClient)",
            methods=["getItem(itemId, locale): Optional<ItemDto>"],
            kind="client",
            note="reused SDK jar: catalog-service-client"))
        s.node("ItemDto", uml_class(
            "CatalogDtos.ItemDto", "record (SDK contract)",
            attrs=["itemId, productId, productName",
                   "category, attribute1..5",
                   "listPrice, unitCost: double"],
            kind="external"))

    # ---- Host wiring (petstore-app-v1) — outside this module ----
    def _host(s):
        s.node("Config", uml_class(
            "CartConfig", "@Configuration (host)",
            methods=["@Bean(destroyMethod=\"close\") cartStore(...)",
                     "@Bean cartOperations(store, catalog)"],
            kind="config",
            note="cart.ttl-minutes / cart.sweep-interval-seconds"))
        s.node("Svc", uml_class(
            "CartService", "@Service adapter (host)",
            methods=["resolves per-request cartId (CartIdFilter cookie)",
                     "delegates addItem/set/delete/view/count/empty"],
            kind="external"))

    cluster(g, "core", "cart-lib  ·  com.petstore.cart  (framework-free library)", _core, "#EAF2FB")
    cluster(g, "sdk", "Reused client SDK  ·  com.petstore.catalog.client", _sdk, "#E9F2FA")
    cluster(g, "host", "Host wiring  ·  petstore-app-v1 (only consumer)", _host, "#EDEDED")

    # relationships (all from real code)
    edge(g, "Ops", "Store", "depends", "mutates/reads state")
    edge(g, "Ops", "CatClient", "depends", "resolve price/name")
    edge(g, "Ops", "View", "flow", "returns")
    edge(g, "Store", "Entry", "compose", "owns per cartId")
    edge(g, "View", "ItemView", "compose", "lines")
    edge(g, "CatClient", "ItemDto", "flow", "getItem ->")
    edge(g, "Ops", "ItemView", "flow", "maps ItemDto -> line")
    edge(g, "Config", "Store", "depends", "@Bean")
    edge(g, "Config", "Ops", "depends", "@Bean")
    edge(g, "Svc", "Ops", "depends", "delegates")

    legend(g, [
        (PALETTE["service"][0], "Business logic (CartOperations)"),
        (PALETTE["adapter"][0], "In-memory state (CartStore)"),
        (PALETTE["domain"][0], "Framework-free domain / wire records"),
        (PALETTE["client"][0], "Reused client SDK (catalog-service-client)"),
        (PALETTE["config"][0], "Host wiring / consumer (petstore-app-v1)"),
        (PALETTE["external"][0], "Owned by another module"),
    ])
    render(g, "cart-lib_class")


# ─────────────────────────────────────────────────────────────────────────────
#  (b) DATA-MODEL / WIRE-CONTRACT DIAGRAM (no database)
# ─────────────────────────────────────────────────────────────────────────────
def build_schema_diagram():
    g = new_graph("cart-lib — In-memory data model & wire contract (NO database)", rankdir="LR")

    # In-memory store structures (owned by this module)
    def _mem(s):
        s.node("StoreMap", table_node("CartStore.carts (ConcurrentHashMap)", [
            ("cartId", "String  (host cookie id)", "pk"),
            ("value", "CartEntry", "fk"),
        ], kind="owned"))
        s.node("EntryTbl", table_node("CartEntry  (one live cart)", [
            ("quantities", "LinkedHashMap<String,Integer>", "pk"),
            ("lastAccess", "Instant (volatile)", ""),
        ], kind="owned"))
        s.node("QtyTbl", table_node("quantities entry  (a raw cart line)", [
            ("itemId", "String", "pk"),
            ("qty", "int  (1..999; 0/neg -> removed)", ""),
        ], kind="owned"))

    # Wire contract (DTOs returned to the host)
    def _wire(s):
        s.node("ViewTbl", table_node("CartView  (resolved cart — record)", [
            ("items", "List<CartItemView>", ""),
            ("subTotal", "double  = Σ(unitCost*qty)", ""),
            ("count", "int  = distinct raw lines", ""),
        ], kind="owned"))
        s.node("LineTbl", table_node("CartItemView  (resolved line — record)", [
            ("itemId", "String", "pk"),
            ("productId", "String", ""),
            ("category", "String", ""),
            ("productName", "String", ""),
            ("attribute", "String", ""),
            ("quantity", "int", ""),
            ("unitCost", "double  = catalog listPrice", ""),
        ], kind="owned"))

    # Resolved-from source (owned elsewhere)
    def _ext(s):
        s.node("ItemSrc", table_node("ItemDto  (from catalog-service-client)", [
            ("itemId", "String", "pk"),
            ("listPrice", "double  -> unitCost", ""),
            ("productName", "String", ""),
            ("category / attribute1", "String", ""),
        ], kind="external"))

    cluster(g, "mem", "In-memory state (CartStore) — per-shopper, sliding 15-min TTL", _mem, "#EAF7EE")
    cluster(g, "wire", "Wire contract (CartDtos) — returned to host, JSON records", _wire, "#EAF2FB")
    cluster(g, "ext", "Resolved on demand (owned by catalog-service)", _ext, "#F5F5F5")

    edge(g, "StoreMap", "EntryTbl", "fk", "cartId ->")
    edge(g, "EntryTbl", "QtyTbl", "fk", "0..n lines")
    edge(g, "QtyTbl", "LineTbl", "flow", "view(): resolve + skip dangling")
    edge(g, "QtyTbl", "ItemSrc", "flow", "getItem(itemId, en_US)")
    edge(g, "ItemSrc", "LineTbl", "flow", "listPrice -> unitCost")
    edge(g, "LineTbl", "ViewTbl", "fk", "items[]")

    # Invariant note node
    inv = ('<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="4">'
           '<TR><TD BGCOLOR="#8A6D00"><FONT COLOR="white" POINT-SIZE="10"><B>'
           'Invariants (pinned by tests)</B></FONT></TD></TR>'
           '<TR><TD BGCOLOR="#FFFDF0" ALIGN="LEFT"><FONT POINT-SIZE="9">'
           "&#8226; qty &lt;= 0  &#8594; line SILENTLY removed<BR ALIGN='LEFT'/>"
           "&#8226; addItem(null qty) RESETS qty = 1 (no increment)<BR ALIGN='LEFT'/>"
           "&#8226; positive qty CAPPED at MAX_QUANTITY = 999<BR ALIGN='LEFT'/>"
           "&#8226; count = distinct raw lines (dangling id still counts)<BR ALIGN='LEFT'/>"
           "&#8226; view() SKIPS items missing from catalog (no error)<BR ALIGN='LEFT'/>"
           "&#8226; idle &gt; TTL &#8594; evicted (= HttpSession timeout)<BR ALIGN='LEFT'/>"
           "</FONT></TD></TR></TABLE>>")
    g.node("__inv__", label=inv, shape="plaintext")

    legend(g, [
        (TABLE_KIND["owned"][0], "In-memory / wire model owned by cart-lib"),
        (TABLE_KIND["external"][0], "Data owned by another module (catalog)"),
    ])
    render(g, "cart-lib_schema")


if __name__ == "__main__":
    build_class_diagram()
    build_schema_diagram()
    print("wrote cart-lib_class.{png,svg} and cart-lib_schema.{png,svg}")
