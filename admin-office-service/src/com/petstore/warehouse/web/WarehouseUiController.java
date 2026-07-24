package com.petstore.warehouse.web;

import com.petstore.opc.client.OrderDtos.OrderView;
import com.petstore.opc.client.OrderProcessingClient;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin order-approval console (legacy admin.ear). Owns NO order data — it calls
 * order-processing-service (the OPC / legacy OPCAdminFacade) via
 * {@link OrderProcessingClient}, forwarding the acting admin's JWT. Secured to
 * ROLE_ADMIN.
 */
@Controller
public class WarehouseUiController {

    private final OrderProcessingClient opc;

    public WarehouseUiController(OrderProcessingClient opc) {
        this.opc = opc;
    }

    /** Pending-orders approval console — lists via the OPC facade. */
    @GetMapping("/warehouse/orders")
    public String orders(HttpServletRequest request, Model model) {
        String bearer = jwt(request);
        List<Map<String, Object>> pending = new ArrayList<>();
        for (String id : opc.ordersByStatus("PENDING", bearer).orderIds()) {
            opc.getOrder(id, bearer).ifPresent(o -> pending.add(Map.of(
                    "orderId", o.orderId(), "user", o.userId(),
                    "total", o.totalPrice(), "lines", o.lines().size())));
        }
        model.addAttribute("pending", pending);
        return "orders";
    }

    @PostMapping("/warehouse/orders/{id}/approve")
    public String approve(@PathVariable String id, HttpServletRequest request) {
        opc.approve(id, jwt(request));
        return "redirect:/warehouse/orders";
    }

    @PostMapping("/warehouse/orders/{id}/deny")
    public String deny(@PathVariable String id, HttpServletRequest request) {
        opc.deny(id, jwt(request));
        return "redirect:/warehouse/orders";
    }

    private static String jwt(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("jwt".equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return "";
    }
}
