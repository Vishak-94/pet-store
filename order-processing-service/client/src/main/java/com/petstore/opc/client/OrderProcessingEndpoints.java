package com.petstore.opc.client;

/**
 * The order-processing-service HTTP contract — the modern OPCAdminFacade. Path
 * constants shared by the server (which maps them) and clients (admin-office-service
 * calls them), so the two can't drift.
 */
public final class OrderProcessingEndpoints {

    private OrderProcessingEndpoints() {
    }

    public static final String DEFAULT_BASE_URL = "http://localhost:8088";

    /** GET — order ids by status: /api/orders?status=PENDING */
    public static final String ORDERS = "/api/orders";

    /** GET — full order detail: /api/orders/{id} */
    public static final String ORDER_BY_ID = "/api/orders/{id}";

    /** GET — just the status: /api/orders/{id}/status */
    public static final String ORDER_STATUS = "/api/orders/{id}/status";

    /** POST — approve: /api/orders/{id}/approve */
    public static final String ORDER_APPROVE = "/api/orders/{id}/approve";

    /** POST — deny: /api/orders/{id}/deny */
    public static final String ORDER_DENY = "/api/orders/{id}/deny";

    /** POST — atomic batch status update (legacy updateOrders/OrderApproval): /api/orders/approvals */
    public static final String ORDER_APPROVALS = "/api/orders/approvals";

    /** GET — sales aggregation over a date range (legacy getChartInfo): /api/sales?start=&end=&category= */
    public static final String SALES = "/api/sales";
}
