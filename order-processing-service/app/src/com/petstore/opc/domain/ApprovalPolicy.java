package com.petstore.opc.domain;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Auto-approval rule — carried over from the legacy PurchaseOrderMDB.canIApprove:
 * auto-approve US &lt; 500, Japan &lt; 50000; else needs manual admin approval.
 */
@Component
public class ApprovalPolicy {

    /**
     * Order-total ceilings under which an order auto-approves, per locale (legacy
     * {@code PurchaseOrderMDB.canIApprove}). Kept as named domain constants rather than
     * inline magic numbers so the business rule is self-documenting; these are fixed
     * legacy parity values, not per-environment config, so they are not externalized.
     */
    private static final double US_AUTO_APPROVE_CEILING = 500d;
    private static final double JAPAN_AUTO_APPROVE_CEILING = 50000d;

    public boolean canAutoApprove(double totalPrice, Locale locale) {
        if (Locale.US.equals(locale)) {
            return totalPrice < US_AUTO_APPROVE_CEILING;
        }
        if (Locale.JAPAN.equals(locale)) {
            return totalPrice < JAPAN_AUTO_APPROVE_CEILING;
        }
        return false;
    }
}
