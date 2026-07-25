package com.petstore.opc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * order-processing-service — the modern Order Processing Center (legacy opc.ear).
 * It is the AUTHORITATIVE owner of orders and their workflow status:
 * <ul>
 *   <li>consumes PurchaseOrderQueue → persists the order (like legacy PurchaseOrderMDB);</li>
 *   <li>auto-approves under the locale threshold, else leaves PENDING for admin;</li>
 *   <li>on approval, dispatches to inventory over ApprovedOrderQueue;</li>
 *   <li>consumes InvoiceTopic → completes the order (like legacy InvoiceMDB);</li>
 *   <li>exposes an admin facade API (orders-by-status, approve, deny, status) —
 *       the legacy OPCAdminFacade — which admin-office-service (the admin.ear
 *       console) calls.</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} drives the {@link com.petstore.opc.service.OutboxRelay}
 * poller that publishes the transactional outbox — outbound events are enqueued in the
 * business transaction and drained to JMS just after commit.
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.petstore.opc", "com.petstore.messaging"})
public class OrderProcessingApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderProcessingApplication.class, args);
    }
}
