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

    /**
     * JSON field names on the login/registration wire contract. Shared between the client
     * (which writes/reads them) and the server controller — kept as constants (contract
     * literals) so the two ends can't disagree on a key name.
     */
    public static final String FIELD_USER_NAME = "userName";
    public static final String FIELD_PASSWORD = "password";
    public static final String FIELD_TOKEN = "token";
    public static final String FIELD_CUSTOMER_ID = "customerId";
    public static final String FIELD_USER_ID = "userId";
    public static final String FIELD_STATUS = "status";
}
