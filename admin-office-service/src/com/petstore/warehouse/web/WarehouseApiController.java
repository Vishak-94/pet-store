package com.petstore.warehouse.web;

import com.petstore.opc.client.OrderDtos.OrderApprovalDto;
import com.petstore.opc.client.OrderDtos.OrdersByStatus;
import com.petstore.opc.client.OrderDtos.SalesReportDto;
import com.petstore.opc.client.OrderProcessingClient;
import com.petstore.opc.client.OrderProcessingEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin JSON API — a thin proxy over order-processing-service's facade (the legacy
 * admin.ear → OPCAdminFacade relationship). Forwards the caller's Bearer token.
 * Secured to ROLE_ADMIN. The routes intentionally mirror the OPC facade one-for-one,
 * so they reuse the SDK's {@link OrderProcessingEndpoints} path/param constants —
 * the proxy and the facade cannot drift apart.
 */
@RestController
public class WarehouseApiController {

    /** Default status filter when {@code ?status=} is omitted (the human-review queue). */
    private static final String DEFAULT_STATUS = "PENDING";
    /** JSON response body keys + the status values echoed for approve/deny. */
    private static final String KEY_ORDER_ID = "orderId";
    private static final String KEY_STATUS = "status";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_DENIED = "DENIED";

    private final OrderProcessingClient opc;

    public WarehouseApiController(OrderProcessingClient opc) {
        this.opc = opc;
    }

    private static String bearer(HttpServletRequest req) {
        String h = req.getHeader(HttpHeaders.AUTHORIZATION);
        return h != null ? h : "";
    }

    /**
     * List order ids for a workflow status (defaults to the PENDING review queue).
     * Reads the admin's JWT from the {@code Authorization} header and DELEGATES to the
     * OPC ({@link OrderProcessingClient#ordersByStatus}) — this service owns no data.
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/orders?status=PENDING
     * Authorization: Bearer <admin JWT>
     * }</pre>
     *
     * <p>Example response (the {@link OrdersByStatus} shape):
     * <pre>{@code
     * HTTP/1.1 200 OK
     * { "status": "PENDING", "orderIds": ["1001", "1002"], "count": 2 }
     * }</pre>
     *
     * <p>Errors: 401/403 if not an ADMIN (re-enforced by the OPC); 502/503 if the OPC is
     * unreachable/erroring (mapped by {@code ApiExceptionHandler}).
     */
    @GetMapping(OrderProcessingEndpoints.ORDERS)
    public OrdersByStatus ordersByStatus(
            @RequestParam(value = OrderProcessingEndpoints.PARAM_STATUS, defaultValue = DEFAULT_STATUS) String status,
            HttpServletRequest req) {
        return opc.ordersByStatus(status, bearer(req));
    }

    /**
     * Approve a single order. DELEGATES to {@link OrderProcessingClient#approve} (the OPC
     * performs the actual PENDING&rarr;APPROVED transition and event emission); this method
     * just echoes the outcome.
     *
     * <p>Example request:
     * <pre>{@code
     * POST /api/orders/1001/approve
     * Authorization: Bearer <admin JWT>
     * }</pre>
     *
     * <p>Example response:
     * <pre>{@code
     * HTTP/1.1 200 OK
     * { "orderId": "1001", "status": "APPROVED" }
     * }</pre>
     *
     * <p>Errors: 401/403 if not ADMIN; 502/503 if the OPC is unreachable/erroring.
     */
    @PostMapping(OrderProcessingEndpoints.ORDER_APPROVE)
    public ResponseEntity<Map<String, String>> approve(@PathVariable String id, HttpServletRequest req) {
        opc.approve(id, bearer(req));
        return ResponseEntity.ok(Map.of(KEY_ORDER_ID, id, KEY_STATUS, STATUS_APPROVED));
    }

    /**
     * Deny a single order. DELEGATES to {@link OrderProcessingClient#deny} (the OPC performs
     * the PENDING&rarr;DENIED transition and event emission); this method echoes the outcome.
     *
     * <p>Example request:
     * <pre>{@code
     * POST /api/orders/1001/deny
     * Authorization: Bearer <admin JWT>
     * }</pre>
     *
     * <p>Example response:
     * <pre>{@code
     * HTTP/1.1 200 OK
     * { "orderId": "1001", "status": "DENIED" }
     * }</pre>
     *
     * <p>Errors: 401/403 if not ADMIN; 502/503 if the OPC is unreachable/erroring.
     */
    @PostMapping(OrderProcessingEndpoints.ORDER_DENY)
    public ResponseEntity<Map<String, String>> deny(@PathVariable String id, HttpServletRequest req) {
        opc.deny(id, bearer(req));
        return ResponseEntity.ok(Map.of(KEY_ORDER_ID, id, KEY_STATUS, STATUS_DENIED));
    }

