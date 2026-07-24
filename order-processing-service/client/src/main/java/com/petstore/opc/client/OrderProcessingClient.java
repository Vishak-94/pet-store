package com.petstore.opc.client;

import com.petstore.opc.client.OrderDtos.OrderApprovalDto;
import com.petstore.opc.client.OrderDtos.OrderView;
import com.petstore.opc.client.OrderDtos.OrdersByStatus;
import com.petstore.opc.client.OrderDtos.SalesReportDto;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * Importable client for the order-processing-service admin facade — the modern
 * OPCAdminFacade proxy. admin-office-service (the admin console) uses this to list
 * orders and submit approve/deny, exactly as legacy admin.ear called OPCAdminFacade.
 * The acting admin's Bearer token is forwarded so the OPC enforces ADMIN itself.
 */
public class OrderProcessingClient {

    private final RestClient http;

    public OrderProcessingClient() {
        this(OrderProcessingEndpoints.DEFAULT_BASE_URL);
    }

    public OrderProcessingClient(String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    public OrdersByStatus ordersByStatus(String status, String bearer) {
        OrdersByStatus r = http.get()
                .uri(uri -> uri.path(OrderProcessingEndpoints.ORDERS).queryParam("status", status).build())
                .header("Authorization", bearer(bearer))
                .retrieve().body(OrdersByStatus.class);
        return r == null ? new OrdersByStatus(status, List.of(), 0) : r;
    }

    public Optional<OrderView> getOrder(String orderId, String bearer) {
        try {
            return Optional.ofNullable(http.get()
                    .uri(OrderProcessingEndpoints.ORDER_BY_ID, orderId)
                    .header("Authorization", bearer(bearer))
                    .retrieve().body(OrderView.class));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public void approve(String orderId, String bearer) {
        http.post().uri(OrderProcessingEndpoints.ORDER_APPROVE, orderId)
                .header("Authorization", bearer(bearer))
                .retrieve().toBodilessEntity();
    }

    public void deny(String orderId, String bearer) {
        http.post().uri(OrderProcessingEndpoints.ORDER_DENY, orderId)
                .header("Authorization", bearer(bearer))
                .retrieve().toBodilessEntity();
    }

    /** Apply a batch of status changes atomically (legacy updateOrders/OrderApproval). */
    public void updateOrders(OrderApprovalDto approval, String bearer) {
        http.post().uri(OrderProcessingEndpoints.ORDER_APPROVALS)
                .header("Authorization", bearer(bearer))
                .body(approval)
                .retrieve().toBodilessEntity();
    }

    /** Sales aggregation over a date range (legacy getChartInfo); category optional. */
    public SalesReportDto sales(String start, String end, String category, String bearer) {
        return http.get()
                .uri(uri -> {
                    uri.path(OrderProcessingEndpoints.SALES)
                            .queryParam("start", start).queryParam("end", end);
                    if (category != null) {
                        uri.queryParam("category", category);
                    }
                    return uri.build();
                })
                .header("Authorization", bearer(bearer))
                .retrieve().body(SalesReportDto.class);
    }

    private static String bearer(String token) {
        return token != null && token.startsWith("Bearer ") ? token : "Bearer " + token;
    }
}
