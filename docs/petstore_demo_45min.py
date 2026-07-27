#!/usr/bin/env python3
"""
Java Pet Store — 45-MINUTE DEMO DECK (with code walkthrough).

A focused, presentation-paced deck for a 45-minute playback session:
  · the brief + what "done" means
  · gaps in the legacy design → what we fixed (parity audit)
  · a multi-step migration plan (incl. the legacy JMS XML → JSON plan)
  · target as-built architecture
  · a live-code walkthrough (real files, real snippets)
  · MongoDB stretch

It EMBEDS the already-generated diagrams (run these first if missing):
  petstore_legacy_highlevel.py   → petstore_legacy_highlevel.png
  petstore_architecture.py       → petstore_architecture.png
  petstore_packages.py           → petstore_packages.png

Output: docs/PetStore_Demo_45min.pptx   (self-contained; own copy of the helpers)
Deps: python-pptx (+ the PNGs above for the image slides).
"""
import os
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from PIL import Image

BASE = os.path.dirname(os.path.abspath(__file__))

# ── palette (shared with petstore_ppt.py) ─────────────────────────────────────
NAVY   = RGBColor(0x1F, 0x33, 0x55)
BLUE   = RGBColor(0x2E, 0x6D, 0xB4)
AMBER  = RGBColor(0xC9, 0x7C, 0x2F)
GREEN  = RGBColor(0x2F, 0x8F, 0x46)
RED    = RGBColor(0xB2, 0x3A, 0x48)
PURPLE = RGBColor(0x6B, 0x4F, 0xA0)
GREY   = RGBColor(0x55, 0x55, 0x55)
LIGHT  = RGBColor(0xF2, 0xF4, 0xF7)
CODEBG = RGBColor(0x1E, 0x28, 0x3B)   # dark panel for code
CODEFG = RGBColor(0xE6, 0xED, 0xF3)
CODEKW = RGBColor(0x7F, 0xB5, 0xF5)   # keyword-ish accent
WHITE  = RGBColor(0xFF, 0xFF, 0xFF)
SUB    = RGBColor(0xC5, 0xD3, 0xE6)

prs = Presentation()
prs.slide_width = Inches(13.333)   # 16:9
prs.slide_height = Inches(7.5)
SW, SH = prs.slide_width, prs.slide_height
BLANK = prs.slide_layouts[6]


def slide():
    return prs.slides.add_slide(BLANK)


def rect(s, x, y, w, h, color):
    sh = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, y, w, h)
    sh.fill.solid(); sh.fill.fore_color.rgb = color
    sh.line.fill.background()
    sh.shadow.inherit = False
    return sh


def textbox(s, x, y, w, h, lines, size=18, color=NAVY, bold=False,
            align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, font="Calibri"):
    tb = s.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame; tf.word_wrap = True
    tf.vertical_anchor = anchor
    if isinstance(lines, str):
        lines = [lines]
    for i, ln in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        text, opts = ln if isinstance(ln, tuple) else (ln, {})
        r = p.add_run(); r.text = text
        r.font.size = Pt(opts.get("size", size))
        r.font.bold = opts.get("bold", bold)
        r.font.color.rgb = opts.get("color", color)
        r.font.name = opts.get("font", font)
        if "space_after" in opts:
            p.space_after = Pt(opts["space_after"])
        p.level = opts.get("level", 0)
    return tb


