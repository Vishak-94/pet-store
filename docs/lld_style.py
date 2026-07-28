#!/usr/bin/env python3
"""
Shared Low-Level-Design diagram style library for the Java Pet Store migration.

Every per-module diagram generator (``<module>/docs/<module>_lld.py``) imports this
module so that all packages render in ONE consistent, neat house style — a UML-ish
class diagram and an ER/schema diagram built on graphviz HTML-like table nodes.

Why a shared library (and not copy-paste per module)?
  * Visual consistency across all 10 modules for free.
  * Single place to tune palette / fonts / rendering.
  * It is itself a live example of the reusability the LLDs describe.

Public API
----------
    g = new_graph(title)                       -> a configured Digraph
    cluster(g, key, label, nodes, color)       -> a layer/bounded-context box
    uml_class(name, stereo, attrs, methods, kind, note)  -> HTML label str
    table_node(name, cols, kind)               -> HTML label str for a DB table
    edge(g, a, b, kind)                         -> a styled relationship edge
    legend(g, entries)                          -> a legend box
    render(g, out_basename)                     -> writes <base>.png and <base>.svg

`kind` vocabulary (drives colour): 'web', 'service', 'domain', 'port',
'adapter', 'entity', 'client', 'config', 'messaging', 'framework', 'external'.
Table `kind`: 'owned' (this module owns it) / 'external' (read elsewhere).

Requires: python `graphviz` package + the graphviz `dot` binary on PATH.
"""

import html
import graphviz

# ── Palette (colour-blind friendly, light, high-contrast text) ────────────────
PALETTE = {
    "web":        ("#DCE9F7", "#2E6DB4"),   # controllers / REST / MVC — blue
    "service":    ("#D6ECE4", "#2E8B74"),   # application/business logic — teal
    "domain":     ("#E9E1F5", "#6B4FA0"),   # framework-free domain VOs — purple
    "port":       ("#FFF3CE", "#C9A227"),   # ports / interfaces (SPI seams) — gold
    "adapter":    ("#FBE7D0", "#C97C2F"),   # persistence / infra adapters — amber
    "entity":     ("#F7D9DC", "#B23A48"),   # JPA entities / documents — red
    "client":     ("#E2EEF9", "#4A7FB5"),   # client SDK (RestClient) — soft blue
    "config":     ("#EDEDED", "#666666"),   # config / security / wiring — grey
    "messaging":  ("#DDEFE0", "#3E9B54"),   # JMS listeners / gateways — green
    "framework":  ("#F0F0F0", "#888888"),   # Spring / external framework — light grey
    "external":   ("#F5F5F5", "#AAAAAA"),   # things owned by another module — pale
}
TABLE_KIND = {
    "owned":    ("#D8F0DD", "#2F8F46"),     # table this module owns — green
    "external": ("#F5F5F5", "#AAAAAA"),     # table owned elsewhere — pale grey
}

FONT = "Helvetica"


def _esc(s):
    return html.escape(str(s), quote=True)


def new_graph(title, rankdir="TB"):
    """A configured top-to-bottom Digraph with a title caption."""
    g = graphviz.Digraph("lld", format="png")
    g.attr(
        rankdir=rankdir,
        labelloc="t",
        label=f"\n{title}",
        fontname=f"{FONT}-Bold",
        fontsize="22",
        bgcolor="white",
        nodesep="0.35",
        ranksep="0.6",
        pad="0.4",
        splines="spline",
    )
    g.attr("node", shape="plaintext", fontname=FONT)
    g.attr("edge", fontname=FONT, fontsize="9", color="#555555")
    return g


def uml_class(name, stereotype="", attrs=None, methods=None, kind="service", note=None):
    """Build an HTML-like UML class node label: title / stereotype / attrs / methods."""
    fill, border = PALETTE.get(kind, PALETTE["service"])
    rows = [
        f'<TR><TD BGCOLOR="{border}" ALIGN="CENTER">'
        f'<FONT COLOR="white" POINT-SIZE="13"><B>{_esc(name)}</B></FONT></TD></TR>'
    ]
    if stereotype:
        rows.append(
            f'<TR><TD BGCOLOR="{fill}" ALIGN="CENTER">'
            f'<FONT POINT-SIZE="9"><I>&#171;{_esc(stereotype)}&#187;</I></FONT></TD></TR>'
        )
    if attrs:
        body = "<BR ALIGN='LEFT'/>".join(f"- {_esc(a)}" for a in attrs)
        rows.append(
            f'<TR><TD BGCOLOR="#FCFCFC" ALIGN="LEFT"><FONT POINT-SIZE="9" COLOR="#333333">'
            f"{body}<BR ALIGN='LEFT'/></FONT></TD></TR>"
        )
    if methods:
        body = "<BR ALIGN='LEFT'/>".join(f"+ {_esc(m)}" for m in methods)
        rows.append(
            f'<TR><TD BGCOLOR="white" ALIGN="LEFT"><FONT POINT-SIZE="9">'
            f"{body}<BR ALIGN='LEFT'/></FONT></TD></TR>"
        )
    if note:
        rows.append(
            f'<TR><TD BGCOLOR="#FFFDF0" ALIGN="LEFT"><FONT POINT-SIZE="8" COLOR="#8A6D00">'
            f"{_esc(note)}</FONT></TD></TR>"
        )
    return (
        '<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="5">'
        + "".join(rows)
        + "</TABLE>>"
    )


