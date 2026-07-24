# Phase 7 — Cutover Plan (Data Migration · Parallel-Run · Decommission)

Phases 0–6 delivered a functionally complete Spring Boot 3.x / Java 21 replacement
verified by characterization tests. Phase 7 is the **operational cutover**: moving
real production data and traffic from the legacy system to the new one with zero
data loss and a safe rollback. For this exercise it is documented (there is no live
legacy system to migrate against); the steps below are what a real cutover requires.

## 1. Guiding principles
- **Reversible at every step** — no one-way doors; keep the ability to roll back
  until the new system is proven in production.
- **Reconcile before trust** — every data move is followed by an automated
  count/checksum comparison old-vs-new.
- **Strangler cutover** — migrate + shift traffic by bounded context, not big-bang.

## 2. Data migration (legacy CMP schema → new JPA schema)

The legacy tables are CMP-generated (`*EJBTable`, `__PMPrimaryKey`,
`__reverse_*`, everything `VARCHAR(255)`) and split across the 4 apps' databases.
The new schema is redesigned (typed columns, proper keys, consolidated).

**Approach — a Flyway/scripted ETL per bounded context:**

| Legacy source | New target | Transform notes |
|---|---|---|
| `category/product/item(+_details)` | same tables (kept) | Nearly 1:1; already clean. Lowest risk — migrate first. |
| `UserEJBTable` | `app_user` | userName→user_name; password carried as-is (see security note). |
| `CustomerEJBTable`+`Account`+`Profile`+`ContactInfo`+`Address` | `customer` (flattened) | Join the CMP graph via `__PMPrimaryKey`/`__reverse_*` FKs; drop synthetic keys; map to typed columns. |
| `PurchaseOrderEJBTable`+`LineItemEJBTable`(+join)+`ManagerEJBTable` | `purchase_order`+`line_item`+`order_status` | `poDate` LONGINT→timestamp; `poValue` REAL→DECIMAL; status string→enum. |
| `InventoryEJBTable` | `inventory` | itemId→item_id, quantity carried; add CHECK(quantity>=0). |

**Rules:**
- Scripts are **idempotent and resumable** (re-runnable without duplication).
- Each script has a tested **rollback** (drop/restore new rows) — never mutate legacy.
- **Reconciliation gate** after each: row counts match; checksums on key columns
  (e.g. sum of order totals, count of customers) match; spot-check sample rows.
- De-duplicate the shared value objects (ContactInfo/Address/CreditCard were
  copied across the 3 legacy app DBs) into the single consolidated schema.

## 3. Parallel-run (shadow) validation

Before shifting real traffic:
- Run new alongside legacy; **mirror a copy of production requests** to the new
  system (read paths first — catalog/browse — which are side-effect-free).
- **Diff the responses** (status codes, JSON/HTML shapes) old-vs-new; investigate
  every divergence. The characterization tests already encode the expected
  contract; parallel-run confirms it against real traffic and data.
- For write paths (checkout/fulfilment), shadow into an isolated new DB and
  reconcile resulting order/inventory state rather than double-charging.

## 4. Traffic cutover (per bounded context, behind a façade)

Put a routing façade (gateway / reverse proxy) in front and shift **capability by
capability**, in the low-risk order the migration followed:

1. Catalog browse (read-only, safest) → 2. Customer/SignOn → 3. Cart →
4. Checkout/Order → 5. Fulfilment (JMS backbone) → 6. Admin.

- Use **feature flags / percentage routing** (e.g. 1% → 10% → 50% → 100%) with
  instant rollback (flip the route back).
- Watch metrics/errors/latency at each step; hold at each percentage until healthy.

## 5. Messaging cutover (JMS)
- New system already uses JMS (Artemis). For a real cutover, point both at the
  same broker or bridge queues during coexistence.
- The consumer is **idempotent** (verified), so brief double-delivery during the
  switch cannot double-process orders.

## 6. Decommission
- Only after the new system runs 100% of traffic and reconciles clean for an
  agreed soak period, freeze and archive the legacy databases, then retire the
  4 legacy EARs and the J2EE server.
- Keep the archived legacy DB snapshots for the audit/rollback window.

## 7. Definition of done (cutover)
- All production data migrated and reconciled (counts + checksums green).
- 100% traffic on the new system for the soak period with healthy metrics.
- Legacy decommissioned; rollback artifacts archived.
- Runbook + this plan updated with what actually happened.

## 8. Follow-up (post-cutover, out of parity scope)
- **Security:** replace plaintext password equality with hashed passwords
  (BCrypt) + Spring Security — deliberately deferred so the migration stayed
  behaviour-preserving. Flagged in DECISIONS.md #21.
- **MongoDB / Kafka** swaps — additive adapters behind the existing ports
  (DECISIONS.md "Swappability guarantee").
