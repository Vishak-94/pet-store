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

    /** GET — all orders as summaries, newest-received first: /api/orders/all */
    public static final String ORDERS_ALL = "/api/orders/all";

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

    /**
     * Query-parameter names on the wire. Shared by the client (which sets them) and the
     * controller (which reads them via {@code @RequestParam}). Contract literals — kept as
     * constants, never externalized to config.
     */
    public static final String PARAM_STATUS = "status";
    public static final String PARAM_START = "start";
    public static final String PARAM_END = "end";
    public static final String PARAM_CATEGORY = "category";

    /** Path variable name used in the {@code {id}} templated paths above. */
    public static final String PATH_VAR_ID = "id";
}
