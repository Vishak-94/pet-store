# Functional Walkthrough — Every Use-Case, Every Edge (Behavioral Contract)

*Source-verified. This is the contract the characterization tests must pin before migrating.*

## Entry point & request flow

Everything enters through **one servlet** — `MainServlet` (mapped to `*.do`). No per-page servlets.

```
Browser GET/POST *.do
  → MainServlet.doProcess()          (single front controller)
      1. look up URL in mappings.xml
      2. if useFlowHandler → run FlowHandler
      3. RequestProcessor turns *.do into a WAF Event
  → EJBControllerLocalEJB.processEvent()   (stateful session bean)
      StateMachine maps Event → EJBAction (command)
  → XxxEJBAction.perform(event)      (business logic, calls component EJBs)
  → ScreenFlowManager                (next JSP screen; on exception → error screen)
  → JSP rendered back to browser
```

**Exception → screen routes (the edge-case map):**

| Exception | Routed to screen |
|---|---|
| `ShoppingCartEmptyOrderException` | `cart_empty_order_error.screen` |
| `DuplicateAccountException` | `duplicate_account.screen` |
| `GeneralFailureException` | `error.screen` |

## Use-cases 1–3: Browse · Sign-on · Register

| Use-case (entry) | Happy path | Edge cases where it FAILS / surprises |
|---|---|---|
| Browse catalog `GET /category.do?id=CATS` | CatalogEJB→DAO returns locale rows, paginated | Unknown id → **empty list, NOT 404**; `start=9999` → empty; `start<0` → SQL error; DB down → error.screen |
| Sign on `POST /signon.do` | `authenticate(user,pwd)=true` → session signed-on | Wrong pwd & unknown user → **same false** (no distinction); no lockout/counter; `userName` case-sensitive (Jane≠jane) |
| Register `POST /createcustomer.do` (FlowHandler) | Creates Customer→Account→Profile→ContactInfo→Address graph | Duplicate username → `DuplicateAccountException` → duplicate_account.screen; partial create rolls back (CMP tx); weak field validation |

## Use-case 4: Shopping cart (richest edge cases)

| Operation | Actual code behaviour | Gotcha / failure edge case |
|---|---|---|
| `ADD_ITEM` | `cart.put(itemID, 1)` | **Re-adding an item RESETS qty to 1** — does NOT increment. "Fixing" this breaks the contract. |
| `UPDATE_ITEMS` qty>0 | `remove` then `put(qty)` | Sets absolute quantity ✓ |
| `UPDATE_ITEMS` qty≤0 | `remove`, does NOT re-add | **Silently deletes the line item.** Not an error. |
| `UPDATE` unknown itemId | puts a line for it | Item not in catalog → `getSubTotal` later throws (dangling ref) |
| `getSubTotal()` | loops items; calls `CatalogHelper.getItem` **per line** | Item removed from catalog but still in cart → `CatalogException` → subtotal fails |
| Session timeout | stateful bean destroyed | **Cart silently empties.** Migration to `@SessionScope` must reproduce; a singleton would leak carts across users |

## Use-cases 5–6: Checkout & fulfilment (async)

| Step (entry) | Behaviour | Edge case where it fails |
|---|---|---|
| Checkout `POST /order.do` | PO built from cart+customer → XML → `jms/AsyncSenderQueue` → order_complete.screen | Empty cart → `ShoppingCartEmptyOrderException`; not signed on → blocked by filter |
| OPC consumes PO | `PurchaseOrderMDB` → ProcessManager status PENDING; large order → OrderApprovalQueue | JMS broker down → enqueue fails, **NO order created**; duplicate redelivery → MDB must be **IDEMPOTENT** |
| Supplier fulfilment | `SupplierOrderMDB` decrements InventoryEJB, ships, returns Invoice (XML) | Concurrent orders for last unit → inventory race; Invoice for unknown orderId → no manager row → silent drop |
| Notifications | Mail MDBs send confirmation email | Mail server down → redelivery → must not double-send |

## Use-cases 7–8 + cross-cutting failures

