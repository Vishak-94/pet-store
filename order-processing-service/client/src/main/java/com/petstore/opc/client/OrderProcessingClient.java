package com.petstore.opc.client;

import com.petstore.opc.client.OrderDtos.CheckoutRequest;
import com.petstore.opc.client.OrderDtos.CheckoutResponse;
import com.petstore.opc.client.OrderDtos.OrderApprovalDto;
import com.petstore.opc.client.OrderDtos.OrderSummaryDto;
import com.petstore.opc.client.OrderDtos.OrderView;
import com.petstore.opc.client.OrderDtos.OrdersByStatus;
import com.petstore.opc.client.OrderDtos.SalesReportDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Importable client for the order-processing-service admin facade — the modern
 * OPCAdminFacade proxy. admin-office-service (the admin console) uses this to list
 * orders and submit approve/deny, exactly as legacy admin.ear called OPCAdminFacade.
 * The acting admin's Bearer token is forwarded so the OPC enforces ADMIN itself.
 */
public class OrderProcessingClient {

    /** Bounded timeouts so a hung/slow OPC can't block the admin console's threads indefinitely. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Bearer scheme prefix for the {@code Authorization} header (RFC 6750). */
    private static final String BEARER_PREFIX = "Bearer ";

    private final RestClient http;

    public OrderProcessingClient() {
        this(OrderProcessingEndpoints.DEFAULT_BASE_URL);
    }

    public OrderProcessingClient(String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build();
    }

    /**
     * Advanced: supply a preconfigured {@link RestClient} (e.g. with resilience
     * interceptors / TLS / custom timeouts). The caller owns the base URL + factory.
     */
    public OrderProcessingClient(RestClient restClient) {
        this.http = restClient;
    }

