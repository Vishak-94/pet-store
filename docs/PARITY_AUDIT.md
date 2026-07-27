# Legacy → Migrated Business-Logic Parity Audit

**Scope:** file-by-file audit of Java Pet Store 1.3.1_02 (`petstore1.3.1_02/src`, ~309 Java files)
against the migrated Spring Boot 3 / Java 21 system (8 services + libs, ~140 Java files).
**Method:** 10 parallel bounded-context audits, each reporting per legacy file whether business
logic was **preserved / changed / missing / intentionally-dropped**, then synthesized here.
**Goal of the migration (constraint):** *zero business-logic changes* — so every "changed/missing"
below is a place where observable behavior drifted and should be reviewed against that constraint.

> **Bottom line:** The core workflows are faithful — cart math, checkout→JMS publish, the
> order-approval threshold (US<500 / JAPAN<50000, byte-for-byte), the 5-state order workflow, all
> three JMS destinations (PurchaseOrderQueue, ApprovedOrderQueue, InvoiceTopic pub/sub), the
> oversell→backorder race outcome, catalog CRUD + locale-split tables, and trilingual i18n are all
> **PRESERVED**. All EJB/JNDI/ServiceLocator/XML-DTO/WAF/Swing plumbing is correctly and
> intentionally dropped for Spring idioms. The gaps below are genuine behavioral drifts, not framework noise.

---

## Remediation status (2026-07-24)

All gaps have been addressed. H1 (all-or-nothing fulfilment) and M8 (no persisted supplier PO) are
kept as **intentional** design decisions (recorded in `DECISIONS.md`); every other gap was **FIXED**
to restore legacy behavioral parity — including H2 (backorder retry-on-restock), restored later via an
event-driven re-drive rather than the legacy PENDING PO store. All touched modules build and their test
suites pass.

| # | Status | Resolution |
|---|--------|-----------|
| H1 | **KEEP (intentional)** | All-or-nothing fulfilment is a recorded decision. Only cleanup applied: removed the unreachable `OrderStatus.SHIPPED_PART` dead state and corrected `FulfilmentService` javadoc to describe the actual all-or-nothing behavior. |
| H2 | **FIXED (event-driven re-drive)** | Backorder retry-on-restock restored, but by a different mechanism than legacy (no persisted supplier PO — see M8). Inventory-service publishes a `RestockEvent` to `RestockTopic` on restock; OPC's `RestockListener` re-drives every APPROVED (backordered) order, oldest-first, back through the existing `ApprovalGateway` → outbox → `ApprovedOrderQueue` → fulfilment pipeline (`AdminService.redriveApprovedForFulfilment`). Idempotent: inventory dedups by `order_id` (`fulfilled_order` ledger, replacing the eventId-keyed `processed_event`) so a re-driven order that already shipped never double-decrements. Matches the legacy `processPendingPO`-on-restock **observable behavior** without the legacy PENDING PO store. |
| H3 | **FIXED** | New `OrderStatusEvent` broadcast on `OrderStatusTopic` via `OrderStatusGateway` (after-commit); `notification-service` `OrderStatusNotificationListener` emails customer on APPROVED/DENIED (`"...Order Status: <id>"`). |
| H4 | **FIXED** | COMPLETED transition (in `InvoiceListener`) announces via the same gateway; distinct `"...Order COMPLETED: <id>"` subject restored in `OrderMailComposer`. |
| H5 | **FIXED** | `getChartInfo` aggregation logic restored: OPC `aggregateSales(start,end,category)` (JPQL GROUP BY revenue Σ qty·unitPrice + order counts) exposed at `GET /api/sales`; admin-office delegates via `OrderProcessingClient`. Added `wh_order.created` timestamp for the date range. |
| H6 | **FIXED** | `searchItems` restored to legacy tokenized multi-field search (whitespace tokens, per-token OR `LIKE %tok%` across product name + category catid + item descn) via a dynamic-JPQL repository fragment. |
| H7 | **FIXED (restore per direction)** | Checkout collects + validates ship-to and bill-to (required: family/given name, street1, city, state, zip, telephone; street2/country/email optional) → 400 on missing; `PurchaseOrderEvent` carries `shipTo`/`billTo`; OPC persists them (`wh_order` ship_*/bill_* columns). |
| H8 | **FIXED** | `Profile.defaults()` corrected to legacy `(en_US, null, true, true)`; javadoc fixed. |
| H9 | **FIXED** | Sign-on applies the customer's stored `preferredLanguage` to session/cart locale via an `AuthenticationSuccessHandler`; explicit `?lang=` still wins. |
| M1 | **FIXED** | Catalog `getCategories`/`getProducts` restored to `order by name`. |
| M2 | **FIXED** | `getItem` populates `category` from the product's `catid`. |
| M3 | **FIXED** | Atomic batch approval restored: `POST /api/orders/approvals` applies a list of status changes in one transaction (all-or-nothing), preserving after-commit gateway publishes. Per-order endpoints kept. |
| M4 | **FIXED** | Storefront customer UPDATE path added (`GET/POST /customer`) wiring existing SDK `updateAccount/updateProfile/updateCard`. |
| M5 | **FIXED** | Registration re-validates the legacy required contact + card field set (400 on missing). |
| M6 | **FIXED** | Sign-on provisioning rejects username/password > 25 chars and username with `%`/`*`. |
| M7 | **FIXED** | Account `status` (`active`/`disabled`, seeded `active`) field + column restored. |
| M8 | **KEEP (intentional)** | Supplier PO still not persisted (no `SupplierOrder` entity / per-line `quantityShipped`). Structural root of H1 (all-or-nothing kept). H2's retry-on-restock is now restored *without* it — OPC re-drives from its own APPROVED order read-model, so the legacy PO store is not required for parity. |
| L2 | **FIXED** | Return-to-originating-screen after registration restored (same-app guarded). |
| L4 | **FIXED** | `locale` helper handles `"default"` → `Locale.getDefault()` and 3-part `language_country_variant`. |
| L5 | **FIXED** | Catalog pagination uses `Slice.hasNext()` (limit+1) — no more over-reported empty final page. |
| L7 | **FIXED** | Distinct COMPLETED email subject restored (folded into H4). |
| L1, L3, L6, L8 | **Accepted as-is** | Documented non-issues / capability preserved elsewhere (L1 API), downstream-only helpers (L3), arguably-intentional (L6), minor UI semantic (L8). No change. |

