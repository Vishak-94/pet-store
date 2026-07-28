#!/usr/bin/env python3
"""
Low-Level-Design diagram generator for **catalog-service**.

Renders two diagrams into this docs/ folder using the shared house-style library
(../../docs/lld_style.py):

  * catalog-service_class.png/.svg  — UML class diagram. Shows the strict
    hexagonal layering (Web -> Service -> two PORTS -> two @Profile ADAPTERS),
    the Interface-Segregation split (CatalogRepository browse port +
    CatalogSearchPort search port), the reusable client SDK, and the
    framework-free domain. The port/adapter seam and the profile-swap
    extensibility (one interface, two adapters) are the visual centrepiece.

  * catalog-service_schema.png/.svg — the two persistence data models side by
    side: the locale-split H2 ER model (base + _details tables, PK/FK) that the
    JPA adapter maps, and the MongoDB Option-C document model (3 collections,
    embedded per-locale details map) that the Mongo adapter maps. Both sit
    behind the same port.

Every class, method, field, table, column, collection and field name below is
extracted from the real module source — nothing invented.
"""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs"))
from lld_style import new_graph, uml_class, table_node, cluster, edge, legend, render, PALETTE, TABLE_KIND


# ─────────────────────────────────────────────────────────────────────────────
# (a) CLASS DIAGRAM
# ─────────────────────────────────────────────────────────────────────────────
def build_class_diagram():
    g = new_graph("catalog-service — class diagram (hexagonal: 2 ports, 2 @Profile adapters)", rankdir="TB")

    # ---- Web layer ----
    def web(s):
        s.node("Ctl", uml_class(
            "CatalogApiController", "RestController",
            methods=[
                "categories(start,count,lang) CategoryPage",
                "category(id,lang) ResponseEntity<CategoryDto>",
                "productsInCategory(id,...) ProductPage",
                "product(id,lang) ResponseEntity<ProductDto>",
                "itemsInProduct(id,...) ItemPage",
                "item(id,lang) ResponseEntity<ItemDto>",
                "search(keyword,...) ItemPage",
                "- locale(String) Locale",
            ],
            kind="web",
            note="Maps CatalogServiceEndpoints path constants; miss -> 404 / 200-empty"))

    # ---- Application service ----
    def service(s):
        s.node("Svc", uml_class(
            "CatalogService", "Service",
            attrs=["repository : CatalogRepository", "search : CatalogSearchPort"],
            methods=[
                "getCategory(id,locale) Optional<Category>",
                "getCategories(start,count,locale) Page",
                "getProduct(id,locale) Optional<Product>",
                "getProducts(catId,start,count,locale) Page",
                "getItem(id,locale) Optional<Item>",
                "getItems(prodId,start,size,locale) Page",
                "searchItems(query,start,size,locale) Page",
            ],
            kind="service",
            note="Pass-through over the ports (DIP); no business logic — replaces CatalogEJB"))

    # ---- Ports (ISP seam) ----
    def ports(s):
        s.node("PortBrowse", uml_class(
            "CatalogRepository", "interface / port",
            methods=[
                "getCategory(id,locale) Optional<Category>",
                "getCategories(start,count,locale) Page",
                "getProduct(id,locale) Optional<Product>",
                "getProducts(catId,start,count,locale) Page",
                "getItem(id,locale) Optional<Item>",
                "getItems(prodId,start,size,locale) Page",
            ],
            kind="port",
            note="Browse reads only (ISP)"))
        s.node("PortSearch", uml_class(
            "CatalogSearchPort", "interface / port",
            methods=["searchItems(query,start,size,locale) Page"],
            kind="port",
            note="Segregated search port — swap in a search engine w/o touching browse"))

    # ---- Domain (framework-free) ----
    def domain(s):
        s.node("Category", uml_class(
            "Category", "value object",
            attrs=["id", "name", "description"], kind="domain"))
        s.node("Product", uml_class(
            "Product", "value object",
            attrs=["id", "name", "description"], kind="domain"))
        s.node("Item", uml_class(
            "Item", "value object",
            attrs=["category", "productId", "productName",
                   "attribute1..attribute5", "itemId", "description",
                   "listPrice:double", "unitCost:double", "imageLocation"],
            methods=["getAttribute()", "getListCost()"],
            kind="domain",
            note="Legacy accessor quirks preserved"))
        s.node("Page", uml_class(
            "Page", "value object",
            attrs=["EMPTY_PAGE : Page (static)", "objects:List", "start:int", "hasNextPage:boolean"],
            methods=["getList()", "isNextPageAvailable()", "isPreviousPageAvailable()",
                     "getStartOfNextPage()", "getSize()"],
            kind="domain"))

    # ---- JPA adapter (@Profile("!mongo")) ----
    def jpa(s):
        s.node("Jpa", uml_class(
            "JpaCatalogRepository", 'adapter @Profile("!mongo")',
            methods=["+ all 6 browse ops + searchItems",
                     "- lang(Locale) String",
                     "- toItem(ItemDetailEntity,Locale) Item"],
            kind="adapter",
            note="DEFAULT adapter — implements BOTH ports over H2/JPA"))
        s.node("SdCat", uml_class(
            "CategoryDetailRepository", "Spring Data JPA",
            methods=["findByCatidAndLocale", "findByLocaleOrderByName", "countByLocale"],
            kind="adapter"))
        s.node("SdProdB", uml_class(
            "ProductBaseRepository", "Spring Data JPA",
            methods=["findByCatid(catid)"], kind="adapter"))
        s.node("SdProdD", uml_class(
            "ProductDetailRepository", "Spring Data JPA",
            methods=["findByProductidAndLocale", "findByCategory(...) Slice"], kind="adapter"))
        s.node("SdItemB", uml_class(
            "ItemBaseRepository", "Spring Data JPA",
            methods=["findByProductid(productid)"], kind="adapter"))
        s.node("SdItemD", uml_class(
            "ItemDetailRepository", "Spring Data JPA + search",
            methods=["findByItemidAndLocale", "findByProduct(...) Slice"], kind="adapter"))
        s.node("SearchPortI", uml_class(
            "ItemSearchRepository", "interface (custom fragment)",
            methods=["search(tokens,locale,offset,limit) List"], kind="port"))
        s.node("SearchImpl", uml_class(
            "ItemSearchRepositoryImpl", "impl",
            attrs=["entityManager : EntityManager"],
            methods=["search(...) — runtime-assembled JPQL OR-per-token"],
            kind="adapter",
            note="Dynamic token count -> StringBuilder JPQL; attrs NOT searched"))

    # ---- JPA entities (locale-split) ----
    def entities(s):
        s.node("ECatD", uml_class(
            "CategoryDetailEntity", "@Entity category_details",
            attrs=["catid (PK)", "locale (PK)", "name", "descn", "image"], kind="entity"))
        s.node("EProdB", uml_class(
            "ProductBaseEntity", "@Entity product",
            attrs=["productid (PK)", "catid"], kind="entity"))
        s.node("EProdD", uml_class(
            "ProductDetailEntity", "@Entity product_details",
            attrs=["productid (PK)", "locale (PK)", "name", "descn", "image"], kind="entity"))
        s.node("EItemB", uml_class(
            "ItemBaseEntity", "@Entity item",
            attrs=["itemid (PK)", "productid"], kind="entity"))
        s.node("EItemD", uml_class(
            "ItemDetailEntity", "@Entity item_details",
            attrs=["itemid (PK)", "locale (PK)", "listprice", "unitcost",
                   "descn", "attr1..attr5", "image"], kind="entity"))

    # ---- Mongo adapter (@Profile("mongo")) ----
    def mongo(s):
        s.node("Mongo", uml_class(
            "MongoCatalogRepository", 'adapter @Profile("mongo")',
            attrs=["mongo : MongoTemplate"],
            methods=["+ all 6 browse ops + searchItems",
                     "- lang(Locale) / detailsPath(locale)",
                     "- toItem(ItemDocument,localeKey) Item"],
            kind="adapter",
            note="OPT-IN adapter — implements BOTH ports over MongoDB"))
        s.node("Seeder", uml_class(
            "MongoCatalogSeeder", '@Component @Profile("mongo")',
            methods=["seedIfEmpty() @EventListener(ApplicationReadyEvent)"],
            kind="adapter",
            note="data.sql equivalent; idempotent 3-locale seed"))
        s.node("MSchema", uml_class(
            "MongoSchema", "constants",
            attrs=["CATEGORIES/PRODUCTS/ITEMS", "F_CAT_ID/F_PRODUCT_ID", "F_CATEGORY_ID/F_DETAILS"],
            kind="config"))

    # ---- Client SDK (catalog-service-client jar) ----
    def client(s):
        s.node("Client", uml_class(
            "CatalogServiceClient", "client SDK (RestClient)",
            attrs=["http : RestClient", "CONNECT_TIMEOUT=2s", "READ_TIMEOUT=5s"],
            methods=["getCategory / getCategories", "getProduct / getProducts",
                     "getItem / getItems", "searchItems",
                     "(404 -> Optional.empty; null body -> empty page)"],
            kind="client",
            note="Reused by storefront & other callers — no URL/JSON leaks"))
        s.node("Endpoints", uml_class(
            "CatalogServiceEndpoints", "path constants",
            attrs=["DEFAULT_BASE_URL=:8083", "CATEGORIES / CATEGORY_BY_ID",
                   "PRODUCTS_IN_CATEGORY / PRODUCT_BY_ID",
                   "ITEMS_IN_PRODUCT / ITEM_BY_ID / ITEMS_SEARCH",
                   "PARAM_START/COUNT/LANG/KEYWORD"],
            kind="client",
            note="Single-sourced contract — server @GetMapping reuses these"))
        s.node("Dtos", uml_class(
            "CatalogDtos", "records (wire contract)",
            attrs=["CategoryDto / ProductDto / ItemDto",
                   "CategoryPage / ProductPage / ItemPage",
                   "(list, start, nextPageAvailable)"],
            kind="client",
            note="Additive-safe: add a field w/o breaking callers"))

    cluster(g, "web", "Web layer", web, "#EAF2FB")
    cluster(g, "svc", "Application / Service", service, "#E7F4EF")
    cluster(g, "port", "Ports (hexagon seam · Interface Segregation)", ports, "#FFF9E6", "#C9A227")
    cluster(g, "dom", "Domain (framework-free value objects)", domain, "#F1EAFA")
    cluster(g, "jpa", 'JPA adapter — @Profile("!mongo") · DEFAULT', jpa, "#FBEFE2", "#C97C2F")
    cluster(g, "ent", "JPA entities (locale-split: base + _details)", entities, "#FBE1E4")
    cluster(g, "mongo", 'MongoDB adapter — @Profile("mongo") · OPT-IN', mongo, "#FBEFE2", "#C97C2F")
    cluster(g, "cli", "Client SDK  (catalog-service-client jar — reused by callers)", client, "#EAF3FC")

    # ---- relationships ----
    edge(g, "Ctl", "Svc", "depends")
    edge(g, "Ctl", "Dtos", "flow", "maps domain->DTO")
    edge(g, "Ctl", "Endpoints", "flow", "@GetMapping paths")
    edge(g, "Svc", "PortBrowse", "depends", "DIP")
    edge(g, "Svc", "PortSearch", "depends", "DIP")

    # both adapters realize both ports (the profile-swap story)
    edge(g, "Jpa", "PortBrowse", "impl")
    edge(g, "Jpa", "PortSearch", "impl")
    edge(g, "Mongo", "PortBrowse", "impl")
    edge(g, "Mongo", "PortSearch", "impl")

    # JPA adapter wiring
    for sd in ["SdCat", "SdProdB", "SdProdD", "SdItemB", "SdItemD"]:
        edge(g, "Jpa", sd, "depends")
    edge(g, "SdItemD", "SearchPortI", "extends", "mixes in")
    edge(g, "SearchImpl", "SearchPortI", "impl")
    edge(g, "SdCat", "ECatD", "depends")
    edge(g, "SdProdB", "EProdB", "depends")
    edge(g, "SdProdD", "EProdD", "depends")
    edge(g, "SdItemB", "EItemB", "depends")
    edge(g, "SdItemD", "EItemD", "depends")

    # Mongo adapter wiring
    edge(g, "Mongo", "MSchema", "depends")
    edge(g, "Seeder", "MSchema", "depends")

    # adapters build domain objects
    edge(g, "Jpa", "Item", "flow", "builds")
    edge(g, "Mongo", "Item", "flow", "builds")

    # client SDK internal
    edge(g, "Client", "Endpoints", "depends")
    edge(g, "Client", "Dtos", "depends")

    legend(g, [
        (PALETTE["web"][0], "Web / REST"),
        (PALETTE["service"][0], "Application service"),
        (PALETTE["port"][0], "Port (interface / SPI seam)"),
        (PALETTE["domain"][0], "Domain value object"),
        (PALETTE["adapter"][0], "Adapter (@Profile swap)"),
        (PALETTE["entity"][0], "JPA entity"),
        (PALETTE["client"][0], "Client SDK (reused)"),
        (PALETTE["config"][0], "Config / constants"),
        ("#FFFFFF", "──▷ realizes (impl)   ──▶ depends   ⇢ flow"),
    ])

    render(g, "catalog-service_class")


