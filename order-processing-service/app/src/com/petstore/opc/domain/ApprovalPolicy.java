package com.petstore.opc.domain;

import org.springframework.stereotype.Component;

/**
 * Auto-approval rule — carried over from the legacy PurchaseOrderMDB.canIApprove:
 * auto-approve USD &lt; 500, JPY &lt; 50000; else needs manual admin approval.
 *
 * <p>The legacy method keyed on {@code Locale} but its own comment admitted it was
 * "a stub for converting currency" — the thresholds ($500 vs ¥50000) are money amounts,
 * not localisation. This policy therefore keys on the order's ISO 4217 <b>currency</b>,
 * which is the dimension the rule always meant. The thresholds are unchanged, so US
 * (USD) and Japan (JPY) orders approve exactly as before.
 */
@Component
public class ApprovalPolicy {

    /**
     * Order-total ceilings under which an order auto-approves, per currency (legacy
     * {@code PurchaseOrderMDB.canIApprove}). Kept as named domain constants rather than
     * inline magic numbers so the business rule is self-documenting; these are fixed
     * legacy parity values, not per-environment config, so they are not externalized.
     */
    private static final double USD_AUTO_APPROVE_CEILING = 500d;
    private static final double JPY_AUTO_APPROVE_CEILING = 50000d;

    /** ISO 4217 currency assumed when an order carries none (older messages / storefront default). */
    private static final String DEFAULT_CURRENCY = "USD";

    /**
     * Whether an order auto-approves without human review: {@code true} for a {@code USD}
     * order under {@value #USD_AUTO_APPROVE_CEILING} or a {@code JPY} order under
     * {@value #JPY_AUTO_APPROVE_CEILING}; any other currency (or an over-ceiling order)
     * returns {@code false} and stays PENDING for a manual admin decision. Pure decision
     * logic — no persistence or side effects (legacy {@code PurchaseOrderMDB.canIApprove}).
     *
     * @param totalPrice the order total to test against the currency ceiling
     * @param currency   the order's ISO 4217 currency ({@code USD}/{@code JPY} have a ceiling);
     *                   {@code null}/blank is treated as {@code USD} (storefront default)
     * @return {@code true} to auto-approve, {@code false} to leave PENDING
     */
    public boolean canAutoApprove(double totalPrice, String currency) {
        String c = (currency == null || currency.isBlank()) ? DEFAULT_CURRENCY : currency;
        if (DEFAULT_CURRENCY.equals(c)) {
            return totalPrice < USD_AUTO_APPROVE_CEILING;
        }
        if ("JPY".equals(c)) {
            return totalPrice < JPY_AUTO_APPROVE_CEILING;
        }
        return false;
    }
}