    /**
     * A request factory with bounded connect/read timeouts. Without these the default
     * factory waits forever, so one unresponsive OPC would tie up every admin-console
     * request thread and cascade into the console's own outage.
     */
    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        f.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return f;
    }

    /**
     * Place an order synchronously ({@code POST /api/orders/intake}) — the checkout write path
     * (the REST replacement for publishing a {@code PurchaseOrderEvent} to PurchaseOrderQueue).
     * OPC persists the order, runs the auto-approval policy, and dispatches the outbound event
     * via its outbox, then returns the persisted id + resolved status. Unlike the admin methods,
     * the {@code bearer} here is the acting <b>customer's</b> JWT (the storefront proxies it);
     * OPC authorizes this one endpoint for the customer role.
     *
     * <p>Idempotent by {@code request.orderId()}: a duplicate submit (same server-minted id)
     * returns the already-stored order's id + status rather than creating a second order.
     *
     * @param request the intake payload (order id + lines + contacts + currency); must be valid
     * @param bearer  the acting customer's JWT (with or without the {@code "Bearer "} prefix)
     * @return the persisted order id, resolved status (PENDING/APPROVED), and stored total
     * @throws org.springframework.web.client.HttpClientErrorException.BadRequest on an invalid
     *         payload (blank order id/user id, empty lines)
     * @throws org.springframework.web.client.RestClientException on transport failure / OPC down
     *         (the storefront maps this to a clean 503 for the shopper)
     */
    public CheckoutResponse checkout(CheckoutRequest request, String bearer) {
        return http.post().uri(OrderProcessingEndpoints.ORDER_INTAKE)
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .body(request)
                .retrieve().body(CheckoutResponse.class);
    }

    /**
     * List the order ids in a given workflow status ({@code GET /api/orders?status=}).
     * A {@code null} server body is normalized to an empty {@link OrdersByStatus}.
     *
     * <p>Example:
     * <pre>{@code
     * OrdersByStatus pending = client.ordersByStatus("PENDING", bearer);
     * // → OrdersByStatus[status=PENDING, orderIds=[1002, 1003], count=2]
     * }</pre>
     *
     * @param status the status to filter on (PENDING/APPROVED/DENIED/COMPLETED, upper-cased server-side)
     * @param bearer the acting admin's JWT (with or without the {@code "Bearer "} prefix)
     * @return the matching ids + count, never {@code null}
     * @throws org.springframework.web.client.HttpClientErrorException.BadRequest on an unknown status
     */
    public OrdersByStatus ordersByStatus(String status, String bearer) {
        OrdersByStatus r = http.get()
                .uri(uri -> uri.path(OrderProcessingEndpoints.ORDERS)
                        .queryParam(OrderProcessingEndpoints.PARAM_STATUS, status).build())
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .retrieve().body(OrdersByStatus.class);
        return r == null ? new OrdersByStatus(status, List.of(), 0) : r;
    }

    /**
     * All orders as summaries, newest-received first ({@code GET /api/orders/all}, admin
     * all-orders overview). A {@code null} server body is normalized to an empty list.
     *
     * @param bearer the acting admin's JWT (with or without the {@code "Bearer "} prefix)
     * @return every order summary, newest first; empty list if none, never {@code null}
     */
    public List<OrderSummaryDto> allOrders(String bearer) {
        List<OrderSummaryDto> r = http.get()
                .uri(OrderProcessingEndpoints.ORDERS_ALL)
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .retrieve().body(new ParameterizedTypeReference<List<OrderSummaryDto>>() {});
        return r == null ? List.of() : r;
    }

    /**
     * Full order detail by id ({@code GET /api/orders/{id}}). A 404 from the server is
     * mapped to {@link Optional#empty()} rather than thrown.
     *
     * @param orderId the order to fetch
     * @param bearer  the acting admin's JWT (with or without the {@code "Bearer "} prefix)
     * @return the order view, or empty if no such order exists
     */
    public Optional<OrderView> getOrder(String orderId, String bearer) {
        try {
            return Optional.ofNullable(http.get()
                    .uri(OrderProcessingEndpoints.ORDER_BY_ID, orderId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                    .retrieve().body(OrderView.class));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /**
     * Approve a single PENDING order ({@code POST /api/orders/{id}/approve}); the server
     * moves it PENDING → APPROVED and dispatches it for fulfilment. Response body is ignored.
     *
     * @param orderId the order to approve
     * @param bearer  the acting admin's JWT (with or without the {@code "Bearer "} prefix)
     * @throws org.springframework.web.client.HttpClientErrorException.Conflict if the order
     *         is not PENDING (illegal transition) or a concurrent write won the race
     */
    public void approve(String orderId, String bearer) {
        http.post().uri(OrderProcessingEndpoints.ORDER_APPROVE, orderId)
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .retrieve().toBodilessEntity();
    }

    /**
     * Deny a single PENDING order ({@code POST /api/orders/{id}/deny}); the server moves it
     * PENDING → DENIED (terminal, no fulfilment). Response body is ignored.
     *
     * @param orderId the order to deny
     * @param bearer  the acting admin's JWT (with or without the {@code "Bearer "} prefix)
     * @throws org.springframework.web.client.HttpClientErrorException.Conflict if the order
     *         is not PENDING (illegal transition) or a concurrent write won the race
     */
    public void deny(String orderId, String bearer) {
        http.post().uri(OrderProcessingEndpoints.ORDER_DENY, orderId)
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .retrieve().toBodilessEntity();
    }

    /**
     * Apply a batch of status changes atomically (legacy updateOrders/OrderApproval) via
     * {@code POST /api/orders/approvals}. All-or-nothing: the whole batch commits or none of
     * it does. Response body is ignored.
     *
     * <p>Example:
     * <pre>{@code
     * client.updateOrders(new OrderApprovalDto(List.of(
     *         new OrderStatusChangeDto("1002", "APPROVED"),
     *         new OrderStatusChangeDto("1003", "DENIED"))), bearer);
     * }</pre>
     *
     * @param approval the batch of {@code (orderId, newStatus)} changes (must be non-empty)
     * @param bearer   the acting admin's JWT (with or without the {@code "Bearer "} prefix)
     * @throws org.springframework.web.client.HttpClientErrorException.BadRequest on an empty
     *         batch, a blank field, an unknown status, or a missing order
     * @throws org.springframework.web.client.HttpClientErrorException.Conflict on any illegal
     *         transition in the batch (whole batch rolls back)
     */
    public void updateOrders(OrderApprovalDto approval, String bearer) {
        http.post().uri(OrderProcessingEndpoints.ORDER_APPROVALS)
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .body(approval)
                .retrieve().toBodilessEntity();
    }

    /**
     * Sales aggregation over a date range (legacy getChartInfo) via {@code GET /api/sales}.
     * Grouped by category, or by item within {@code category} when one is supplied.
     *
     * <p>Example:
     * <pre>{@code
     * SalesReportDto byCategory = client.sales("2026-07-01", "2026-07-31", null, bearer);
     * // → SalesReportDto[groupBy=category, buckets=[SalesBucketDto[key=FISH, revenue=1240.5, quantity=14], ...]]
     * }</pre>
     *
     * @param start    inclusive ISO start date (yyyy-MM-dd, UTC)
     * @param end      inclusive ISO end date (yyyy-MM-dd, UTC)
     * @param category optional category id to group by item within it; {@code null} groups by category
     * @param bearer   the acting admin's JWT (with or without the {@code "Bearer "} prefix)
     * @return the aggregated report
     * @throws org.springframework.web.client.HttpClientErrorException.BadRequest on a malformed date
     */
    public SalesReportDto sales(String start, String end, String category, String bearer) {
        return http.get()
                .uri(uri -> {
                    uri.path(OrderProcessingEndpoints.SALES)
                            .queryParam(OrderProcessingEndpoints.PARAM_START, start)
                            .queryParam(OrderProcessingEndpoints.PARAM_END, end);
                    if (category != null) {
                        uri.queryParam(OrderProcessingEndpoints.PARAM_CATEGORY, category);
                    }
                    return uri.build();
                })
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .retrieve().body(SalesReportDto.class);
    }

    private static String bearer(String token) {
        return token != null && token.startsWith(BEARER_PREFIX) ? token : BEARER_PREFIX + token;
    }
}
