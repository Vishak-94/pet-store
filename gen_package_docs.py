#!/usr/bin/env python3
"""
Per-package migration-spec generator for Java Pet Store 1.3.1_02.

For every component and app package it statically analyses the Java source and
writes a MIGRATION.md documenting, per class:
  - stereotype (EJB session/entity bean, MDB, DAO, servlet, value object, ...)
  - business responsibility (heuristic from name + methods)
  - public API (method signatures)
  - dependencies (other blueprints packages it imports)
  - CMP table mapping (if the package's ejb-jar.xml declares CMP entities)

It also writes a per-package migration checklist (old pattern -> new pattern),
so a downstream agentic loop (see migrate.sh) has a concrete, verifiable spec
for each module.

Usage:  python3 gen_package_docs.py
Output: <src>/components/<pkg>/MIGRATION.md  and  <src>/apps/<pkg>/MIGRATION.md
        plus a top-level MIGRATION_INDEX.md
"""
import os
import re
import glob
from collections import defaultdict

SRC = "/Users/vishakvj/Downloads/pet-project/petstore1.3.1_02/src"
BP = "com.sun.j2ee.blueprints"

METHOD_RE = re.compile(
    r'public\s+(?:static\s+|final\s+|abstract\s+|synchronized\s+)*'
    r'([A-Za-z0-9_<>\[\],.\s]+?)\s+([a-zA-Z0-9_]+)\s*\(([^)]*)\)'
)
IMPORT_RE = re.compile(r'import\s+(com\.sun\.j2ee\.blueprints\.[a-zA-Z0-9_.]+)\s*;')
CLASSDECL_RE = re.compile(r'\b(?:public\s+)?(?:abstract\s+)?(class|interface)\s+([A-Za-z0-9_]+)')


def stereotype(classname, src, is_interface):
    """Infer the J2EE stereotype and the target Spring construct."""
    s = src
    if "MessageDrivenBean" in s or classname.endswith("MDB"):
        return ("Message-Driven Bean", "@Component + @KafkaListener / @JmsListener (or ApplicationEvent)")
    if "EntityBean" in s or ("EntityContext" in s and "ejbLoad" in s):
        return ("CMP Entity Bean", "JPA @Entity + Spring Data repository")
    if "SessionBean" in s or "SessionContext" in s:
        stateful = "StatefulSessionBean" in s or classname.lower().startswith("shoppingcart")
        return ("Stateful Session Bean" if stateful else "Session Bean",
                "@Service (@SessionScope if stateful)")
    if classname.endswith("DAO") and is_interface:
        return ("DAO interface (port)", "Spring Data repository interface")
    if "DAO" in classname:
        return ("DAO implementation (adapter)", "repository impl / JdbcTemplate adapter")
    if "Servlet" in s and "extends HttpServlet" in s:
        return ("Servlet", "Spring MVC @Controller")
    if is_interface and (classname.endswith("Local") or classname.endswith("LocalHome")
                         or classname.endswith("Home")):
        return ("EJB component interface", "(removed — replaced by direct bean injection)")
    if classname.endswith("Exception"):
        return ("Exception", "custom RuntimeException")
    if re.search(r'private\s+\w+\s+\w+;', s) and "get" in s and "set" in s:
        return ("Value object / model", "domain record / POJO")
    return ("Helper / utility", "@Component or plain class")


def responsibility(classname, methods):
    """One-line business responsibility heuristic."""
    verbs = {m[1] for m in methods}
    hints = []
    if any(v in verbs for v in ("authenticate", "createUser")):
        hints.append("authenticates users / manages credentials")
    if any(v in verbs for v in ("addItem", "deleteItem", "updateItemQuantity")):
        hints.append("manages shopping-cart contents")
    if any(v.startswith("get") and ("Categor" in v or "Product" in v or "Item" in v) for v in verbs):
        hints.append("reads the product catalog")
    if "processEvent" in verbs:
        hints.append("dispatches web events to business actions (session façade)")
    if any(v in verbs for v in ("updateStatus", "getOrdersByStatus", "createManager")):
        hints.append("tracks order workflow state")
    if any(v in verbs for v in ("addLineItem", "getData")) and "Order" in classname:
        hints.append("represents a purchase order aggregate")
    if "onMessage" in verbs:
        hints.append("consumes async JMS messages and drives the next workflow step")
    if not hints:
        cn = classname.lower()
        if "address" in cn: return "represents a postal address value"
        if "creditcard" in cn: return "represents credit-card details"
        if "contactinfo" in cn: return "represents contact information (name/email/phone)"
        if "inventory" in cn: return "tracks stock quantity per item"
        if "mail" in cn: return "sends notification email"
        if "uid" in cn or "unique" in cn: return "generates unique identifiers"
        if "servicelocator" in cn: return "caches JNDI lookups of EJB homes / queues"
        if "xml" in cn: return "marshals domain objects to/from XML for JMS transport"
        return "supporting type for this component"
    return "; ".join(hints)


