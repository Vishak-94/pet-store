package com.petstore.order.web;

import com.petstore.order.service.EmptyCartException;
import com.petstore.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * JSON checkout endpoint (API alternative to the HTML storefront checkout).
 * Order STATUS is no longer served here — it's owned by warehouse-service
 * ({@code GET /api/orders/{id}/status} on :8082).
 */
@RestController
public class CheckoutController {

    private final OrderService orderService;

    public CheckoutController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/checkout")
    public ResponseEntity<Map<String, Object>> checkout(
            @RequestParam(defaultValue = "guest") String userId,
            @RequestParam(defaultValue = "guest@petstore.com") String email,
            @ModelAttribute CheckoutForm form) {
        try {
            // Legacy OrderHTMLAction validated both ship-to and bill-to before ordering.
            ContactInfoForm.requireValid(form.getShipTo(), form.getBillTo());
            OrderService.OrderPlaced placed = orderService.checkout(userId, email,
                    form.getShipTo().toContactInfo(), form.getBillTo().toContactInfo());
            return ResponseEntity.ok(Map.of(
                    "orderId", placed.orderId(),
                    "total", placed.total(),
                    "note", "submitted to order-processing-service for fulfilment"));
        } catch (EmptyCartException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "cart_empty"));
        }
    }
}
