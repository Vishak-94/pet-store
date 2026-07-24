package com.petstore.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;

/**
 * warehouse-service — merges the legacy admin.ear (back-office order approval)
 * and supplier.ear (fulfilment + inventory). Owns order_status + inventory,
 * consumes PurchaseOrderQueue, and serves a staff UI (JWT/ADMIN).
 */
@SpringBootApplication(scanBasePackages = {"com.petstore.warehouse", "com.petstore.messaging"})
@EnableJms
public class WarehouseServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WarehouseServiceApplication.class, args);
    }
}
