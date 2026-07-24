package com.petstore.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * admin-office-service — the back-office ADMIN console (legacy admin.ear). It owns
 * NO order data: it lists orders and submits approve/deny by calling
 * order-processing-service (the OPC / legacy OPCAdminFacade) via its client SDK.
 * Verify-only auth (auth-client); ADMIN role.
 */
@SpringBootApplication
public class WarehouseServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WarehouseServiceApplication.class, args);
    }
}