def table_node(name, cols, kind="owned"):
    """HTML label for a DB table / collection. cols = list of (col, type, tag).

    tag in {'pk','fk','pk,fk',''} decorates the key column.
    """
    fill, border = TABLE_KIND.get(kind, TABLE_KIND["owned"])
    rows = [
        f'<TR><TD BGCOLOR="{border}" ALIGN="CENTER" COLSPAN="2">'
        f'<FONT COLOR="white" POINT-SIZE="12"><B>{_esc(name)}</B></FONT></TD></TR>'
    ]
    for col, typ, tag in cols:
        key = ""
        if "pk" in tag:
            key += "&#128273; "  # key emoji
        if "fk" in tag:
            key += "&#128279; "  # link emoji
        rows.append(
            f'<TR><TD BGCOLOR="{fill}" ALIGN="LEFT"><FONT POINT-SIZE="9">{key}<B>{_esc(col)}</B></FONT></TD>'
            f'<TD BGCOLOR="white" ALIGN="LEFT"><FONT POINT-SIZE="8" COLOR="#555555">{_esc(typ)}</FONT></TD></TR>'
        )
    return (
        '<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="4">'
        + "".join(rows)
        + "</TABLE>>"
    )


def cluster(g, key, label, build, color="#F7F9FB", border="#BBBBBB"):
    """Create a subgraph cluster (a layer / bounded-context box). `build(sub)` adds nodes."""
    with g.subgraph(name=f"cluster_{key}") as sub:
        sub.attr(
            label=label,
            style="rounded,filled",
            color=border,
            fillcolor=color,
            fontname=f"{FONT}-Bold",
            fontsize="13",
            labeljust="l",
            margin="12",
        )
        build(sub)


# Relationship styles: (arrowhead, style, extra label)
_EDGE = {
    "impl":     dict(arrowhead="empty", style="dashed"),   # realizes interface (..|>)
    "extends":  dict(arrowhead="empty", style="solid"),    # inheritance
    "depends":  dict(arrowhead="vee",   style="solid"),    # uses / depends-on (-->)
    "compose":  dict(arrowhead="diamond", style="solid"),  # composition
    "flow":     dict(arrowhead="vee",   style="dashed"),   # data / call flow
    "async":    dict(arrowhead="vee",   style="dotted", color="#3E9B54"),  # JMS async
    "fk":       dict(arrowhead="crow",  style="solid", color="#2F8F46"),   # FK reference
}


def edge(g, a, b, kind="depends", label=None):
    style = dict(_EDGE.get(kind, _EDGE["depends"]))
    if label:
        style["label"] = label
    g.edge(a, b, **style)


def legend(g, entries):
    """entries = list of (swatch_color, text). Renders a small legend box."""
    rows = "".join(
        f'<TR><TD BGCOLOR="{c}" WIDTH="18"></TD>'
        f'<TD ALIGN="LEFT"><FONT POINT-SIZE="9">{_esc(t)}</FONT></TD></TR>'
        for c, t in entries
    )
    lbl = ('<<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="3">'
           f'<TR><TD COLSPAN="2" BGCOLOR="#333333"><FONT COLOR="white" POINT-SIZE="10"><B>Legend</B></FONT></TD></TR>'
           + rows + "</TABLE>>")
    g.node("__legend__", label=lbl, shape="plaintext")


def render(g, out_basename):
    """Write <out_basename>.png and <out_basename>.svg. Returns (png, svg) paths."""
    png = g.render(filename=out_basename, format="png", cleanup=True)
    svg = g.render(filename=out_basename, format="svg", cleanup=True)
    return png, svg


if __name__ == "__main__":
    # Smoke test: a tiny hexagonal slice rendered to /tmp.
    g = new_graph("lld_style smoke test")

    def _web(s):
        s.node("Ctl", uml_class("DemoController", "RestController",
                                methods=["get(id)"], kind="web"))

    def _svc(s):
        s.node("Svc", uml_class("DemoService", "Service",
                                methods=["find(id)"], kind="service"))

    def _port(s):
        s.node("Port", uml_class("DemoPort", "interface",
                                 methods=["load(id)"], kind="port"))
        s.node("Adp", uml_class("JpaAdapter", "adapter",
                                methods=["load(id)"], kind="adapter"))

    cluster(g, "w", "Web", _web, "#EAF2FB")
    cluster(g, "s", "Service", _svc, "#E7F4EF")
    cluster(g, "p", "Ports & Adapters", _port, "#FFF9E6")
    edge(g, "Ctl", "Svc", "depends")
    edge(g, "Svc", "Port", "depends")
    edge(g, "Adp", "Port", "impl")
    legend(g, [(PALETTE["web"][0], "Web"), (PALETTE["service"][0], "Service"),
               (PALETTE["port"][0], "Port"), (PALETTE["adapter"][0], "Adapter")])
    png, svg = render(g, "/tmp/lld_style_smoke")
    print("wrote", png, svg)
