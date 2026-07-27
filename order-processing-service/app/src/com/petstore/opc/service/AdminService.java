package com.petstore.opc.service;

import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.OrderStatusChange;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
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

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final OrderStore orders;
    private final ApprovalGateway approvalGateway;
    private final OrderStatusGateway statusGateway;

    public AdminService(OrderStore orders, ApprovalGateway approvalGateway,
                        OrderStatusGateway statusGateway) {
        this.orders = orders;
        this.approvalGateway = approvalGateway;
        this.statusGateway = statusGateway;
    }

    /**
     * The ids of every order currently in {@code status} (e.g. the PENDING human-review
     * queue). Read-only; no side effects.
     *
     * @param status the workflow status to filter on
     * @return matching order ids (empty if none), never {@code null}
     */
    @Transactional(readOnly = true)
    public List<String> ordersByStatus(OrderStatus status) {
        return orders.orderIdsByStatus(status);
    }

    /** Every order, most-recently-received first, for the admin all-orders overview. */
    @Transactional(readOnly = true)
    public List<WarehouseOrder> allOrders() {
        return orders.findAllByCreatedDesc();
    }

    /**
     * Approve a pending order (PENDING → APPROVED), then dispatch it for fulfilment.
     * Side effects (after commit, via the outbox): an {@code OrderApprovedEvent} to
     * ApprovedOrderQueue (inventory-service fulfils) and an {@code OrderStatusEvent} to
     * OrderStatusTopic (customer email).
     *
     * @param orderId the order to approve
     * @throws IllegalArgumentException if no such order exists
     * @throws IllegalStateException    if the order is not PENDING (illegal transition)
     */
    @Transactional
    public void approve(String orderId) {
        applyStatusChange(orderId, OrderStatus.APPROVED);
    }

    /**
     * Deny a pending order (PENDING → DENIED, terminal). Side effect (after commit, via the
     * outbox): an {@code OrderStatusEvent} to OrderStatusTopic (customer email). No
     * fulfilment dispatch — denied orders are never sent to inventory-service.
     *
     * @param orderId the order to deny
     * @throws IllegalArgumentException if no such order exists
     * @throws IllegalStateException    if the order is not PENDING (illegal transition)
     */
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
     * Re-drive every APPROVED (backordered) order back through fulfilment, oldest-first —
     * the migrated form of the legacy supplier {@code processPendingPO()} that ran when fresh
     * stock arrived (PARITY_AUDIT H2/M8). Triggered by a {@code RestockEvent} once inventory-service
     * has added stock: an order that short-shipped stays APPROVED (never SHIPPED_PART — invariant #2),
     * so on restock we simply re-dispatch each APPROVED order via the SAME {@link ApprovalGateway}
     * outbox → ApprovedOrderQueue path the original approval used. No status change, no customer email —
     * this is a fulfilment retry, not a workflow transition.
     *
     * <p>Ordering: oldest {@code created} first, so the longest-waiting backorders get first claim on
     * the replenished stock (legacy processed the pending PO queue in arrival order). Safe to re-run:
     * inventory-service dedups by orderId (an order ships at most once), so re-dispatching an order that
     * already shipped but is briefly still APPROVED — or racing two restocks — never double-decrements.
     * Runs in one transaction so all outbox rows commit together (invariant #3).
     */
    @Transactional
    public void redriveApprovedForFulfilment() {
        List<WarehouseOrder> approved = orders.orderIdsByStatus(OrderStatus.APPROVED).stream()
                .map(orders::findById)
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparing(WarehouseOrder::created))   // oldest-first
                .toList();
        if (approved.isEmpty()) {
            log.info("Restock re-drive: no APPROVED (backordered) orders to re-attempt");
            return;
        }
        log.info("Restock re-drive: re-dispatching {} APPROVED order(s) for fulfilment (oldest-first)",
                approved.size());
        for (WarehouseOrder order : approved) {
            approvalGateway.dispatchForFulfilment(order);   // → ApprovedOrderQueue → inventory-service
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

    /**
     * The current workflow status of an order, or {@code null} if no such order exists
     * (lets the controller map absence to a 404). Read-only; no side effects.
     *
     * @param orderId the order to look up
     * @return the order's {@link OrderStatus}, or {@code null} if unknown
     */
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