---

## HIGH — genuine behavioral gaps

| # | Gap | Legacy behavior | Migrated behavior | Evidence / corroboration |
|---|-----|-----------------|-------------------|--------------------------|
| H1 | **Partial-shipment semantics lost** | `OrderFulfillmentFacadeEJB.processAnOrder` ships every *available* line immediately, invoices the shipped subset, leaves short lines PENDING (`SHIPPED_PART`). | `FulfilmentService.fulfil` is strictly **all-or-nothing**: if any one line is short, nothing ships and nothing is decremented. `OrderStatus.SHIPPED_PART` is declared but **unreachable dead code**. | Corroborated by **supplier** + **opc** agents. Migrated `FulfilmentService` Javadoc *claims* it mirrors legacy — it does not. NB: distinct from the sanctioned single-item oversell fix (which is preserved). |
| H2 | **Backorder retry-on-restock dropped → RESTORED (event-driven)** | `RcvrRequestProcessor.processPendingPO` + `findOrdersByStatus(PENDING)` re-fulfil PENDING orders whenever stock is received. | **Now restored** via a different mechanism (no persisted supplier PO): restock publishes `RestockEvent`→`RestockTopic`; OPC's `RestockListener` re-drives every APPROVED backorder oldest-first through the outbox→`ApprovedOrderQueue`→fulfilment path, idempotent via inventory's `order_id`-keyed `fulfilled_order` ledger. A backordered order **is** auto-shipped once stock returns. | supplier agent. Restored from OPC's own APPROVED read-model instead of the legacy PENDING PO store (see M8). |
| H3 | **Order APPROVAL/STATUS email missing** | `MailOrderApprovalMDB` emails customer subject `"Java Pet Store Order Status: <id>"` on approve/deny. | No listener emails the customer on approval/denial. | Corroborated by **mailer** + **opc** agents. |
| H4 | **Order COMPLETED email missing** | `MailCompletedOrderMDB` emails subject `"Java Pet Store Order COMPLETED: <id>"` when fully shipped. | Status set to COMPLETED but no email; subject string absent from entire codebase. | Corroborated by **mailer** + **opc** agents. (Only the INVOICE / "Order Shipped" email was migrated.) |
| H5 | **Sales/revenue charting & aggregation gone** | `getChartInfo(start,end,category)` aggregates REVENUE (Σ qty·unitPrice by category/item) and ORDERS (counts) — present in admin BD, webservice, and OPC facade. | Zero migrated path in admin-office-service **or** OPC. The *aggregation business logic* is gone, not just the Swing charts. | Corroborated by **admin** (HIGH) + **opc** agents. |
| H6 | **Catalog `searchItems` semantics changed** | Tokenizes query on whitespace; for each token OR-matches `LIKE %tok%` across product **name** + category **catid** + item **descn**. | Matches the **whole untokenized query** against only **descn + attr1**. No name/category search; no tokenization; searches attr1 (legacy never did). | catalog agent. Different result sets for real multi-word searches. |
| H7 | **Order-time ship/bill address collection + validation dropped** | `OrderHTMLAction.extractContactInfo` validates family/given name, street, city, state, postal, telephone for **both** ship-to and bill-to before creating the order (→ `MissingFormDataException`). | `POST /checkout` collects/validates **no** address and builds the PO without ship/bill ContactInfo. | web agent. Lost validation **and** lost order data. (Distinct from the sanctioned publish-only/no-persistence decision.) |
| H8 | **New-customer default profile prefs changed** | `ProfileLocalHome` + `CustomerEJB.ejbPostCreate` seed `(preferredLanguage=en_US, favoriteCategory=null, myList=true, banner=true)`. | `Profile.defaults()` returns `(null, null, false, false)` — language null, My-List/Banner opt-in **OFF** instead of ON, for every new customer. `Profile.java` javadoc also misstates the legacy defaults. | customer agent. |
| H9 | **Customer profile → locale application on sign-on lost** | `SignOnEJBAction`/`SignOnNotifier` set session & cart locale from the customer's `preferredLanguage` on login. | Auth provider sets only username/userId/roles; locale is cookie/`?lang=` only. A returning user's stored language preference no longer takes effect. | web agent. Compounds H8. |

