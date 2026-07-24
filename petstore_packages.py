#!/usr/bin/env python3
"""
Java Pet Store — package / client-SDK dependency graph (as-built).
Shows every app, the client SDK it PUBLISHES, the shared libraries, and every
Maven import edge (who depends on whose client/library).
Output: petstore_packages.png / .svg

Edges:
  blue  = imports a client SDK (calls that service's API)
  grey  = imports a shared library (in-process)
  green = a service PUBLISHES this client SDK (its own contract)
"""
import graphviz

APP = ("#DCE9F7", "#2E6DB4")     # runnable service
SDK = ("#D8F0DD", "#2F8F46")     # client SDK artifact
LIBP = ("#EDEDED", "#555555")    # shared library

g = graphviz.Digraph("pkg", format="png")
g.attr(rankdir="LR", splines="spline", nodesep="0.35", ranksep="1.3", bgcolor="white",
       fontname="Helvetica", fontsize="15", labelloc="t",
       label="Java Pet Store — Package & Client-SDK Dependencies (Maven, as-built)\\l"
             "green = service publishes this SDK · blue = imports a client SDK (HTTP) · grey = imports a shared library (in-process)\\l")
g.attr("node", fontname="Helvetica")


def node(nid, title, sub, colors=APP, shape="box"):
    f, b = colors
    g.node(nid,
        f'<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="5">'
        f'<TR><TD BGCOLOR="{b}"><FONT COLOR="white" POINT-SIZE="12"><B>{title}</B></FONT></TD></TR>'
        f'<TR><TD BGCOLOR="{f}"><FONT POINT-SIZE="8">{sub}</FONT></TD></TR></TABLE>>',
        shape="plaintext")


# ── runnable apps (Maven artifactId) ────────────────────────────────────
node("store", "petstore-app-v1", ":8080 storefront")
node("auth", "auth-service", ":8086 IdP")
node("customer", "customer-service", ":8081")
node("catalog", "catalog-service", ":8083")
node("opc", "order-processing-service", ":8088 OPC")
node("admin", "admin-office-service", ":8082 console")
node("inventory", "inventory-service", ":8085")
node("notify", "notification-service", ":8087")

# ── published client SDKs (artifacts) ───────────────────────────────────
node("authsdk", "auth-client", "RS256 verify + login", SDK)
node("custsdk", "customer-service-client", "customer API + DTOs", SDK)
node("catsdk", "catalog-service-client", "catalog API + DTOs", SDK)
node("opcsdk", "order-processing-client", "OPC facade + DTOs", SDK)

# ── shared libraries (in-process, not a remote API) ─────────────────────
node("cartlib", "cart-lib", "in-process cart", LIBP)
node("msg", "petstore-messaging", "JMS destinations + events", LIBP)

# ── PUBLISHES edges (green): service → its own SDK ──────────────────────
def publishes(app, sdk):
    g.edge(app, sdk, label="publishes", color="#2F8F46", fontsize="8",
           fontcolor="#2F8F46", style="bold", arrowhead="odot")

publishes("auth", "authsdk")
publishes("customer", "custsdk")
publishes("catalog", "catsdk")
publishes("opc", "opcsdk")

# ── IMPORTS a client SDK (blue): app → sdk it consumes ──────────────────
def imports_sdk(app, sdk):
    g.edge(app, sdk, color="#2E6DB4", fontsize="8", style="solid")

imports_sdk("store", "custsdk")
imports_sdk("store", "catsdk")
imports_sdk("store", "authsdk")
imports_sdk("customer", "authsdk")
imports_sdk("admin", "opcsdk")
imports_sdk("admin", "authsdk")
imports_sdk("inventory", "authsdk")
imports_sdk("opc", "authsdk")
imports_sdk("cartlib", "catsdk")     # cart-lib resolves items via catalog SDK

# ── IMPORTS a shared library (grey): consumer → library ─────────────────
def imports_lib(app, libn):
    g.edge(app, libn, color="#888888", fontsize="8", style="dashed")

imports_lib("store", "cartlib")
imports_lib("store", "msg")
imports_lib("opc", "msg")
imports_lib("inventory", "msg")
imports_lib("notify", "msg")

g.render("/Users/vishakvj/Downloads/pet-project/petstore_packages", cleanup=True)
print("Wrote petstore_packages.png")