def strip_comments(src):
    """Remove block and line comments so declarations aren't matched inside Javadoc."""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def parse_java(path):
    raw = open(path, encoding="utf-8", errors="ignore").read()
    src = strip_comments(raw)
    m = CLASSDECL_RE.search(src)
    if not m:
        return None
    kind, name = m.group(1), m.group(2)
    is_interface = kind == "interface"
    methods = []
    for mm in METHOD_RE.finditer(src):
        ret, mname, args = mm.group(1).strip(), mm.group(2), mm.group(3).strip()
        if mname in ("ejbActivate", "ejbPassivate", "ejbRemove", "ejbLoad", "ejbStore",
                     "setSessionContext", "setEntityContext", "unsetEntityContext",
                     "ejbCreate", "ejbPostCreate"):
            continue  # skip EJB lifecycle noise
        argt = ", ".join(a.split()[0] for a in args.split(",") if a.strip())
        methods.append((ret, mname, argt))
    imports = sorted(set(IMPORT_RE.findall(src)))
    dep_pkgs = sorted({i.split(".")[4] for i in imports if len(i.split(".")) > 4})
    return {"name": name, "is_interface": is_interface, "src": src,
            "methods": methods[:12], "deps": dep_pkgs}


def cmp_tables(pkg_dir):
    """Extract CMP entity/table info from any ejb-jar.xml in the package."""
    out = []
    for xml in glob.glob(os.path.join(pkg_dir, "**", "ejb-jar.xml"), recursive=True):
        txt = open(xml, encoding="utf-8", errors="ignore").read()
        for m in re.finditer(r'<ejb-name>(.*?)</ejb-name>(.*?)(?=<ejb-name>|</enterprise-beans>|\Z)',
                             txt, re.S):
            ejb, body = m.group(1).strip(), m.group(2)
            if "<persistence-type>Container</persistence-type>" in body:
                fields = re.findall(r'<field-name>(.*?)</field-name>', body)
                schema = re.search(r'<abstract-schema-name>(.*?)</abstract-schema-name>', body)
                out.append((ejb, schema.group(1) if schema else "?", fields))
    return out