    /**
     * Fetch full order detail. DELEGATES to {@link OrderProcessingClient#getOrder};
     * returns 200 with the {@link com.petstore.opc.client.OrderDtos.OrderView} or 404 when
     * the OPC has no such order.
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/orders/1001
     * Authorization: Bearer <admin JWT>
     * }</pre>
     *
     * <p>Example response (the {@code OrderView} shape):
     * <pre>{@code
     * HTTP/1.1 200 OK
     * {
     *   "orderId": "1001", "userId": "jdoe", "emailId": "jane@example.com",
     *   "locale": "en_US", "totalPrice": 129.98, "status": "PENDING",
     *   "lines": [
     *     { "itemId": "EST-1", "productId": "FELV-01", "categoryId": "CATS",
     *       "quantity": 2, "unitPrice": 64.99 }
     *   ]
     * }
     * }</pre>
     *
     * <p>Errors: 404 if no such order; 401/403 if not ADMIN; 502/503 if the OPC is
     * unreachable/erroring.
     */
    @GetMapping(OrderProcessingEndpoints.ORDER_BY_ID)
    public ResponseEntity<?> order(@PathVariable String id, HttpServletRequest req) {
        return opc.getOrder(id, bearer(req))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Atomic batch approval (legacy updateOrders/OrderApproval) — DELEGATES to
     * {@link OrderProcessingClient#updateOrders}. The OPC applies every status change in one
     * transaction (all-or-nothing). Returns 200 with an empty body on success.
     *
     * <p>Example request (the {@link OrderApprovalDto} shape; at least one change required):
     * <pre>{@code
     * POST /api/orders/approvals
     * Authorization: Bearer <admin JWT>
     * Content-Type: application/json
     *
     * {
     *   "orders": [
     *     { "orderId": "1001", "newStatus": "APPROVED" },
     *     { "orderId": "1002", "newStatus": "DENIED" }
     *   ]
     * }
     * }</pre>
     *
     * <p>Example response:
     * <pre>{@code
     * HTTP/1.1 200 OK
     * }</pre>
     *
     * <p>Errors: 400 on an empty batch or a blank {@code orderId}/{@code newStatus}
     * (bean-validation); 401/403 if not ADMIN; 502/503 if the OPC is unreachable/erroring.
     */
    @PostMapping(OrderProcessingEndpoints.ORDER_APPROVALS)
    public ResponseEntity<Void> updateOrders(@RequestBody OrderApprovalDto approval, HttpServletRequest req) {
        opc.updateOrders(approval, bearer(req));
        return ResponseEntity.ok().build();
    }

    /**
     * Sales aggregation over a date range (legacy getChartInfo) — DELEGATES to
     * {@link OrderProcessingClient#sales}. {@code start}/{@code end} are required; the
     * optional {@code category} switches the grouping (present &rarr; group by item within
     * that category; absent &rarr; group by category).
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/sales?start=2026-01-01&end=2026-03-31&category=DOGS
     * Authorization: Bearer <admin JWT>
     * }</pre>
     *
     * <p>Example response (the {@link SalesReportDto} shape):
     * <pre>{@code
     * HTTP/1.1 200 OK
     * {
     *   "groupBy": "item",
     *   "buckets": [
     *     { "key": "EST-6", "revenue": 1899.85, "quantity": 5 },
     *     { "key": "EST-7", "revenue": 640.00, "quantity": 8 }
     *   ]
     * }
     * }</pre>
     *
     * <p>Errors: 400 if {@code start}/{@code end} are missing; 401/403 if not ADMIN;
     * 502/503 if the OPC is unreachable/erroring.
     */
    @GetMapping(OrderProcessingEndpoints.SALES)
    public SalesReportDto sales(
            @RequestParam(OrderProcessingEndpoints.PARAM_START) String start,
            @RequestParam(OrderProcessingEndpoints.PARAM_END) String end,
            @RequestParam(value = OrderProcessingEndpoints.PARAM_CATEGORY, required = false) String category,
            HttpServletRequest req) {
        return opc.sales(start, end, category, bearer(req));
    }
}
