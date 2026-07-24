package com.petstore.warehouse.web;

import com.petstore.warehouse.domain.OrderStatus;
import com.petstore.warehouse.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * JSON API for warehouse — the programmatic equivalents of the legacy admin
 * OPCAdminFacade: order approval + status. Secured to ROLE_ADMIN. Inventory now
 * lives in inventory-service (SUPPLIER).
 */
@RestController
public class WarehouseApiController {

    private final AdminService admin;

    public WarehouseApiController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/api/orders")
    public Map<String, Object> ordersByStatus(@RequestParam(defaultValue = "PENDING") String status) {
        OrderStatus s = OrderStatus.valueOf(status.toUpperCase());
        List<String> ids = admin.ordersByStatus(s);
        return Map.of("status", s.toString(), "orderIds", ids, "count", ids.size());
    }

    @PostMapping("/api/orders/{id}/approve")
    public ResponseEntity<Map<String, String>> approve(@PathVariable String id) {
        admin.approve(id);
        return ResponseEntity.ok(Map.of("orderId", id, "status", "APPROVED"));
    }

    @PostMapping("/api/orders/{id}/deny")
    public ResponseEntity<Map<String, String>> deny(@PathVariable String id) {
        admin.deny(id);
        return ResponseEntity.ok(Map.of("orderId", id, "status", "DENIED"));
    }

    @GetMapping("/api/orders/{id}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
        OrderStatus s = admin.statusOf(id);
        return s == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(Map.of("orderId", id, "status", s.toString()));
    }
}
