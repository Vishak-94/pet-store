package com.petstore.opc.domain;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Auto-approval rule — carried over from the legacy PurchaseOrderMDB.canIApprove:
 * auto-approve US &lt; 500, Japan &lt; 50000; else needs manual admin approval.
 */
@Component
public class ApprovalPolicy {

    public boolean canAutoApprove(double totalPrice, Locale locale) {
        if (Locale.US.equals(locale)) {
            return totalPrice < 500d;
        }
        if (Locale.JAPAN.equals(locale)) {
            return totalPrice < 50000d;
        }
        return false;
    }
}
