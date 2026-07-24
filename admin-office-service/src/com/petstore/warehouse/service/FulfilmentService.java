package com.petstore.warehouse.service;

import com.petstore.warehouse.domain.ApprovalPolicy;
import com.petstore.warehouse.domain.OrderStatus;
import com.petstore.warehouse.domain.WarehouseOrder;
import com.petstore.warehouse.repository.OrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Order intake + approval decision (the legacy OPC role). On a new order from JMS:
 * store it, and auto-approve if under the locale threshold — else leave PENDING
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

    /** Handle a newly received order (from checkout via JMS). Idempotent. */
    @Transactional
    public void receiveOrder(WarehouseOrder incoming) {
        if (orders.findById(incoming.orderId()).isPresent()) {
            log.info("Order {} already received, ignoring duplicate", incoming.orderId());
            return;   // idempotent — JMS at-least-once
        }
        boolean auto = approvalPolicy.canAutoApprove(incoming.totalPrice(), localeOf(incoming.locale()));
        OrderStatus initial = auto ? OrderStatus.APPROVED : OrderStatus.PENDING;
        WarehouseOrder saved = orders.save(new WarehouseOrder(
                incoming.orderId(), incoming.userId(), incoming.emailId(),
                incoming.locale(), incoming.totalPrice(), initial, incoming.lines()));
        log.info("Order {} received → {} (total {})", incoming.orderId(), initial, incoming.totalPrice());
        if (auto) {
            approvalGateway.dispatchForFulfilment(saved);   // → ApprovedOrderQueue → inventory-service
        }
    }

    private static Locale localeOf(String s) {
        if (s == null || s.isBlank()) return Locale.US;
        String[] p = s.split("_");
        return p.length == 2 ? new Locale(p[0], p[1]) : new Locale(s);
    }
}
