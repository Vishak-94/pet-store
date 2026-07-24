package com.petstore.opc.service;

import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Back-office admin operations (from admin.ear). Approve/deny pending orders and
 * list by status. Fulfilment now lives in inventory-service, so approve publishes
 * the order to ApprovedOrderQueue (via {@link ApprovalGateway}); the order moves
 * to COMPLETED only when the invoice comes back (see InvoiceListener). Mirrors
 * the legacy opc→supplier→opc round-trip.
 */
@Service
public class AdminService {

    private final OrderStore orders;
    private final ApprovalGateway approvalGateway;

    public AdminService(OrderStore orders, ApprovalGateway approvalGateway) {
        this.orders = orders;
        this.approvalGateway = approvalGateway;
    }

    @Transactional(readOnly = true)
    public List<String> ordersByStatus(OrderStatus status) {
        return orders.orderIdsByStatus(status);
    }

    /** Approve a pending order (PENDING → APPROVED), then dispatch for fulfilment. */
    @Transactional
    public void approve(String orderId) {
        OrderStatus current = orders.statusOf(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No such order: " + orderId));
        if (!current.canGoTo(OrderStatus.APPROVED)) {
            throw new IllegalStateException("Illegal transition " + current + " -> APPROVED for " + orderId);
        }
        orders.updateStatus(orderId, OrderStatus.APPROVED);
        WarehouseOrder order = orders.findById(orderId).orElseThrow();
        approvalGateway.dispatchForFulfilment(order);   // → ApprovedOrderQueue → inventory-service
    }

    /** Deny a pending order (PENDING → DENIED, terminal). */
    @Transactional
    public void deny(String orderId) {
        OrderStatus current = orders.statusOf(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No such order: " + orderId));
        if (!current.canGoTo(OrderStatus.DENIED)) {
            throw new IllegalStateException("Illegal transition " + current + " -> DENIED for " + orderId);
        }
        orders.updateStatus(orderId, OrderStatus.DENIED);
    }

    @Transactional(readOnly = true)
    public OrderStatus statusOf(String orderId) {
        return orders.statusOf(orderId).orElse(null);
    }
}