# ─────────────────────────────────────────────────────────────────────────────
# (b) SCHEMA / DATA-MODEL DIAGRAM (H2 locale-split ER  +  Mongo Option-C docs)
# ─────────────────────────────────────────────────────────────────────────────
def build_schema_diagram():
    g = new_graph("catalog-service — persistence models (both behind CatalogRepository port)", rankdir="LR")

    # ---- H2 relational (locale-split) — JPA adapter maps this ----
    def h2(s):
        s.node("category", table_node("category", [
            ("catid", "VARCHAR(10)", "pk"),
        ], "owned"))
        s.node("category_details", table_node("category_details", [
            ("catid", "VARCHAR(10)", "pk,fk"),
            ("locale", "VARCHAR(10)", "pk"),
            ("name", "VARCHAR(80)", ""),
            ("descn", "VARCHAR(255)", ""),
            ("image", "VARCHAR(255)", ""),
        ], "owned"))
        s.node("product", table_node("product", [
            ("productid", "VARCHAR(10)", "pk"),
            ("catid", "VARCHAR(10)", "fk"),
        ], "owned"))
        s.node("product_details", table_node("product_details", [
            ("productid", "VARCHAR(10)", "pk,fk"),
            ("locale", "VARCHAR(10)", "pk"),
            ("name", "VARCHAR(80)", ""),
            ("descn", "VARCHAR(255)", ""),
            ("image", "VARCHAR(255)", ""),
        ], "owned"))
        s.node("item", table_node("item", [
            ("itemid", "VARCHAR(10)", "pk"),
            ("productid", "VARCHAR(10)", "fk"),
        ], "owned"))
        s.node("item_details", table_node("item_details", [
            ("itemid", "VARCHAR(10)", "pk,fk"),
            ("locale", "VARCHAR(10)", "pk"),
            ("listprice", "DECIMAL(10,2)", ""),
            ("unitcost", "DECIMAL(10,2)", ""),
            ("descn", "VARCHAR(255)", ""),
            ("attr1..attr5", "VARCHAR(80)", ""),
            ("image", "VARCHAR(255)", ""),
        ], "owned"))

    # ---- MongoDB Option-C documents — Mongo adapter maps this ----
    def mongo(s):
        s.node("categories", table_node("categories  (collection)", [
            ("_id", "String (= catid)", "pk"),
            ("details", "Map<locale, LocalizedText>", ""),
            ("  .name / .descn / .image", "per-locale", ""),
        ], "owned"))
        s.node("products", table_node("products  (collection)", [
            ("_id", "String (= productid)", "pk"),
            ("catId", "String  @Indexed (->category)", "fk"),
            ("details", "Map<locale, LocalizedText>", ""),
            ("  .name / .descn / .image", "per-locale", ""),
        ], "owned"))
        s.node("items", table_node("items  (collection)", [
            ("_id", "String (= itemid)", "pk"),
            ("productId", "String  @Indexed (->product)", "fk"),
            ("categoryId", "String  denormalized", "fk"),
            ("details", "Map<locale, LocalizedItem>", ""),
            ("  .descn/.image/.listPrice/.unitCost", "per-locale", ""),
            ("  .productName  denormalized (search)", "per-locale", ""),
            ("  .attr1..attr5", "per-locale", ""),
        ], "owned"))

    cluster(g, "h2", 'H2 relational — locale-split ER  ·  JpaCatalogRepository @Profile("!mongo")',
            h2, "#EAF7EE", "#2F8F46")
    cluster(g, "mongo", 'MongoDB — Option C embedded docs  ·  MongoCatalogRepository @Profile("mongo")',
            mongo, "#EAF7EE", "#2F8F46")

    # FK edges (H2) — _details keyed by (id, locale) reference the base row
    edge(g, "category_details", "category", "fk", "catid")
    edge(g, "product", "category", "fk", "catid")
    edge(g, "product_details", "product", "fk", "productid")
    edge(g, "item", "product", "fk", "productid")
    edge(g, "item_details", "item", "fk", "itemid")

    # membership references (Mongo)
    edge(g, "products", "categories", "fk", "catId")
    edge(g, "items", "products", "fk", "productId")
    edge(g, "items", "categories", "fk", "categoryId (denorm)")

    legend(g, [
        (TABLE_KIND["owned"][0], "Table / collection owned by catalog-service"),
        ("#FFFFFF", "\U0001F511 primary key   \U0001F517 foreign key"),
        ("#FFFFFF", "H2: base + _details (id, locale)   Mongo: embedded details map"),
    ])

    render(g, "catalog-service_schema")


if __name__ == "__main__":
    build_class_diagram()
    build_schema_diagram()
    print("rendered catalog-service_class.{png,svg} and catalog-service_schema.{png,svg}")
