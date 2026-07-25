package com.petstore.opc.service;

import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.OrderStatusChange;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final OrderStatusGateway statusGateway;

    public AdminService(OrderStore orders, ApprovalGateway approvalGateway,
                        OrderStatusGateway statusGateway) {
        this.orders = orders;
        this.approvalGateway = approvalGateway;
        this.statusGateway = statusGateway;
    }

    @Transactional(readOnly = true)
    public List<String> ordersByStatus(OrderStatus status) {
        return orders.orderIdsByStatus(status);
    }

    /** Every order, most-recently-received first, for the admin all-orders overview. */
    @Transactional(readOnly = true)
    public List<WarehouseOrder> allOrders() {
        return orders.findAllByCreatedDesc();
    }

    /** Approve a pending order (PENDING → APPROVED), then dispatch for fulfilment. */
    @Transactional
    public void approve(String orderId) {
        applyStatusChange(orderId, OrderStatus.APPROVED);
    }

    /** Deny a pending order (PENDING → DENIED, terminal). */
    @Transactional
    public void deny(String orderId) {
        applyStatusChange(orderId, OrderStatus.DENIED);
    }

    /**
     * Apply a BATCH of status changes atomically in a single transaction — the
     * migrated form of the legacy {@code updateOrders(OrderApproval)}, which
     * committed a batch of {@code ChangedOrder}s in one JMS message. Either every
     * change is validated + applied, or (on any illegal transition / missing order)
     * the whole batch rolls back and nothing is published. Per-change fulfilment
     * dispatch + customer status announcements fire after commit, exactly as the
     * per-order path.
     */
    @Transactional
    public void updateOrders(List<OrderStatusChange> changes) {
        for (OrderStatusChange change : changes) {
            applyStatusChange(change.orderId(), change.newStatus());
        }
    }

    /**
     * Validate + apply one status transition, dispatching for fulfilment on APPROVED
     * and announcing the change to the customer. Shared by the per-order and batch
     * paths so the transaction + after-commit gateway semantics stay identical.
     */
    private void applyStatusChange(String orderId, OrderStatus target) {
        OrderStatus current = orders.statusOf(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No such order: " + orderId));
        if (!current.canGoTo(target)) {
            throw new IllegalStateException("Illegal transition " + current + " -> " + target + " for " + orderId);
        }
        orders.updateStatus(orderId, target);
        WarehouseOrder order = orders.findById(orderId).orElseThrow();
        if (target == OrderStatus.APPROVED) {
            approvalGateway.dispatchForFulfilment(order);   // → ApprovedOrderQueue → inventory-service
        }
        statusGateway.announce(order, target);   // → customer "Order Status" email (legacy MailOrderApprovalMDB)
    }

    @Transactional(readOnly = true)
    public OrderStatus statusOf(String orderId) {
        return orders.statusOf(orderId).orElse(null);
    }

    /**
     * Aggregate sales (revenue Σ qty·unitPrice + order quantities) over orders
     * received in [start, end], grouped by category (or by item when a category is
     * given). Migrated {@code OPCAdminFacadeEJB.getChartInfo} aggregation logic.
     */
    @Transactional(readOnly = true)
    public SalesReport salesReport(Instant start, Instant end, String categoryId) {
        return orders.aggregateSales(start, end, categoryId);
    }
}
