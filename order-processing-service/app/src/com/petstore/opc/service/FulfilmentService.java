package com.petstore.opc.service;

import com.petstore.opc.domain.ApprovalPolicy;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Order intake + approval decision (the legacy OPC role). On a new order from JMS:
 * store it, and auto-approve if under the currency threshold — else leave PENDING
 * for manual admin approval. Actual stock reservation + shipping is now owned by
 * inventory-service; on approval this publishes the order to ApprovedOrderQueue
 * (see {@link ApprovalGateway}) and the order moves to COMPLETED only when the
 * invoice comes back. Idempotent.
 */
@Service
public class FulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentService.class);

    private final OrderStore orders;
    private final ApprovalPolicy approvalPolicy;
    private final ApprovalGateway approvalGateway;

    public FulfilmentService(OrderStore orders, ApprovalPolicy approvalPolicy,
                             ApprovalGateway approvalGateway) {
        this.orders = orders;
        this.approvalPolicy = approvalPolicy;
        this.approvalGateway = approvalGateway;
    }

    /**
     * Handle a newly received order (from checkout via JMS). Persists the order and, if it
     * clears the currency auto-approval threshold ({@link ApprovalPolicy}), saves it APPROVED
     * and dispatches it for fulfilment (via {@link ApprovalGateway} → ApprovedOrderQueue);
     * otherwise it is saved PENDING for a human admin. Idempotent — an order id already
     * stored is ignored (JMS is at-least-once), so redeliveries are safe no-ops.
     *
     * @param incoming the order mapped from the inbound {@code PurchaseOrderEvent}; its
     *                 status is ignored (recomputed here as APPROVED or PENDING) and its
     *                 {@code created} timestamp falls back to now when absent
     */
    @Transactional
    public void receiveOrder(WarehouseOrder incoming) {
        if (orders.findById(incoming.orderId()).isPresent()) {
            log.info("Order {} already received, ignoring duplicate", incoming.orderId());
            return;   // idempotent — JMS at-least-once
        }
        boolean auto = approvalPolicy.canAutoApprove(incoming.totalPrice(), incoming.currency());
        OrderStatus initial = auto ? OrderStatus.APPROVED : OrderStatus.PENDING;
        Instant created = incoming.created() != null ? incoming.created() : Instant.now();
        WarehouseOrder saved = orders.save(new WarehouseOrder(
                incoming.orderId(), incoming.userId(), incoming.emailId(),
                incoming.locale(), incoming.currency(), incoming.totalPrice(), initial,
                incoming.lines(), incoming.shipTo(), incoming.billTo(), created));
        log.info("Order {} received → {} (total {} {})", incoming.orderId(), initial,
                incoming.totalPrice(), incoming.currency());
        if (auto) {
            approvalGateway.dispatchForFulfilment(saved);   // → ApprovedOrderQueue → inventory-service
        }
    }
}
