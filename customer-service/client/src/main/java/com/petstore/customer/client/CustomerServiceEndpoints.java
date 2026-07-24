package com.petstore.customer.client;

/**
 * The customer-service API contract — endpoint paths as constants.
 *
 * <p>These paths ARE the published contract of the service, so they are hardcoded
 * here (they don't vary by environment). The base URL (host/port) is NOT hardcoded
 * — it's supplied to {@link CustomerServiceClient} because it changes per
 * environment. {@code {id}} is a path placeholder to be substituted.
 */
public final class CustomerServiceEndpoints {

    private CustomerServiceEndpoints() {
    }

    public static final String DEFAULT_BASE_URL = "http://localhost:8081";

    public static final String LOGIN    = "/auth/login";
    public static final String REGISTER = "/register";
    public static final String CUSTOMER = "/customer/{id}";
    public static final String ACCOUNT  = "/customer/{id}/account";
    public static final String PROFILE  = "/customer/{id}/profile";
    public static final String CARD     = "/customer/{id}/card";
}
