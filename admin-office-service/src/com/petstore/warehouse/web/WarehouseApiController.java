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

    @GetMapping(OrderProcessingEndpoints.ORDERS)
    public OrdersByStatus ordersByStatus(
            @RequestParam(value = OrderProcessingEndpoints.PARAM_STATUS, defaultValue = DEFAULT_STATUS) String status,
            HttpServletRequest req) {
        return opc.ordersByStatus(status, bearer(req));
    }

    @PostMapping(OrderProcessingEndpoints.ORDER_APPROVE)
    public ResponseEntity<Map<String, String>> approve(@PathVariable String id, HttpServletRequest req) {
        opc.approve(id, bearer(req));
        return ResponseEntity.ok(Map.of(KEY_ORDER_ID, id, KEY_STATUS, STATUS_APPROVED));
    }

    @PostMapping(OrderProcessingEndpoints.ORDER_DENY)
    public ResponseEntity<Map<String, String>> deny(@PathVariable String id, HttpServletRequest req) {
        opc.deny(id, bearer(req));
        return ResponseEntity.ok(Map.of(KEY_ORDER_ID, id, KEY_STATUS, STATUS_DENIED));
    }

    @GetMapping(OrderProcessingEndpoints.ORDER_BY_ID)
    public ResponseEntity<?> order(@PathVariable String id, HttpServletRequest req) {
        return opc.getOrder(id, bearer(req))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Atomic batch approval (legacy updateOrders/OrderApproval) — delegates to the OPC. */
    @PostMapping(OrderProcessingEndpoints.ORDER_APPROVALS)
    public ResponseEntity<Void> updateOrders(@RequestBody OrderApprovalDto approval, HttpServletRequest req) {
        opc.updateOrders(approval, bearer(req));
        return ResponseEntity.ok().build();
    }

    /** Sales aggregation over a date range (legacy getChartInfo) — delegates to the OPC. */
    @GetMapping(OrderProcessingEndpoints.SALES)
    public SalesReportDto sales(
            @RequestParam(OrderProcessingEndpoints.PARAM_START) String start,
            @RequestParam(OrderProcessingEndpoints.PARAM_END) String end,
            @RequestParam(value = OrderProcessingEndpoints.PARAM_CATEGORY, required = false) String category,
            HttpServletRequest req) {
        return opc.sales(start, end, category, bearer(req));
    }
}
