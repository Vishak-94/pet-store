package com.petstore.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * inventory-service — the fulfilment + inventory microservice, extracted from
 * warehouse-service to match the legacy supplier.ear (which was a separate app
 * from the opc/admin order-approval side).
 *
 * <p>Event-driven, mirroring legacy opc↔supplier: consumes ApprovedOrderQueue,
 * reserves stock (pessimistic lock) and ships, then publishes an invoice back on
 * InvoiceQueue. Also hosts the "receiver" UI for restocking inventory. SUPPLIER role.
 */
@SpringBootApplication(scanBasePackages = {"com.petstore.inventory", "com.petstore.messaging"})
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