## MEDIUM

| # | Gap | Detail | Source |
|---|-----|--------|--------|
| M1 | Catalog result **ordering changed** | `getCategories` legacy `order by name` → migrated by `catid`; `getProducts` `order by name` → by `productid`. Changes which rows land on which paginated page. | catalog |
| M2 | Catalog `getItem` **loses `category`** | Legacy populated `Item.category` from `catid`; migrated hardcodes `null` (with an inaccurate "legacy left null here" comment). Now always null. | catalog |
| M3 | **Batch order approval** collapsed | Legacy `updateOrders(OrderApproval)` committed a batch of `ChangedOrder`s atomically in one JMS message; migrated is per-order `approve(id)`/`deny(id)`. No atomic batch op. | admin |
| M4 | **Customer UPDATE path** missing in storefront | Legacy `customer.do`/`update_customer.screen` let a signed-in user edit account/profile/card. Client SDK `updateAccount/updateProfile/updateCard` exist but **no controller/route calls them**; `customer/web` is empty. | web |
| M5 | **Registration field validation relaxed** | Legacy required address, credit-card, language, favorite-category (→ `MissingFormDataException`). Migrated `register.html` marks only username/password required; rest optional, passed through. | web |
| M6 | **Sign-on provisioning input validation dropped** | Legacy `UserEJB.ejbCreate` rejected username/password > 25 chars and username containing `%`/`*`. Migrated `AccountController.provision` only checks non-blank + password length ≥ 1. | signon |
| M7 | **Account `status` field dropped** | Legacy `AccountEJB.status` (`active`/`disabled`, seeded `active` at creation) has no field/column in migrated `Account`/`CustomerEntity`. Not confirmed relocated to auth-service. | customer |
| M8 | **Supplier PO not persisted** | No `SupplierOrder` entity, no PENDING/COMPLETED status, no per-line `quantityShipped`. Structural root of H1/H2. | supplier |

## LOW

