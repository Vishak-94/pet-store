package com.petstore.opc.domain;

import java.util.List;

/**
 * Aggregated sales figures over a date range — the migrated form of the legacy
 * {@code OPCAdminFacadeEJB.getChartInfo} result. {@code groupBy} is
 * {@code "category"} when no category filter is applied (revenue/quantity summed
 * per category) or {@code "item"} when filtered to a single category (summed per
 * item). Each bucket carries BOTH the legacy REVENUE (Σ qty·unitPrice) and ORDERS
 * (Σ quantity) figures the old charts requested separately. Framework-free.
 */
public record SalesReport(String groupBy, List<SalesBucket> buckets) {

    /**
     * The two {@link #groupBy} discriminators, echoed to the client so it knows how to
     * label the chart axis. Kept as named constants (not scattered literals) so the
     * aggregation adapter and any reader agree on the exact spelling.
     */
    public static final String GROUP_BY_CATEGORY = "category";
    public static final String GROUP_BY_ITEM = "item";

    /** One aggregation bucket keyed by category id or item id. */
    public record SalesBucket(String key, double revenue, int quantity) {
    }
}