def title_bar(s, title, subtitle=None, tag=None):
    rect(s, 0, 0, SW, Inches(1.15), NAVY)
    if tag:  # small amber "minute budget" chip on the right
        rect(s, Inches(11.35), Inches(0.30), Inches(1.75), Inches(0.55), AMBER)
        textbox(s, Inches(11.35), Inches(0.30), Inches(1.75), Inches(0.55),
                tag, size=13, color=WHITE, bold=True,
                align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    textbox(s, Inches(0.5), Inches(0.12), Inches(10.7), Inches(0.7),
            title, size=27, color=WHITE, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    if subtitle:
        textbox(s, Inches(0.5), Inches(0.74), Inches(10.7), Inches(0.35),
                subtitle, size=13, color=SUB)


def bullets(s, x, y, w, h, items, size=16, gap=7):
    tb = s.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame; tf.word_wrap = True
    for i, it in enumerate(items):
        lvl = 0; text = it; color = NAVY; bold = False; sz = size
        if isinstance(it, tuple):
            text, meta = it
            lvl = meta.get("level", 0); color = meta.get("color", NAVY)
            bold = meta.get("bold", False); sz = meta.get("size", size)
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.level = lvl; p.space_after = Pt(gap)
        r = p.add_run()
        r.text = ("•  " if lvl == 0 else "–  ") + text
        r.font.size = Pt(sz); r.font.color.rgb = color; r.font.bold = bold
        r.font.name = "Calibri"
    return tb


def table(s, x, y, w, h, data, col_widths=None, header_color=NAVY, fsize=11):
    rows, cols = len(data), len(data[0])
    gt = s.shapes.add_table(rows, cols, x, y, w, h).table
    if col_widths:
        for i, cw in enumerate(col_widths):
            gt.columns[i].width = cw
    for r in range(rows):
        for c in range(cols):
            cell = gt.cell(r, c)
            cell.text = str(data[r][c])
            para = cell.text_frame.paragraphs[0]
            para.font.size = Pt(fsize); para.font.name = "Calibri"
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            cell.margin_top = Pt(2); cell.margin_bottom = Pt(2)
            cell.margin_left = Pt(5); cell.margin_right = Pt(5)
            if r == 0:
                cell.fill.solid(); cell.fill.fore_color.rgb = header_color
                para.font.color.rgb = WHITE; para.font.bold = True
            else:
                cell.fill.solid()
                cell.fill.fore_color.rgb = WHITE if r % 2 else LIGHT
                para.font.color.rgb = NAVY
    return gt


def code_panel(s, x, y, w, h, path, lines, caption=None):
    """A dark code panel: file-path header + monospaced body."""
    rect(s, x, y, w, h, CODEBG)
    # file path header
    hdr = s.shapes.add_textbox(x + Inches(0.15), y + Inches(0.06), w - Inches(0.3), Inches(0.3))
    hp = hdr.text_frame.paragraphs[0]
    hr = hp.add_run(); hr.text = path
    hr.font.size = Pt(11); hr.font.bold = True; hr.font.name = "Consolas"
    hr.font.color.rgb = CODEKW
    # body
    body = s.shapes.add_textbox(x + Inches(0.15), y + Inches(0.42), w - Inches(0.3), h - Inches(0.5))
    tf = body.text_frame; tf.word_wrap = True
    for i, ln in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(1)
        text, opts = ln if isinstance(ln, tuple) else (ln, {})
        r = p.add_run(); r.text = text if text else " "
        r.font.size = Pt(opts.get("size", 12)); r.font.name = "Consolas"
        r.font.color.rgb = opts.get("color", CODEFG)
    if caption:
        textbox(s, x, y + h + Inches(0.08), w, Inches(0.5), caption,
                size=13, color=GREY)


def image_slide(title, subtitle, img_name, tag=None):
    s = slide()
    title_bar(s, title, subtitle, tag=tag)
    png = os.path.join(BASE, img_name)
    if os.path.exists(png):
        with Image.open(png) as im:
            iw, ih = im.size
        avail_w, avail_h = int(Inches(12.7)), int(Inches(5.9))
        ar = iw / ih
        w = avail_w; h = int(w / ar)
        if h > avail_h:
            h = avail_h; w = int(h * ar)
        x = int((SW - w) / 2); y = int(Inches(1.28)) + int((avail_h - h) / 2)
        s.shapes.add_picture(png, x, y, width=w, height=h)
    else:
        textbox(s, Inches(0.6), Inches(3), Inches(12), Inches(1),
                f"{img_name} not found — run its generator", size=16, color=RED)
    return s


# ═══════════════════════════════════════════════════════════════════════════
# 1 — TITLE
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
rect(s, 0, 0, SW, SH, NAVY)
rect(s, 0, Inches(2.7), SW, Inches(0.06), AMBER)
textbox(s, Inches(0.8), Inches(1.25), Inches(11.7), Inches(1.2),
        "Java Pet Store → Spring Boot 3 / Java 21", size=40, color=WHITE, bold=True)
textbox(s, Inches(0.8), Inches(2.85), Inches(11.7), Inches(0.8),
        "Migration Playback — gaps fixed · phased plan · code walkthrough", size=24, color=SUB)
textbox(s, Inches(0.8), Inches(4.3), Inches(11.7), Inches(2.2), [
    ("Legacy J2EE 1.3 · 4 EARs · EJB 2.x CMP · JMS (XML) · Cloudscape   →   "
     "8 Spring Boot services · JPA · JMS (JSON) · H2/MongoDB",
     {"size": 15, "color": WHITE, "space_after": 12}),
    ("45-minute session · ~30 min narrative + ~10 min live code + ~5 min Q&A",
     {"size": 14, "color": RGBColor(0x9D, 0xB4, 0xD4)}),
])

# ═══════════════════════════════════════════════════════════════════════════
# 2 — AGENDA (with the 45-min budget)
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
title_bar(s, "Agenda", "How the 45 minutes are spent", tag="45 min")
table(s, Inches(0.5), Inches(1.4), Inches(12.3), Inches(5.4), [
    ["#", "Section", "Time"],
    ["1", "The brief & what 'done' means", "2 min"],
    ["2", "Legacy architecture — the starting point", "3 min"],
    ["3", "Gaps in the legacy design", "4 min"],
    ["4", "Migration strategy & principles", "3 min"],
    ["5", "Phased migration plan (7 phases)", "5 min"],
    ["6", "JMS message migration — XML → JSON (Anti-Corruption Layer)", "4 min"],
    ["7", "What we fixed — parity audit results", "4 min"],
    ["8", "Target as-built architecture", "3 min"],
    ["9", "CODE WALKTHROUGH (live)", "10 min"],
    ["10", "MongoDB stretch goal", "2 min"],
    ["11", "Wrap-up & Q&A", "5 min"],
], col_widths=[Inches(0.8), Inches(9.5), Inches(2.0)], fsize=13)

# ═══════════════════════════════════════════════════════════════════════════
# 3 — THE BRIEF
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
title_bar(s, "The Brief — what was asked", "The migration approach is the real deliverable", tag="2 min")
bullets(s, Inches(0.5), Inches(1.4), Inches(12.3), Inches(5.4), [
    ("Target runtime: latest stable Spring Boot 3.x on Java 21 — host anywhere (laptop / cloud).",
     {"bold": True, "size": 17}),
    ("Public GitHub project with a README covering build + run steps for a developer.",
     {"size": 16}),
    ("Approach it as if the codebase were far larger:", {"bold": True, "size": 17, "color": BLUE}),
    ("break the problem into manageable tasks", {"level": 1}),
    ("apply infrastructure, scaffolding & SW-engineering principles that mitigate migration risk",
     {"level": 1}),
    ("ensure the QUALITY of what is migrated — playback probes this", {"level": 1, "color": RED}),
    ("Optional stretch: run against MongoDB instead of the relational DB.  → DONE.",
     {"bold": True, "size": 17, "color": GREEN}),
    ("Guiding constraint: zero business-logic change — parity first, characterization tests pin behavior.",
     {"size": 16, "color": AMBER, "bold": True}),
])

# ═══════════════════════════════════════════════════════════════════════════
# 4 — LEGACY ARCHITECTURE (embed detailed diagram)
# ═══════════════════════════════════════════════════════════════════════════
image_slide("Legacy Architecture — the starting point",
            "4 EARs · coupled ONLY via JMS · shared Cloudscape DB · messages travel as XML",
            "petstore_legacy_highlevel.png", tag="3 min")

# ═══════════════════════════════════════════════════════════════════════════
# 5 — GAPS IN THE LEGACY DESIGN
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
title_bar(s, "Gaps in the Legacy Design", "What blocks a straight port — and why it matters", tag="4 min")
table(s, Inches(0.35), Inches(1.32), Inches(12.65), Inches(5.7), [
    ["Legacy construct", "The problem", "Migration response"],
    ["J2EE 1.3 EAR + Sun RI + Ant",
     "Dead runtime; no Java-21 app server; javax.* everywhere",
     "Spring Boot 3 fat-jars · Maven · javax→jakarta"],
    ["EJB 2.x CMP (container-mapped)",
     "Home/Remote/Local trios, ejb-jar.xml, ServiceLocator/JNDI — untestable",
     "Spring Data JPA entities + repository ports"],
    ["Messages as XML over JMS (xmldocuments)",
     "Hand-marshalled TPA/XML DTOs; brittle, verbose, DTD-bound",
     "JSON event envelope + Anti-Corruption Layer (§6)"],
    ["Shared DB, tables duplicated per EAR",
     "ContactInfo/Address/CreditCard copied across apps; no single owner",
     "DB-per-service; each service owns its schema"],
    ["3 MDB status writers + WAF StateMachine",
     "God-objects; order status mutated in 3 places",
     "One OrderStatusService; enum + guarded transitions"],
    ["Implicit EJB container locking",
     "Oversell/negative-stock risk once container lock is gone",
     "Pessimistic SELECT…FOR UPDATE + CHECK(qty>=0)"],
    ["Swing / Java Web Start admin",
     "Dead on a modern JVM",
     "REST admin endpoints + server-rendered console"],
    ["SOAP webservices/ variant",
     "Duplicate build of the same logic",
     "Dropped — JMS build is the one source of truth"],
], col_widths=[Inches(3.4), Inches(5.0), Inches(4.25)], fsize=10)

# ═══════════════════════════════════════════════════════════════════════════
# 6 — MIGRATION STRATEGY & PRINCIPLES
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
title_bar(s, "Migration Strategy & Principles", "How we de-risk a large migration", tag="3 min")
bullets(s, Inches(0.5), Inches(1.35), Inches(6.4), Inches(5.6), [
    ("Strangler Fig, not big-bang.", {"bold": True, "size": 17, "color": BLUE}),
    ("Migrate one bounded context at a time; keep it runnable at every step.", {"level": 1}),
    ("Characterization tests FIRST.", {"bold": True, "size": 17, "color": BLUE}),
    ("Pin legacy behavior (incl. quirks) before touching it; migrate → green-or-rollback.", {"level": 1}),
    ("Ports & adapters (hexagonal).", {"bold": True, "size": 17, "color": BLUE}),
    ("Domain depends on interfaces; JPA / JMS / Mongo are swappable adapters.", {"level": 1}),
    ("SOLID + ask before choosing a pattern.", {"bold": True, "size": 17, "color": BLUE}),
    ("Enum+guards over State pattern; thin controllers over Command — right-sized.", {"level": 1}),
])
rect(s, Inches(7.15), Inches(1.5), Inches(5.7), Inches(4.9), LIGHT)
textbox(s, Inches(7.4), Inches(1.65), Inches(5.3), Inches(0.5),
        "The safety net (quality gate)", size=16, color=NAVY, bold=True)
bullets(s, Inches(7.4), Inches(2.25), Inches(5.3), Inches(4.0), [
    ("Test pyramid: unit → @DataJpaTest slice → MockMvc → Testcontainers e2e.", {"size": 13}),
    ("Golden-file message tests before any XML→JSON wire change.", {"size": 13, "color": RED}),
    ("Idempotent @JmsListener — JMS is at-least-once.", {"size": 13}),
    ("Parity audit: file-by-file legacy vs migrated (preserved/changed/missing).", {"size": 13}),
    ("Commit only on green; a migration ledger tracks each module.", {"size": 13}),
], gap=9)

# ═══════════════════════════════════════════════════════════════════════════
# 7 — PHASED MIGRATION PLAN
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
title_bar(s, "Phased Migration Plan", "Vertical slices — each phase leaves a runnable app", tag="5 min")
table(s, Inches(0.35), Inches(1.32), Inches(12.65), Inches(5.7), [
    ["Phase", "Scope", "Done when"],
    ["0 · Scaffold", "Parent pom, Boot 3.x/Java 21, H2, Artemis, CI, char-test harness", "empty app boots on Java 21"],
    ["1 · Catalog", "Browse + catalog schema (CatalogRepository port + JPA adapter)", "runs & browsable locally"],
    ["2 · Customer / SignOn", "Register, auth, profile (CMP → JPA); BCrypt upgrade", "auth + profile tests green"],
    ["3 · Cart", "Session cart; all edge-case quirks preserved", "cart edge tests green"],
    ["4 · Order", "Checkout + PurchaseOrder aggregate; OrderStatusService", "checkout persists + emits"],
    ["5 · Fulfilment + JMS", "Inventory (race-fixed), @JmsListener workflow, ACL", "end-to-end order flow green"],
    ["6 · Web + Admin", "Thymeleaf UI, REST admin (replaces Swing), exception handler", "UI + admin flows green"],
    ["7 · Cutover", "Data migration, parallel-run, reconciliation, decommission", "legacy off; ledger all-green"],
], col_widths=[Inches(2.3), Inches(7.55), Inches(2.8)], fsize=11)
textbox(s, Inches(0.35), Inches(7.02), Inches(12.65), Inches(0.4),
        ("Bias to a walking skeleton: Phase 1 gives a browsable app on day one; "
         "every later phase adds one vertical slice without breaking it."),
        size=12, color=GREY)

# ═══════════════════════════════════════════════════════════════════════════
# 8 — JMS MESSAGE MIGRATION: XML → JSON  (explicitly requested)
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
title_bar(s, "JMS Message Migration — XML → JSON", "Legacy sent XML over JMS; we move to a JSON envelope safely", tag="4 min")
textbox(s, Inches(0.5), Inches(1.28), Inches(12.3), Inches(0.55), [
    ("Legacy fact: shared data travelled as hand-marshalled XML documents (TPA/DTD) over JMS — "
     "e.g. checkout built a PurchaseOrder XML → jms/PurchaseOrderQueue.", {"size": 13, "color": RED, "bold": True})])
table(s, Inches(0.5), Inches(1.95), Inches(12.3), Inches(2.55), [
    ["Step", "What we do", "Why / risk mitigated"],
    ["1 · Freeze the contract", "Capture real legacy XML messages as golden files (one per destination)",
     "A regression oracle before any change"],
    ["2 · Model events as records", "PurchaseOrder/Invoice/Approved… → typed Java records + a shared envelope",
     "Compile-time contract; no DTD"],
    ["3 · Anti-Corruption Layer", "Map XML DTO ⇄ domain record in messaging/ — domain never sees XML",
     "Wire format isolated behind a port"],
    ["4 · Switch serializer to JSON", "MappingJackson2MessageConverter; _type header for routing",
     "Human-readable, versionable payloads"],
    ["5 · Forward-compat + idempotency", "FAIL_ON_UNKNOWN_PROPERTIES=false; dedup keys on consumers",
     "Additive fields safe; at-least-once safe"],
], col_widths=[Inches(2.7), Inches(5.6), Inches(4.0)], fsize=10.5)
code_panel(s, Inches(0.5), Inches(4.7), Inches(12.3), Inches(2.15),
    "petstore-messaging/.../MessagingConfig.java  (JSON converter + forward-compat)",
    [("var conv = new MappingJackson2MessageConverter();", {}),
     ("conv.setTargetType(MessageType.TEXT);", {}),
     ('conv.setTypeIdPropertyName(\"_type\");   // routes JSON back to the right record', {"color": CODEKW}),
     ("mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false); // newer producer can add fields", {"color": CODEKW})])

# ═══════════════════════════════════════════════════════════════════════════
# 9 — WHAT WE FIXED (parity audit)
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
title_bar(s, "What We Fixed — Parity Audit", "File-by-file legacy vs migrated · 21 gaps found, all resolved", tag="4 min")
table(s, Inches(0.35), Inches(1.32), Inches(12.65), Inches(4.55), [
    ["#", "Gap the audit found", "Resolution"],
    ["H2", "Backorder retry-on-restock dropped", "RESTORED event-driven: RestockEvent → OPC re-drives APPROVED backorders"],
    ["H3/H4", "Approval / denial / COMPLETED emails missing", "OrderStatusTopic → notification-service emails restored"],
    ["H5", "Sales/revenue aggregation gone", "OPC aggregateSales() + GET /api/sales; admin delegates"],
    ["H6", "Catalog search semantics changed", "Restored tokenized multi-field LIKE search"],
    ["H7", "Ship/bill address collection + validation lost", "Both addresses collected + validated; persisted on order"],
    ["H8/H9", "New-customer profile defaults + sign-on locale", "Legacy defaults restored; preferredLanguage applied on login"],
    ["M1–M7", "Ordering, getItem.category, batch approval, etc.", "All restored (see PARITY_AUDIT.md)"],
], col_widths=[Inches(1.1), Inches(6.15), Inches(5.4)], fsize=10.5)
rect(s, Inches(0.35), Inches(6.05), Inches(12.65), Inches(1.05), LIGHT)
textbox(s, Inches(0.55), Inches(6.15), Inches(12.2), Inches(0.9), [
    ("Kept intentional (recorded in DECISIONS.md): H1 all-or-nothing fulfilment · M8 no persisted supplier PO.",
     {"size": 13, "color": NAVY, "bold": True, "space_after": 3}),
    ("Two bugs caught by LIVE end-to-end testing (not unit tests): large orders auto-shipped without approval; "
     "approve→fulfil race (published before commit). Both fixed + regression-tested.",
     {"size": 12, "color": RED})])

# ═══════════════════════════════════════════════════════════════════════════
# 10 — TARGET AS-BUILT ARCHITECTURE (embed)
# ═══════════════════════════════════════════════════════════════════════════
image_slide("Target Architecture — As-Built",
            "8 Spring Boot services · standalone Artemis · 2 queues + 3 topics + DLQ · 5 client SDKs",
            "petstore_architecture.png", tag="3 min")

# ═══════════════════════════════════════════════════════════════════════════
# 11 — CODE WALKTHROUGH divider
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
rect(s, 0, 0, SW, SH, NAVY)
rect(s, 0, Inches(3.5), SW, Inches(0.06), AMBER)
textbox(s, Inches(0.8), Inches(2.5), Inches(11.7), Inches(1.0),
        "Code Walkthrough", size=40, color=WHITE, bold=True)
textbox(s, Inches(0.8), Inches(3.75), Inches(11.7), Inches(1.6), [
    ("Live in the repo — the slides mirror the files, open the real code alongside.", {"size": 17, "color": SUB, "space_after": 10}),
    ("1) JMS contract  2) Anti-Corruption / envelope  3) checkout intake  "
     "4) pessimistic-lock fulfilment  5) restock re-drive  6) transactional outbox", {"size": 14, "color": RGBColor(0x9D, 0xB4, 0xD4)}),
], anchor=MSO_ANCHOR.TOP)

# ── 12 — WALKTHROUGH 1: the JMS contract (one shared lib) ─────────────────────
s = slide()
title_bar(s, "Walkthrough 1 — The JMS Contract", "One shared library defines every destination; producers/consumers import it", tag="code")
code_panel(s, Inches(0.5), Inches(1.4), Inches(12.3), Inches(3.55),
    "petstore-messaging/src/com/petstore/messaging/Destinations.java",
    [('public static final String PURCHASE_ORDER_NAME = \"PurchaseOrderQueue\";', {}),
     ('public static final String APPROVED_ORDER_NAME = \"ApprovedOrderQueue\";', {}),
     ('public static final String INVOICE_NAME        = \"InvoiceTopic\";', {}),
     ('public static final String ORDER_STATUS_NAME   = \"OrderStatusTopic\";', {}),
     ('public static final String RESTOCK_NAME        = \"RestockTopic\";', {}),
     ("", {}),
     ("public static final Destination APPROVED_ORDER = queue(APPROVED_ORDER_NAME);", {"color": CODEKW}),
     ("public static final Destination INVOICE        = topic(INVOICE_NAME);   // pub/sub fan-out", {"color": CODEKW}),
     ("public static final Destination RESTOCK        = topic(RESTOCK_NAME);   // H2 re-drive", {"color": CODEKW})])
bullets(s, Inches(0.5), Inches(5.15), Inches(12.3), Inches(2.0), [
    ("Single source of truth — no service hard-codes a destination string.", {"size": 14}),
    ("queue() = point-to-point (one consumer) · topic() = fan-out (legacy InvoiceTopic preserved).", {"size": 14}),
    ("2 queues + 3 topics; DLQ + ExpiryQueue are broker-managed safety nets.", {"size": 14}),
])

# ── 13 — WALKTHROUGH 2: synchronous checkout intake ───────────────────────────
s = slide()
title_bar(s, "Walkthrough 2 — Checkout Intake (sync REST)", "Storefront hands the order to OPC over REST; JWT proxied for authz", tag="code")
code_panel(s, Inches(0.5), Inches(1.4), Inches(12.3), Inches(3.0),
    "petstore-app-v1/src/com/petstore/order/service/OrderService.java",
    [("List<CartItem> items = cart.getItems();", {}),
     ("if (items.isEmpty()) throw new EmptyCartException();", {}),
     ("// LOCALE = Locale.US, CURRENCY = \"USD\" (legacy quirk — UI locale does not flow in)", {"color": GREEN}),
     ("CheckoutRequest request = new CheckoutRequest(", {}),
     ("    orderId, userId, emailId, LOCALE, CURRENCY, total, lines, toDto(shipTo), toDto(billTo));", {}),
     ("try {", {}),
     ("    response = orderProcessing.checkout(request, bearer); // POST /api/orders/intake, JWT fwd", {"color": CODEKW}),
     ("} catch (RestClientException e) {", {}),
     ("    throw new OrderIntakeUnavailableException(...); // OPC down → 503, cart NOT emptied", {"color": RED}),
     ("}", {}),
     ("cart.empty();   // only on success", {"color": CODEKW})])
bullets(s, Inches(0.5), Inches(4.65), Inches(12.3), Inches(2.35), [
    ("Legacy published a PurchaseOrder XML fire-and-forget; we made intake synchronous so the shopper "
     "gets an immediate, authoritative result.", {"size": 14}),
    ("The storefront persists nothing — OPC is the authoritative order store (single writer).", {"size": 14}),
    ("PurchaseOrderQueue listener is kept as an alternate async intake path (contract unchanged).", {"size": 14, "color": GREY}),
])

# ── 14 — WALKTHROUGH 3: pessimistic-lock fulfilment (oversell guard) ──────────
s = slide()
title_bar(s, "Walkthrough 3 — Oversell Guard", "The sanctioned technical fix: pessimistic row lock replaces the EJB container lock", tag="code")
code_panel(s, Inches(0.5), Inches(1.4), Inches(12.3), Inches(2.85),
    "inventory-service/.../repository/jpa/InventoryJpaRepository.java",
    [("@Lock(LockModeType.PESSIMISTIC_WRITE)", {"color": CODEKW}),
     ('@Query(\"select i from InventoryEntity i where i.itemId = :itemId\")', {}),
     ("Optional<InventoryEntity> findByIdForUpdate(String itemId);  // SELECT … FOR UPDATE", {}),
     ("", {}),
     ("@Modifying(flushAutomatically = true, clearAutomatically = true)", {"color": CODEKW}),
     ('@Query(\"update InventoryEntity i set i.quantity = i.quantity + :qty where i.itemId = :itemId\")', {}),
     ("int increment(String itemId, int qty);   // additive restock")])
bullets(s, Inches(0.5), Inches(4.5), Inches(12.3), Inches(2.4), [
    ("check-and-decrement inside one @Transactional; DB CHECK (quantity >= 0) is the floor.", {"size": 14}),
    ("Verified with a 20-thread test: 5 stock → exactly 5 succeed, never negative.", {"size": 14, "color": GREEN}),
    ("Preserves the legacy observable outcome: one order ships, the other backorders (stays APPROVED).", {"size": 14}),
])

# ── 15 — WALKTHROUGH 4: restock re-drive (H2, event-driven) ──────────────────
s = slide()
title_bar(s, "Walkthrough 4 — Backorder Re-drive (H2)", "Restore legacy processPendingPO without the legacy PENDING PO store", tag="code")
code_panel(s, Inches(0.5), Inches(1.4), Inches(12.3), Inches(3.35),
    "order-processing-service/app/.../messaging/RestockListener.java",
    [("@JmsListener(destination = Destinations.RESTOCK_NAME, containerFactory = \"topicFactory\",", {"color": CODEKW}),
     ('           subscription = \"opc-restock\")   // durable shared subscription', {"color": CODEKW}),
     ("@Transactional", {"color": CODEKW}),
     ("public void onRestock(RestockEvent restock) {", {}),
     ("    Correlation.set(restock.meta() == null ? null : restock.meta().correlationId());", {}),
     ("    // AdminService: orderIdsByStatus(APPROVED) → sorted by created (oldest-first)", {"color": GREEN}),
     ("    //             → approvalGateway.dispatchForFulfilment(order) per order", {"color": GREEN}),
     ("    admin.redriveApprovedForFulfilment();", {}),
     ("}", {})])
bullets(s, Inches(0.5), Inches(5.0), Inches(12.3), Inches(2.0), [
    ("inventory publishes RestockEvent → RestockTopic when stock is added; OPC owns the order read-model and reacts.",
     {"size": 13}),
    ("Idempotent end-to-end: inventory dedups by order_id (fulfilled_order ledger) — a re-driven order that "
     "already shipped never double-decrements.", {"size": 13, "color": GREEN}),
    ("Same observable behavior as legacy; no persisted supplier PO required (M8 kept).", {"size": 13, "color": GREY}),
])

# ── 16 — WALKTHROUGH 5: transactional outbox ─────────────────────────────────
s = slide()
title_bar(s, "Walkthrough 5 — Transactional Outbox", "Order commit and 'approved' publish can't diverge", tag="code")
bullets(s, Inches(0.5), Inches(1.4), Inches(12.3), Inches(2.0), [
    ("Problem: if we persist the order then publish, a crash between them loses the message (or double-sends).",
     {"size": 15, "color": RED}),
    ("Fix: write the outgoing event into an outbox row in the SAME DB transaction as the order; a relay "
     "polls and publishes, marking rows sent. Publish is now exactly-effectively-once.", {"size": 15}),
])
code_panel(s, Inches(0.5), Inches(3.5), Inches(12.3), Inches(2.4),
    "order-processing-service/app/.../service/  (AdminService · ApprovalGateway · OutboxRelay)",
    [("@Transactional                                     // ONE atomic unit", {"color": CODEKW}),
     ("void applyStatusChange(orderId, APPROVED) {", {}),
     ("    orders.updateStatus(orderId, APPROVED);         // order row", {}),
     ("    approvalGateway.dispatchForFulfilment(order);   // → OutboxWriter.enqueue(...) — SAME tx", {"color": CODEKW}),
     ("    statusGateway.announce(order, APPROVED);        // → OutboxWriter.enqueue(...) — SAME tx", {"color": CODEKW}),
     ("}", {}),
     ("// @Scheduled OutboxRelay polls unsent rows → MessagePublisher → marks published", {"color": GREEN})])
textbox(s, Inches(0.5), Inches(6.05), Inches(12.3), Inches(0.95),
        ("Gateways NEVER publish to JMS directly — they append to the outbox table in-transaction, so a rolled-back "
         "order emits nothing and a committed one always eventually does. At-least-once relay is safe: fixed eventId "
         "+ idempotent consumers. @Version optimistic lock stops an approve+deny race (→ 409); the Mongo adapter keeps "
         "outbox + @Version via multi-document transactions (rs0)."),
        size=12, color=GREY)

# ═══════════════════════════════════════════════════════════════════════════
# 17 — MONGODB STRETCH
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
title_bar(s, "MongoDB Stretch — DONE", "Ports made the DB swap additive — zero change to domain / services / tests", tag="2 min")
bullets(s, Inches(0.5), Inches(1.4), Inches(6.5), Inches(5.4), [
    ("Profile-selectable: @Profile(\"mongo\") vs @Profile(\"!mongo\"); H2 stays the default.",
     {"size": 15, "bold": True}),
    ("order-processing-service — fully wired:", {"size": 15, "color": BLUE, "bold": True}),
    ("MongoOrderStore + MongoOutboxStore adapters", {"level": 1, "size": 14}),
    ("multi-document transactions (outbox + order commit)", {"level": 1, "size": 14}),
    ("@Version optimistic lock + $jsonSchema validators", {"level": 1, "size": 14}),
    ("catalog-service — Mongo adapter (3 collections, locale map embedded).", {"size": 15, "color": BLUE, "bold": True}),
    ("Testcontainers mongo:7 parity tests on both.", {"size": 14, "color": GREEN}),
])
rect(s, Inches(7.25), Inches(1.5), Inches(5.6), Inches(4.6), LIGHT)
textbox(s, Inches(7.5), Inches(1.65), Inches(5.1), Inches(0.5),
        "Why it was cheap", size=16, color=NAVY, bold=True)
bullets(s, Inches(7.5), Inches(2.25), Inches(5.1), Inches(3.7), [
    ("The domain talks to a port (OrderStore / OutboxStore), not to JPA.", {"size": 14}),
    ("Mongo is just another adapter behind the same interface.", {"size": 14}),
    ("Run it: OPC_STORE=mongo ./run-all.sh (docker-compose brings up mongo + mongo-express).", {"size": 13, "color": GREY}),
    ("This is the payoff of hexagonal boundaries chosen up front.", {"size": 14, "color": GREEN, "bold": True}),
])

# ═══════════════════════════════════════════════════════════════════════════
# 18 — WRAP-UP
# ═══════════════════════════════════════════════════════════════════════════
s = slide()
title_bar(s, "Wrap-up", "What the playback should take away", tag="5 min")
bullets(s, Inches(0.5), Inches(1.4), Inches(12.3), Inches(5.4), [
    ("The approach de-risked a large migration: Strangler Fig + characterization tests + ports/adapters.",
     {"size": 17, "bold": True, "color": BLUE}),
    ("Every legacy construct has a deliberate modern mapping — nothing dropped by accident.", {"size": 16}),
    ("Messages moved XML → JSON behind an Anti-Corruption Layer, guarded by golden-file tests.", {"size": 16}),
    ("A file-by-file parity audit found 21 drifts; all resolved or explicitly kept with an ADR.", {"size": 16}),
    ("Live e2e testing caught two bugs unit tests missed — the safety net earned its keep.", {"size": 16, "color": RED}),
    ("Stretch goal delivered: MongoDB behind the same ports, at near-zero domain cost.", {"size": 16, "color": GREEN}),
    ("", {"size": 8}),
    ("Questions?", {"size": 22, "bold": True, "color": NAVY}),
])

# ═══════════════════════════════════════════════════════════════════════════
out = os.path.join(BASE, "PetStore_Demo_45min.pptx")
prs.save(out)
print("Wrote", out)
print("Slides:", len(prs.slides.__iter__.__self__._sldIdLst))