| Use-case | Behaviour | Edge case |
|---|---|---|
| `changelocale.do?locale=ja_JP` | FlowHandler sets session locale; re-renders | Unsupported locale (no `_details` rows) → blank/fallback text |
| `signoff.do` | Invalidate session → signoff.screen | Cart LOST on signoff (stateful bean destroyed) — intended |
| Admin (petstoreadmin.ear) | Swing/JWS client → approve orders via OPC | **Java Web Start WON'T launch on modern JVMs**; re-approve shipped order must be idempotent |

**Cross-cutting failure modes (every use-case):**
- DB (Cloudscape) unreachable → `*DAOSysException`/`EJBException` → `GeneralFailureException` → error.screen
- JMS broker down → async steps stall; user-facing enqueue → error.screen
- Session loss/timeout → stateful cart & sign-on vanish → bounced to signon
- Malformed XML message → MDB `onMessage` throws → JMS redelivery loop (poison message) unless a DLQ exists
- Locale with no `_details` rows → blank/fallback text, never an error

## The dangerous edge cases (pin these or regress)

These are the behaviours an agent (or human) will "accidentally fix" and break the contract — forbidden shortcuts #1 & #10:

1. `addItem` **RESETS** quantity to 1 — does not increment
2. `updateItemQuantity(qty ≤ 0)` **silently DELETES** the line
3. `getSubTotal` **THROWS** on items removed from the catalog
4. Unknown catalog id returns **EMPTY, not 404**
5. Checkout must be **IDEMPOTENT** under JMS redelivery (no double order)
6. Sign-on does **NOT distinguish** bad-password from unknown-user

---

# Complete API Inventory (all 4 apps — verified by full source scan)

## Synchronous HTTP + SOAP endpoints

| App | Type | Endpoint / API | Purpose & edge case |
|---|---|---|---|
| petstore | HTTP `*.do` | cart · order · signon · createcustomer · customer · changelocale · signoff | Storefront; front controller MainServlet |
| petstore | Servlet | `/Populate` | Bootstraps catalog + accounts from XML; ~15 params; re-run w/o `forcefully` → skips if data exists |
| petstore | Filter | `SignOnFilter` | Guards `customer.do/.screen`, `enter_order_information.screen`, `signon_welcome.screen` → redirect to signon, replay original URL |
| opc | SOAP (JAX-RPC) | `OPCService.submitInvoice(Source)` | Receives supplier invoice XML; bad XML → `InvalidInvoiceException` |
| supplier | SOAP (JAX-RPC) | `SupplierService.submitOrder(Source)`, `queryOrderStatus(id)` | Receive PO / query status; bad order → `InvalidOrderException`; unknown id → `UnknownOrderIdException` |
| supplier | Servlet | `/RcvrRequestProcessor`, `/Populate` | Inventory display & receive; seed inventory |
| admin | Servlet | `/AdminRequestProcessor`, `/ApplRequestProcessor` | Back-office order approval; also Swing/JWS client — won't launch on modern JVMs |

## Async APIs — 8 MDBs & JMS destinations

| MDB (consumer) | App | Destination | Responsibility |
|---|---|---|---|
| PurchaseOrderMDB | opc | `jms/PurchaseOrderQueue` | Consume checkout PO → ProcessManager PENDING; route large orders to approval |
| OrderApprovalMDB | opc | `jms/OrderApprovalQueue` | Apply admin approve/deny → next transition |
| InvoiceMDB | opc | `jms/InvoiceTopic` **(pub/sub)** | Supplier invoice → status SHIPPED. **A Topic (fan-out), not a queue** |
| MailCompletedOrderMDB | opc | `jms/CompletedOrderMailQueue` | Email order-complete |
| MailInvoiceMDB | opc | `jms/MailQueue` | Email invoice |
| MailOrderApprovalMDB | opc | `jms/OrderApprovalMailQueue` | Email approval outcome |
| SupplierOrderMDB | supplier | (ejb-jar dest) | Fulfil PO, decrement InventoryEJB, ship, return invoice |
| MailerMDB | component | `jms/MailQueue` | Generic mail sender (shared) |

**Async edge cases (all MDBs):** at-least-once delivery → `onMessage` must be **idempotent**; malformed XML → redelivery loop / poison message unless a DLQ exists; `InvoiceTopic` fan-out means multiple subscribers may each react.

---

# Per-Endpoint Data Flow — DB writes + JMS emissions

## Synchronous HTTP endpoints (storefront)

