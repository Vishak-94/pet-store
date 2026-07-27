package com.petstore.opc.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the currency-keyed auto-approval rule (legacy {@code PurchaseOrderMDB.canIApprove}):
 * USD auto-approves under 500, JPY under 50000, anything else stays PENDING. Null/blank
 * currency is treated as USD (the storefront default), so pre-currency messages behave
 * exactly as before — this is the parity guarantee for the locale→currency change.
 */
class ApprovalPolicyTest {

    private final ApprovalPolicy policy = new ApprovalPolicy();

    @Test
    void usd_underCeiling_autoApproves() {
        assertTrue(policy.canAutoApprove(499.99, "USD"));
    }

    @Test
    void usd_atOrOverCeiling_staysPending() {
        assertFalse(policy.canAutoApprove(500.0, "USD"));
        assertFalse(policy.canAutoApprove(750.0, "USD"));
    }

    @Test
    void jpy_underCeiling_autoApproves() {
        assertTrue(policy.canAutoApprove(49999.0, "JPY"));
    }

    @Test
    void jpy_atOrOverCeiling_staysPending() {
        assertFalse(policy.canAutoApprove(50000.0, "JPY"));
    }

    @Test
    void unknownCurrency_staysPending() {
        assertFalse(policy.canAutoApprove(1.0, "EUR"));
    }

    @Test
    void nullOrBlankCurrency_treatedAsUsd() {
        assertTrue(policy.canAutoApprove(100.0, null));
        assertTrue(policy.canAutoApprove(100.0, "  "));
        assertFalse(policy.canAutoApprove(600.0, null));   // still bound by the USD ceiling
    }
}
