package com.petstore.warehouse.web;

import com.petstore.opc.client.OrderDtos.OrderApprovalDto;
import com.petstore.opc.client.OrderDtos.OrdersByStatus;
import com.petstore.opc.client.OrderDtos.SalesReportDto;
import com.petstore.opc.client.OrderProcessingClient;
import jakarta.servlet.http.HttpServletRequest;
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
 * Secured to ROLE_ADMIN.
 */
@RestController
public class WarehouseApiController {

    private final OrderProcessingClient opc;

    public WarehouseApiController(OrderProcessingClient opc) {
        this.opc = opc;
    }

    private static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return h != null ? h : "";
    }

    @GetMapping("/api/orders")
    public OrdersByStatus ordersByStatus(@RequestParam(defaultValue = "PENDING") String status,
                                         HttpServletRequest req) {
        return opc.ordersByStatus(status, bearer(req));
    }

    @PostMapping("/api/orders/{id}/approve")
    public ResponseEntity<Map<String, String>> approve(@PathVariable String id, HttpServletRequest req) {
        opc.approve(id, bearer(req));
        return ResponseEntity.ok(Map.of("orderId", id, "status", "APPROVED"));
    }

    @PostMapping("/api/orders/{id}/deny")
    public ResponseEntity<Map<String, String>> deny(@PathVariable String id, HttpServletRequest req) {
        opc.deny(id, bearer(req));
        return ResponseEntity.ok(Map.of("orderId", id, "status", "DENIED"));
    }

    @GetMapping("/api/orders/{id}")
    public ResponseEntity<?> order(@PathVariable String id, HttpServletRequest req) {
        return opc.getOrder(id, bearer(req))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Atomic batch approval (legacy updateOrders/OrderApproval) — delegates to the OPC. */
    @PostMapping("/api/orders/approvals")
    public ResponseEntity<Void> updateOrders(@RequestBody OrderApprovalDto approval, HttpServletRequest req) {
        opc.updateOrders(approval, bearer(req));
        return ResponseEntity.ok().build();
    }

    /** Sales aggregation over a date range (legacy getChartInfo) — delegates to the OPC. */
    @GetMapping("/api/sales")
    public SalesReportDto sales(@RequestParam String start, @RequestParam String end,
                                @RequestParam(required = false) String category,
                                HttpServletRequest req) {
        return opc.sales(start, end, category, bearer(req));
    }
}
