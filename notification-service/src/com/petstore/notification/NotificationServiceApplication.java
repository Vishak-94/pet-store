package com.petstore.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * notification-service — subscribes to the InvoiceTopic and "emails" the customer
 * when their order completes. It's a SECOND, independent subscriber to the same
 * topic warehouse listens on, demonstrating pub/sub fan-out: inventory publishes
 * one InvoiceEvent, and both warehouse (completes the order) and this service
 * (notifies the customer) each receive their own copy.
 */
@SpringBootApplication(scanBasePackages = {"com.petstore.notification", "com.petstore.messaging"})
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