| Endpoint | DB written | JMS emitted | Notes / edge |
|---|---|---|---|
| `GET category/product/item/search.do` | — (reads catalog + `_details`) | none | Read-only; unknown id → empty, not 404 |
| `POST signon.do` | — (reads `UserEJBTable`) | none | `authenticate()` reads only |
| `POST createcustomer.do` | **writes** `UserEJBTable`, `CustomerEJBTable`, `AccountEJBTable`, `ProfileEJBTable`, `ContactInfoEJBTable`, `AddressEJBTable`, `CreditCardEJBTable` | none | Dup username → DuplicateAccount; whole graph rolls back (1 tx) |
| `POST customer.do` (update) | **updates** `AccountEJBTable`, `ContactInfoEJBTable`, `AddressEJBTable`, `CreditCardEJBTable` | none | Protected URL |
| `cart.do` (add/delete/update) | **NONE** — in-memory session bean | none | No DB touch; lost on timeout/signoff |
| `POST order.do` (checkout) | **NONE directly** (`CounterEJBTable`++ for order id) | **→ `jms/AsyncSenderQueue`** (PO as XML) | Empty cart → exception before send; **order NOT persisted here**; cart emptied after |
| `changelocale.do` / `signoff.do` | — | none | Session state only |
| `GET /Populate` | **writes** all catalog + sample account tables | none | `forcefully=true` re-seeds; else skips |

## The async write-chain (where the order is actually written)

| Consumer (MDB) | Consumes from | DB written | JMS emitted (next hop) |
|---|---|---|---|
| PurchaseOrderMDB (opc) | `jms/PurchaseOrderQueue` | INSERT `PurchaseOrderEJBTable` + `LineItemEJBTable`(+join) + ContactInfo/Address/CreditCard; INSERT `ManagerEJBTable` (status PENDING) | if approval → **`jms/OrderApprovalQueue`**; else → supplier |
| OrderApprovalMDB (opc) | `jms/OrderApprovalQueue` | UPDATE `ManagerEJBTable` (APPROVED/DENIED) | **`jms/OrderApprovalMailQueue`** |
| SupplierOrderMDB (supplier) | supplier PO queue | UPDATE `InventoryEJBTable` (--); INSERT `SupplierOrderEJBTable` | **`jms/opc/InvoiceTopic`** (pub/sub) |
| InvoiceMDB (opc) | `jms/opc/InvoiceTopic` (subscriber) | UPDATE `ManagerEJBTable` (SHIPPED/COMPLETED) | **`jms/CompletedOrderMailQueue`** |
| Mail MDBs (opc) | mail queues | — | **`jms/MailQueue`** |
| MailerMDB (component) | `jms/MailQueue` | — | none (sends email — terminal) |

```
order.do ──JMS──▶ AsyncSenderQueue ──▶ PurchaseOrderQueue
  (writes: Counter++)                       │
                                            ▼ PurchaseOrderMDB
                    WRITES: PurchaseOrder, LineItem, ContactInfo,
                            Address, CreditCard, Manager(PENDING)
                                            │
                     ┌──────────────────────┴── large order?
              yes ▼ OrderApprovalQueue          no ▼ → supplier
        OrderApprovalMDB                    SupplierOrderMDB
        UPDATES: Manager(APPROVED)          WRITES: Inventory--, SupplierOrder
                 │                                  │
                 ▼ OrderApprovalMailQueue           ▼ InvoiceTopic (pub/sub!)
                                            InvoiceMDB
                                            UPDATES: Manager(SHIPPED)
                                                  │
                                                  ▼ CompletedOrderMailQueue ─▶ MailQueue ─▶ email
```

## Migration-critical observations

1. **Write-side is entirely async.** No HTTP request except registration/profile writes business data synchronously. Checkout is fire-and-forget — making it synchronous changes the consistency model (record as an ADR).
2. **`ManagerEJBTable` is the workflow spine** — written by 3 MDBs (PurchaseOrder→create, OrderApproval→update, Invoice→update). Consolidate behind one `OrderStatusService`.
3. **Invoice is a Topic, not a Queue** — `InvoiceMDB` is a *subscriber*; switching to point-to-point changes delivery semantics.
4. **Idempotency everywhere on the write path** — every MDB write is exposed to at-least-once redelivery; characterization tests must assert idempotency.
