package com.petstore.opc.client;

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

    public OrdersByStatus ordersByStatus(String status, String bearer) {
        OrdersByStatus r = http.get()
                .uri(uri -> uri.path(OrderProcessingEndpoints.ORDERS)
                        .queryParam(OrderProcessingEndpoints.PARAM_STATUS, status).build())
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .retrieve().body(OrdersByStatus.class);
        return r == null ? new OrdersByStatus(status, List.of(), 0) : r;
    }

    /** All orders as summaries, newest-received first (admin all-orders overview). */
    public List<OrderSummaryDto> allOrders(String bearer) {
        List<OrderSummaryDto> r = http.get()
                .uri(OrderProcessingEndpoints.ORDERS_ALL)
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .retrieve().body(new ParameterizedTypeReference<List<OrderSummaryDto>>() {});
        return r == null ? List.of() : r;
    }

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

    public void approve(String orderId, String bearer) {
        http.post().uri(OrderProcessingEndpoints.ORDER_APPROVE, orderId)
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .retrieve().toBodilessEntity();
    }

    public void deny(String orderId, String bearer) {
        http.post().uri(OrderProcessingEndpoints.ORDER_DENY, orderId)
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .retrieve().toBodilessEntity();
    }

    /** Apply a batch of status changes atomically (legacy updateOrders/OrderApproval). */
    public void updateOrders(OrderApprovalDto approval, String bearer) {
        http.post().uri(OrderProcessingEndpoints.ORDER_APPROVALS)
                .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                .body(approval)
                .retrieve().toBodilessEntity();
    }

    /** Sales aggregation over a date range (legacy getChartInfo); category optional. */
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
