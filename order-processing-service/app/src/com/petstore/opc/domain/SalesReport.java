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

    /** One aggregation bucket keyed by category id or item id. */
    public record SalesBucket(String key, double revenue, int quantity) {
    }
}
