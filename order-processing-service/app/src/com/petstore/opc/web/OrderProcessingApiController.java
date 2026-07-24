package com.petstore.opc.web;

import com.petstore.opc.client.OrderDtos.LineDto;
import com.petstore.opc.client.OrderDtos.OrderApprovalDto;
import com.petstore.opc.client.OrderDtos.OrderView;
import com.petstore.opc.client.OrderDtos.OrdersByStatus;
import com.petstore.opc.client.OrderDtos.SalesBucketDto;
import com.petstore.opc.client.OrderDtos.SalesReportDto;
import com.petstore.opc.client.OrderDtos.StatusView;
import com.petstore.opc.client.OrderProcessingEndpoints;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.OrderStatusChange;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import com.petstore.opc.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The order-processing admin facade API — the modern OPCAdminFacade. Reuses the
 * SDK DTOs so the contract is single-sourced. ADMIN-only (enforced in SecurityConfig).
 */
@RestController
public class OrderProcessingApiController {

    private final AdminService admin;
    private final OrderStore orders;

    public OrderProcessingApiController(AdminService admin, OrderStore orders) {
        this.admin = admin;
        this.orders = orders;
    }

    @GetMapping(OrderProcessingEndpoints.ORDERS)
    public OrdersByStatus byStatus(@RequestParam(defaultValue = "PENDING") String status) {
        OrderStatus s = OrderStatus.valueOf(status.toUpperCase());
        List<String> ids = admin.ordersByStatus(s);
        return new OrdersByStatus(s.name(), ids, ids.size());
    }

    @GetMapping(OrderProcessingEndpoints.ORDER_BY_ID)
    public ResponseEntity<OrderView> getOrder(@PathVariable String id) {
        return orders.findById(id).map(o -> ResponseEntity.ok(toView(o)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(OrderProcessingEndpoints.ORDER_STATUS)
    public ResponseEntity<StatusView> status(@PathVariable String id) {
        OrderStatus s = admin.statusOf(id);
        return s == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(new StatusView(id, s.name()));
    }

    @PostMapping(OrderProcessingEndpoints.ORDER_APPROVE)
    public ResponseEntity<StatusView> approve(@PathVariable String id) {
        admin.approve(id);
        return ResponseEntity.ok(new StatusView(id, "APPROVED"));
    }

    @PostMapping(OrderProcessingEndpoints.ORDER_DENY)
    public ResponseEntity<StatusView> deny(@PathVariable String id) {
        admin.deny(id);
        return ResponseEntity.ok(new StatusView(id, "DENIED"));
    }

    /** Atomic batch status update (legacy updateOrders/OrderApproval). All-or-nothing. */
    @PostMapping(OrderProcessingEndpoints.ORDER_APPROVALS)
    public ResponseEntity<OrdersByStatus> updateOrders(@RequestBody OrderApprovalDto approval) {
        List<OrderStatusChange> changes = approval.orders().stream()
                .map(c -> new OrderStatusChange(c.orderId(), OrderStatus.valueOf(c.newStatus().toUpperCase())))
                .toList();
        admin.updateOrders(changes);
        List<String> ids = changes.stream().map(OrderStatusChange::orderId).toList();
        return ResponseEntity.ok(new OrdersByStatus("UPDATED", ids, ids.size()));
    }

    /**
     * Sales aggregation over a date range (legacy getChartInfo). {@code start}/{@code end}
     * are ISO dates (yyyy-MM-dd, inclusive, UTC); {@code category} optional (group by item
     * within it, else by category).
     */
    @GetMapping(OrderProcessingEndpoints.SALES)
    public SalesReportDto sales(@RequestParam String start, @RequestParam String end,
                                @RequestParam(required = false) String category) {
        Instant from = LocalDate.parse(start).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = LocalDate.parse(end).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);
        SalesReport report = admin.salesReport(from, to, category);
        List<SalesBucketDto> buckets = report.buckets().stream()
                .map(b -> new SalesBucketDto(b.key(), b.revenue(), b.quantity()))
                .toList();
        return new SalesReportDto(report.groupBy(), buckets);
    }

    private static OrderView toView(WarehouseOrder o) {
        List<LineDto> lines = o.lines().stream()
                .map(l -> new LineDto(l.itemId(), l.productId(), l.categoryId(), l.quantity(), l.unitPrice()))
                .toList();
        return new OrderView(o.orderId(), o.userId(), o.emailId(), o.locale(),
                o.totalPrice(), o.status().name(), lines);
    }
}