def gen_package(pkg, kind):
    pkg_dir = os.path.join(SRC, kind, pkg)
    javas = sorted(glob.glob(os.path.join(pkg_dir, "**", "*.java"), recursive=True))
    if not javas:
        return None
    classes = [c for c in (parse_java(p) for p in javas) if c]
    all_deps = sorted({d for c in classes for d in c["deps"] if d != pkg})
    tables = cmp_tables(pkg_dir)

    L = []
    L.append(f"# Migration Spec — `{pkg}` ({kind[:-1]})\n")
    L.append("> Auto-generated from source by `gen_package_docs.py`. "
             "Business responsibility + dependencies + target mapping per class. "
             "See root `CLAUDE.md` for the global migration rules and forbidden shortcuts.\n")
    L.append(f"**Package:** `{BP}.{pkg}`  \n**Classes:** {len(classes)}  "
             f"\n**Depends on packages:** {', '.join(f'`{d}`' for d in all_deps) or '_none_'}\n")

    if tables:
        L.append("## CMP tables (container-generated persistence)\n")
        L.append("| Entity | Schema | CMP fields |")
        L.append("|---|---|---|")
        for ejb, schema, fields in tables:
            L.append(f"| `{ejb}` | {schema} | {', '.join(fields) if fields else '—'} |")
        L.append("")

    L.append("## Classes\n")
    for c in classes:
        st, target = stereotype(c["name"], c["src"], c["is_interface"])
        resp = responsibility(c["name"], c["methods"])
        L.append(f"### `{c['name']}`")
        L.append(f"- **Stereotype:** {st}")
        L.append(f"- **Responsibility:** {resp}")
        L.append(f"- **Target (Spring/Java 21):** {target}")
        if c["deps"]:
            L.append(f"- **Depends on:** {', '.join(f'`{d}`' for d in c['deps'] if d != pkg) or '_(intra-package)_'}")
        if c["methods"]:
            L.append("- **Public API:**")
            for ret, mn, at in c["methods"]:
                L.append(f"    - `{ret} {mn}({at})`")
        L.append("")

    L.append("## Migration checklist (old → new)\n")
    L.append("- [ ] Characterization tests written & green against the **legacy** behaviour "
             "(see `CLAUDE.md` §Characterization-test harness).")
    L.append("- [ ] Domain types remodeled as framework-free POJOs/records.")
    if tables:
        L.append("- [ ] CMP entity beans → JPA `@Entity` + Spring Data repository; "
                 "schema reverse-engineered (drop `__PMPrimaryKey`/`__reverse_*`, fix VARCHAR(255)).")
        L.append("- [ ] Data-migration script old-table → new-schema (reversible, reconciled).")
    if any("DAO" in c["name"] for c in classes):
        L.append("- [ ] DAO interface → Spring Data repository; externalized SQL preserved as needed.")
    if any(c["name"].endswith("MDB") for c in classes):
        L.append("- [ ] MDB → `@JmsListener`/`@KafkaListener` or in-process `@EventListener`; "
                 "message schema (XML→JSON) contract-tested.")
    if any("SessionBean" in c["src"] or "SessionContext" in c["src"] for c in classes):
        L.append("- [ ] Session bean → `@Service` (+ `@Transactional`); "
                 "remove Home/Local interface trio.")
    L.append("- [ ] All characterization tests still green against the **migrated** code.")
    L.append("- [ ] Ledger row updated (`migration_ledger.md`).")
    L.append("")

    doc = "\n".join(L)
    out_path = os.path.join(pkg_dir, "MIGRATION.md")
    open(out_path, "w").write(doc)
    return {"pkg": pkg, "kind": kind, "classes": len(classes),
            "deps": all_deps, "has_cmp": bool(tables), "path": out_path}


def main():
    results = []
    for kind in ("components", "apps"):
        for pkg in sorted(os.listdir(os.path.join(SRC, kind))):
            if os.path.isdir(os.path.join(SRC, kind, pkg)):
                r = gen_package(pkg, kind)
                if r:
                    results.append(r)

    # Topological-ish ordering: fewest deps first = migrate first
    order = sorted(results, key=lambda r: (r["kind"] != "components", len(r["deps"]), r["pkg"]))
    idx = ["# Migration Index & Suggested Order\n",
           "> Generated by `gen_package_docs.py`. Order = fewest cross-package deps first "
           "(leaf modules migrate first, lowest risk). Each row links to that package's `MIGRATION.md`.\n",
           "| # | Package | Type | Classes | CMP? | Depends on | Spec |",
           "|---|---|---|---|---|---|---|"]
    for i, r in enumerate(order, 1):
        rel = os.path.relpath(r["path"], os.path.dirname(SRC))
        idx.append(f"| {i} | `{r['pkg']}` | {r['kind'][:-1]} | {r['classes']} | "
                   f"{'yes' if r['has_cmp'] else '—'} | "
                   f"{', '.join(f'`{d}`' for d in r['deps']) or '—'} | [{rel}]({rel}) |")
    idx.append("")
    open(os.path.join(SRC, "MIGRATION_INDEX.md"), "w").write("\n".join(idx))
    print(f"Wrote {len(results)} MIGRATION.md files + MIGRATION_INDEX.md")
    for r in order:
        print(f"  {r['kind'][:-1]:10} {r['pkg']:16} classes={r['classes']:2} "
              f"cmp={'Y' if r['has_cmp'] else '-'} deps={len(r['deps'])}")


if __name__ == "__main__":
    main()
