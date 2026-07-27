package com.petstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised service endpoint configuration — no hardcoded URLs in code.
 *
 * <p>Bound from the {@code services.*} block in application.yml. Each downstream
 * service has a base URL plus named endpoint paths, so switching a host/port or
 * path is a config change (and profile/env-overridable) rather than a code edit.
 *
 * <p>Example:
 * <pre>
 * services:
 *   customer:
 *     base-url: http://localhost:8081
 *     endpoints:
 *       register: /register
 *       customer: /customer/{id}
 * </pre>
 */
@ConfigurationProperties(prefix = "services")
public class ServiceEndpoints {

    private Service customer = new Service();
    private Service catalog = new Service();
    private Service orderProcessing = new Service();
    private Service inventory = new Service();

    public Service getCustomer() { return customer; }
    public void setCustomer(Service customer) { this.customer = customer; }

    public Service getCatalog() { return catalog; }
    public void setCatalog(Service catalog) { this.catalog = catalog; }

    public Service getOrderProcessing() { return orderProcessing; }
    public void setOrderProcessing(Service orderProcessing) { this.orderProcessing = orderProcessing; }

    public Service getInventory() { return inventory; }
    public void setInventory(Service inventory) { this.inventory = inventory; }

    public static class Service {
        private String baseUrl;
        private java.util.Map<String, String> endpoints = new java.util.HashMap<>();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public java.util.Map<String, String> getEndpoints() { return endpoints; }
        public void setEndpoints(java.util.Map<String, String> endpoints) { this.endpoints = endpoints; }

        /** Full URL for a named endpoint (baseUrl + path). */
        public String url(String name) {
            String path = endpoints.get(name);
            if (path == null) {
                throw new IllegalArgumentException("No endpoint configured: services.customer.endpoints." + name);
            }
            return baseUrl + path;
        }
    }
}
