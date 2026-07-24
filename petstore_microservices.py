#!/usr/bin/env python3
"""
Pet Store — target microservices decomposition (traffic/caching driven).
Plan-only diagram: 5 services, database-per-service, JWT auth, JMS backbone.
Output: petstore_microservices.png / .svg
"""
import graphviz

C_CUST=("#E9E1F5","#6B4FA0"); C_CAT=("#DCE9F7","#2E6DB4"); C_CART=("#FBE7D0","#C97C2F")
C_INV=("#F7D9DC","#B23A48"); C_ORD=("#D8F0DD","#2F8F46"); C_DB=("#FFF3CD","#B8860B")
C_GW=("#EDEDED","#555555")

g=graphviz.Digraph("ms",format="png")
g.attr(rankdir="LR",splines="spline",nodesep="0.4",ranksep="1.1",bgcolor="white",
       fontname="Helvetica",fontsize="15",labelloc="t",
       label="Java Pet Store — Target Microservices (traffic-driven split)\\l"
             "DB-per-service · JWT auth · external JMS broker · solid=HTTP/REST · dashed red=JMS\\l")
g.attr("node",fontname="Helvetica")

def svc(nid,title,eps,c,scale):
    f,b=c
    ep="<BR ALIGN='LEFT'/>".join(eps)
    g.node(nid,f'<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="6">'
        f'<TR><TD BGCOLOR="{b}"><FONT COLOR="white" POINT-SIZE="13"><B>{title}</B></FONT></TD></TR>'
        f'<TR><TD BGCOLOR="{f}" ALIGN="LEFT"><FONT POINT-SIZE="9">{ep}<BR ALIGN="LEFT"/></FONT></TD></TR>'
        f'<TR><TD BGCOLOR="{f}"><FONT POINT-SIZE="8"><I>{scale}</I></FONT></TD></TR>'
        f'</TABLE>>',shape="plaintext")

def db(nid,label):
    g.node(nid,label,shape="cylinder",style="filled",fillcolor=C_DB[0],color=C_DB[1],
           fontsize="8",fontcolor=C_DB[1])

# actors + gateway
g.node("client","Browser /\\nAPI client",shape="oval",style="filled",fillcolor="#fff",color="#333")
g.node("gw","API Gateway\\n(routing + JWT verify)",shape="box",style="filled,rounded",
       fillcolor=C_GW[0],color=C_GW[1],fontsize="11")

svc("customer","1 · customer-service",
    ["POST /register","POST /login → JWT","POST /logout","GET /customer/{id}",
     "PUT /customer/{id}/account|profile|card"],C_CUST,"low-med · PII+card · issues JWT")
svc("catalog","2 · catalog-service",
    ["GET /  /category  /product","GET /item","GET /search"],C_CAT,
    "~80% traffic · READ-ONLY · cache/CDN · replicas")
svc("cart","3 · cart-service (+ checkout)",
    ["GET /cart","POST /cart/set|add|update|delete","POST /checkout","GET /orders/{id}/status"],
    C_CART,"med · session (Redis) · sticky")
svc("inv","4 · inventory/admin-service",
    ["GET /admin/orders","POST /admin/orders/{id}/approve|deny","(inventory reserve, pessimistic lock)"],
    C_INV,"low web · back-office")
svc("order","5 · order-processing-service",
    ["@JmsListener PurchaseOrderQueue","(no HTTP) fulfilment workflow","InvoiceTopic (pub/sub)"],
    C_ORD,"async worker · scale by queue depth")

db("db_c","customer db\\napp_user, customer")
db("db_cat","catalog db\\ncategory/product/item")
db("db_o","order db\\npurchase_order, line_item, status")
db("db_i","inventory db\\ninventory")

# client → gateway → services (HTTP)
g.attr("edge",color="#333",penwidth="1.4")
g.edge("client","gw","HTTPS")
for s,lbl in [("customer","/register /login"),("catalog","/ /category /search"),
              ("cart","/cart /checkout"),("inv","/admin")]:
    g.edge("gw",s,label=lbl,fontsize="8")

# DB-per-service
g.attr("edge",color=C_DB[1],penwidth="1.2",fontsize="8")
g.edge("customer","db_c"); g.edge("catalog","db_cat")
g.edge("cart","db_o",label="writes order at checkout")
g.edge("order","db_o",label="updates status"); g.edge("inv","db_i")
g.edge("inv","db_o",label="approve/deny status")

# inter-service REST (dashed grey)
g.attr("edge",color="#6B4FA0",style="dashed",penwidth="1.3",fontcolor="#6B4FA0",fontsize="8")
g.edge("cart","catalog",label="REST: resolve item price/name",constraint="false")

# JMS (dashed red)
g.attr("edge",color="#B23A48",style="dashed",penwidth="2",fontcolor="#B23A48",fontsize="9")
g.edge("cart","order",label="JMS PurchaseOrderQueue (PO)",constraint="false")
g.edge("order","inv",label="JMS reserve stock",constraint="false")
g.edge("inv","order",label="JMS InvoiceTopic",constraint="false")

out="/Users/vishakvj/Downloads/pet-project/petstore_microservices"
g.render(out,format="png",cleanup=True); g.render(out,format="svg",cleanup=True)
print("wrote",out+".png")
