# Adding `currency` to the order model (design note — review before code)

> **Decision (locked in with the user):**
> 1. Add a **`currency`** field (ISO 4217, e.g. `USD` / `JPY`) **alongside** the existing
>    `locale` — do not remove or rename `locale`.
> 2. Approval keeps the **legacy thresholds unchanged** (auto-approve USD < 500, JPY < 50000,
>    else PENDING) but selects the threshold **by currency instead of locale**.
> 3. This is a **design note first** — no code until it's approved.
>
> Status: **design only. Nothing implemented yet.**

---

## 1. Why — the legacy ground truth

Investigated in the legacy source (`petstore1.3.1_02`):

- **The field is genuinely named `locale`** — `PurchaseOrder.java` holds a `java.util.Locale locale`
  (default `Locale.US`), serialized as the XML attribute `"locale"`. It is **not** called
  `currency` anywhere. So the current app's *name* is faithful to legacy.
- **But the approval logic conflates locale with currency.** `PurchaseOrderMDB.canIApprove`
  carries this exact comment and code:

  ```java
  /** Just a stub for converting currency. This method is used for
   *  demonstrating the petstore ... */
  private boolean canIApprove(PurchaseOrder po) {
    Locale locale = po.getLocale();
    if (locale.equals(Locale.US))    { if (po.getTotalPrice() < 500)   return true; }
    else if (locale.equals(Locale.JAPAN)) { if (po.getTotalPrice() < 50000) return true; }
    return false;
  }
  ```

  The thresholds (`< 500` vs `< 50000`) only make sense as **money amounts in different
  currencies** ($500 ≈ ¥50000). The developers' own comment calls it *"a stub for converting
  currency."* So `locale` was **standing in for currency** — the money dimension the model never
  had.

**Conclusion:** "locale is mistaken as currency" is *semantically* correct (that's the money
dimension the approval logic actually needs), even though legacy literally named the field
`locale`. The fix is to introduce the missing **currency** concept as its own field so the two
stop being conflated — and to key approval on it.

> Extra wrinkle in the current app: the storefront **hardcodes `locale = en_US`** on every
> published order (`OrderService.LOCALE = Locale.US`), so today *every* order is treated as USD
> and the Japan branch is effectively dead. Introducing an explicit `currency` makes the money
> dimension real instead of always-USD-by-accident.

---

## 2. What changes — the currency field

- **Type:** `String`, an **ISO 4217** code (`"USD"`, `"JPY"`). String (not `java.util.Currency`)
  to match how events are serialized and to stay JSON/BSON-friendly.
- **Placement:** a new field **alongside** `locale` on the `PurchaseOrderEvent` and the OPC order
  aggregate. `locale` stays for i18n/formatting; `currency` carries money.
- **Nullability / back-compat:** **nullable and additive** (per the messaging contract rule #3 —
  older in-flight messages must still deserialize). When absent, **default to `USD`** (matches the
  current always-`en_US` → USD behaviour, so existing orders are unaffected).

---

## 3. Where it flows (every touch-point)

```
 checkout form ──▶ PurchaseOrderEvent ──(JMS)──▶ OPC FulfilmentService ──▶ ApprovalPolicy
                        (+currency)                  (read currency)         (threshold by currency)
                                                          │
                                                          ▼
                                              order document / wh_order (+currency)
```

| # | File | Change |
|---|---|---|
| 1 | `petstore-messaging/.../events/PurchaseOrderEvent.java` | Add `String currency` component (nullable, additive — append, never reorder). Update `EventSerializationTest`. |
| 2 | `petstore-app-v1/.../order/service/OrderService.java` | Populate `currency` on the built event. Since `locale` is hardcoded `en_US` today, currency defaults to `"USD"` (a matching `CURRENCY` constant) — a real per-order value is a later, separate step. |
| 3 | `order-processing-service/.../domain/ApprovalPolicy.java` | New overload `canAutoApprove(double totalPrice, String currency)` keyed on currency: `USD` < 500, `JPY` < 50000, else false. Keep the constants (`US_AUTO_APPROVE_CEILING`, `JAPAN_AUTO_APPROVE_CEILING`) — same values, renamed to `USD_/JPY_` for clarity. |
| 4 | `order-processing-service/.../service/FulfilmentService.java` | Read `incoming.currency()` (default `USD` if null) and pass to the new policy overload instead of `localeOf(incoming.locale())`. `locale` still stored on the order. |
| 5 | `order-processing-service/.../domain/WarehouseOrder.java` (record) | Add `currency` component → **11-arg record**. Update **every** `new WarehouseOrder(...)` call site (`FulfilmentService`, `OrderListener`, `JpaOrderStore`, both tests) — invariant #6. |
| 6 | `order-processing-service/.../repository/jpa/WarehouseOrderEntity.java` + Flyway `V4__order_currency.sql` | Add `currency` column (`VARCHAR`, default `'USD'`, backfill existing rows). |
| 7 | `docs/MONGODB_SCHEMA.md` | Add `currency` to the `orders` document field table (String, ISO 4217, nullable→default USD). |
| 8 | `order-processing-client` `OrderDtos` | If the order DTO exposes locale to admin-office, add currency too (backward-compatible). |

### 3.1 The new ApprovalPolicy (parity-preserving)

```java
// USD < 500, JPY < 50000 auto-approve; else PENDING. Same numbers as legacy canIApprove,
// now keyed on the order's currency rather than its locale.
public boolean canAutoApprove(double totalPrice, String currency) {
    if ("USD".equals(currency)) return totalPrice < USD_AUTO_APPROVE_CEILING;   // 500
    if ("JPY".equals(currency)) return totalPrice < JPY_AUTO_APPROVE_CEILING;   // 50000
    return false;
}
```

Behaviour for existing US/Japan orders is **identical** — a US order (USD) still auto-approves
under 500, a Japan order (JPY) under 50000. Only the *key* changes (currency, not locale), which
is exactly what the legacy comment says it always meant.

---

## 4. Parity impact

- **No behaviour change for existing orders.** Every order today is `en_US`/USD, defaults keep it
  USD, thresholds are unchanged → same approve/PENDING outcome.
- **`OrderStatus` set unchanged** (PENDING/APPROVED/DENIED/COMPLETED). No `SHIPPED_PART`.
- **Legacy `locale` name preserved** — we add, not rename. i18n/formatting behaviour untouched.
- The old `ApprovalPolicy.canAutoApprove(double, Locale)` overload can be **kept temporarily**
  (deprecated) or removed once all callers move to the currency overload — decide at implement time.

## 5. Open question for implement time

Where does a **real** per-order currency come from? The storefront hardcodes `en_US`. Options
(NOT part of this note — flagging for later): derive from the shopper's locale
(`Currency.getInstance(locale)`), from the catalog/price locale, or a checkout selector. For now
currency defaults to **USD**, matching today's behaviour — making it *vary* per order is a
separate feature.

---

## 6. Verification plan (when implemented)

- `petstore-messaging`: `EventSerializationTest` round-trips the new `currency` field; an old
  message without it still deserializes (→ null → USD default).
- OPC: an `ApprovalPolicyTest` pinning USD<500, JPY<50000, unknown→false; `FulfilmentService`
  intake test that a USD 499 order auto-approves and a USD 500 stays PENDING (unchanged outcomes).
- `@DataJpaTest` for the `V4` column + backfill.
- Build order (libs first): `petstore-messaging install` → `order-processing-service install`
  → `petstore-app-v1 package`. All existing tests stay green.
```
