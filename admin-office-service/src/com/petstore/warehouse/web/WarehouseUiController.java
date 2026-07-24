package com.petstore.warehouse.web;

import com.petstore.warehouse.domain.OrderStatus;
import com.petstore.warehouse.repository.OrderStore;
import com.petstore.warehouse.service.AdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Warehouse staff UI (Thymeleaf) — the admin.ear order-approval console. Secured
 * to ROLE_ADMIN. Inventory/restock now lives in inventory-service (SUPPLIER).
 */
@Controller
public class WarehouseUiController {

    private final AdminService admin;
    private final OrderStore orders;

    public WarehouseUiController(AdminService admin, OrderStore orders) {
        this.admin = admin;
        this.orders = orders;
    }

    /** Pending-orders approval console. */
    @GetMapping("/warehouse/orders")
    public String orders(Model model) {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (String id : admin.ordersByStatus(OrderStatus.PENDING)) {
            orders.findById(id).ifPresent(o -> pending.add(Map.of(
                    "orderId", o.orderId(), "user", o.userId(),
                    "total", o.totalPrice(), "lines", o.lines().size())));
        }
        model.addAttribute("pending", pending);
        return "orders";
    }

    @PostMapping("/warehouse/orders/{id}/approve")
    public String approve(@PathVariable String id) {
        admin.approve(id);
        return "redirect:/warehouse/orders";
    }

    @PostMapping("/warehouse/orders/{id}/deny")
    public String deny(@PathVariable String id) {
        admin.deny(id);
        return "redirect:/warehouse/orders";
    }
}