| # | Gap | Detail | Source |
|---|-----|--------|--------|
| L1 | Admin web console lists only **PENDING** orders; APPROVED/DENIED/COMPLETED reachable via JSON API only (`GET /api/orders?status=`). | UI reduced, capability preserved at API. | admin |
| L2 | **"Return to originating screen"** after account creation lost — legacy returned user to the pre-signon URL; migrated always `redirect:/login?registered`. | web |
| L3 | **CreditCard `getExpiryMonth()/getExpiryYear()`** parsing helpers absent (no in-scope caller; consumed downstream at checkout in legacy). | customer |
| L4 | **`getLocaleFromString` narrowing** — the `"default"` → `Locale.getDefault()` case and 3-part `language_country_variant` form not carried into the reimplemented `localeOf`/`locale` helpers (split on `_`, fall back to `Locale.US`). Negligible: Pet Store locales are 2-part. | Corroborated by catalog + opc + infra agents |
| L5 | Catalog **`hasNext` over-reports** one empty page when the final page is exactly full (`size()==count` vs legacy scroll-one-past). `getCategories` unaffected (uses count query). | catalog |
| L6 | **Invoice now always published**; legacy `SupplierOrderMDB` sent the invoice message only when something shipped (`if(invoice!=null)`). Migrated always publishes an `InvoiceEvent` with a `shipped` flag (arguably intentional — enables backorder notification). | supplier |
| L7 | **Completed-order email subject folded** — `OrderMailComposer` emits only "Order Shipped:"/"Order Delayed:"; the distinct "Order COMPLETED:" notification is gone (see H4). | opc |
| L8 | Restock uses additive `addQuantity` vs legacy absolute `setQuantity` in the receiver UI — minor semantic shift. | supplier |

---

## Notes (verified, NOT gaps)

- **Oversell → backorder race outcome is PRESERVED.** Implemented via pessimistic `SELECT … FOR UPDATE`
  (`@Lock(PESSIMISTIC_WRITE)`) + `tryReserve` check-and-decrement + DDL `CHECK (quantity >= 0)` — **not**
  the literal single-statement conditional `UPDATE … WHERE quantity >= :qty` described in the sanctioned
  plan. Same observable result (one ships, one backorders). Minor benign **TOCTOU**: an unlocked first-pass
  read in `FulfilmentService.fulfil`, but the authoritative decision is the locked second pass. *(supplier)*
- Plaintext `matchPassword` → BCrypt is a deliberate, documented security upgrade (auth decision preserved). *(signon)*
- "Remember username" cookie, `findAllUsers`/`findAllCustomers`/`findPOBetweenDates` finders — dropped, no in-scope callers.
- All EJB home/remote/local interfaces, ServiceLocator, JNDINames, XML/DTD DTOs & marshalling, TransitionDelegates,
  the WAF request/screen-flow/event/template engine, smart taglibs, and the entire Swing/WebStart admin client are
  **correctly INTENTIONALLY-DROPPED** for Spring MVC + DI + JSON + Thymeleaf, with business intent preserved in services.
- No hidden currency/tax/rounding/number-formatting logic exists anywhere in the util/infra layer (verified). *(infra)*

## Suggested remediation priority

1. **Emails (H3, H4)** — lowest-risk, highest-visibility: add `@JmsListener`-driven approval + completed
   notifications, reusing the existing `MailSender` port + `OrderMailComposer`.
2. **Profile defaults + sign-on locale (H8, H9)** — one-line default change + apply `preferredLanguage`
   on authentication; fix the misleading `Profile.java` javadoc.
3. **Catalog search + ordering + `getItem.category` (H6, M1, M2)** — restore tokenized multi-field search
   JPQL and `order by name`; populate `category` from `catid`.
4. **Checkout address validation (H7)** — decide first: was dropping ship/bill capture an intentional
   scope cut, or a regression? (Interacts with the publish-only storefront decision.)
5. **Partial shipment + backorder retry (H1, H2, M8)** — H2 (retry-on-restock) has since been restored
   *without* persisting supplier POs, via an event-driven re-drive (RestockEvent → OPC re-dispatches
   APPROVED backorders); see the resolution table. H1 (all-or-nothing) + M8 (no persisted supplier PO)
   remain intentional keeps — restoring true partial shipment would still require the supplier PO store.
