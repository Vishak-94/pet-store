package com.petstore.warehouse.web;

import com.petstore.auth.client.AuthJwtFilter;
import com.petstore.opc.client.OrderProcessingClient;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    /** Renders the order-received Instant in the server's local zone for the overview table. */
    private static final DateTimeFormatter RECEIVED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Default status listed on the approval console (the human-review queue). */
    private static final String STATUS_PENDING = "PENDING";
    /** Placeholder shown when an order has no received timestamp. */
    private static final String NO_TIMESTAMP = "—";

    /** Thymeleaf view names + redirect target. */
    private static final String VIEW_ORDERS = "orders";
    private static final String VIEW_ALL_ORDERS = "all_orders";
    private static final String REDIRECT_ORDERS = "redirect:/warehouse/orders";

    /** Model attribute + row-map keys consumed by the templates. */
    private static final String ATTR_PENDING = "pending";
    private static final String ATTR_ORDERS = "orders";
    private static final String KEY_ORDER_ID = "orderId";
    private static final String KEY_USER = "user";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_LINES = "lines";
    private static final String KEY_RECEIVED = "received";
    private static final String KEY_STATUS = "status";

    private final OrderProcessingClient opc;

    public WarehouseUiController(OrderProcessingClient opc) {
        this.opc = opc;
    }

    /** Pending-orders approval console — lists via the OPC facade. */
    @GetMapping("/warehouse/orders")
    public String orders(HttpServletRequest request, Model model) {
        String bearer = jwt(request);
        List<Map<String, Object>> pending = new ArrayList<>();
        for (String id : opc.ordersByStatus(STATUS_PENDING, bearer).orderIds()) {
            opc.getOrder(id, bearer).ifPresent(o -> pending.add(Map.of(
                    KEY_ORDER_ID, o.orderId(), KEY_USER, o.userId(),
                    KEY_TOTAL, o.totalPrice(), KEY_LINES, o.lines().size())));
        }
        model.addAttribute(ATTR_PENDING, pending);
        return VIEW_ORDERS;
    }

    /**
     * Read-only "All Orders" overview — every order (any status) sorted newest-first.
     * Delegates to the OPC's {@code /api/orders/all} summary endpoint in a single call
     * (no per-order fetch), forwarding the acting admin's JWT. Approve/deny stays on
     * the focused pending console; this page never mutates.
     */
    @GetMapping("/warehouse/orders/all")
    public String allOrders(HttpServletRequest request, Model model) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var o : opc.allOrders(jwt(request))) {
            rows.add(Map.of(
                    KEY_ORDER_ID, o.orderId(),
                    KEY_USER, o.userId(),
                    KEY_RECEIVED, o.created() == null ? NO_TIMESTAMP : RECEIVED_FMT.format(o.created()),
                    KEY_STATUS, o.status(),
                    KEY_LINES, o.lineCount(),
                    KEY_TOTAL, o.totalPrice()));
        }
        model.addAttribute(ATTR_ORDERS, rows);
        return VIEW_ALL_ORDERS;
    }

    @PostMapping("/warehouse/orders/{id}/approve")
    public String approve(@PathVariable String id, HttpServletRequest request) {
        opc.approve(id, jwt(request));
        return REDIRECT_ORDERS;
    }

    @PostMapping("/warehouse/orders/{id}/deny")
    public String deny(@PathVariable String id, HttpServletRequest request) {
        opc.deny(id, jwt(request));
        return REDIRECT_ORDERS;
    }

    /** Extract the admin's JWT from the {@code jwt} cookie (empty string if absent). */
    private static String jwt(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (AuthJwtFilter.JWT_COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return "";
    }
}
