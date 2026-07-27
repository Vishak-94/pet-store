package com.petstore.opc.web;

import com.petstore.opc.client.OrderDtos.CheckoutRequest;
import com.petstore.opc.client.OrderDtos.CheckoutResponse;
import com.petstore.opc.client.OrderDtos.ContactInfoDto;
import com.petstore.opc.client.OrderDtos.LineDto;
import com.petstore.opc.client.OrderDtos.OrderApprovalDto;
import com.petstore.opc.client.OrderDtos.OrderView;
import com.petstore.opc.client.OrderDtos.OrderSummaryDto;
import com.petstore.opc.client.OrderDtos.OrdersByStatus;
import com.petstore.opc.client.OrderDtos.SalesBucketDto;
import com.petstore.opc.client.OrderDtos.SalesReportDto;
import com.petstore.opc.client.OrderDtos.StatusView;
import com.petstore.opc.client.OrderProcessingEndpoints;
import com.petstore.opc.domain.ContactInfo;
import com.petstore.opc.domain.OrderLine;
import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.OrderStatusChange;
import com.petstore.opc.domain.SalesReport;
import com.petstore.opc.domain.WarehouseOrder;
import com.petstore.opc.repository.OrderStore;
import com.petstore.opc.service.AdminService;
import com.petstore.opc.service.FulfilmentService;
import jakarta.validation.Valid;
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
    private final FulfilmentService fulfilment;

    public OrderProcessingApiController(AdminService admin, OrderStore orders, FulfilmentService fulfilment) {
        this.admin = admin;
        this.orders = orders;
        this.fulfilment = fulfilment;
    }

    /** Default status filter when {@code ?status=} is omitted (the human-review queue). */
    private static final String DEFAULT_STATUS = "PENDING";
    /** Synthetic status label echoed back for a batch update (not an {@link OrderStatus}). */
    private static final String STATUS_UPDATED = "UPDATED";

    /**
     * Synchronous order intake from the storefront checkout ({@code POST /api/orders/intake}) —
     * the REST replacement for publishing a {@code PurchaseOrderEvent} to PurchaseOrderQueue.
     * Maps the request to a PENDING {@link WarehouseOrder} and hands it to the SAME
     * {@link FulfilmentService#receiveOrder} the queue listener uses, so persistence, the
     * currency auto-approval policy, and outbound-event dispatch (via the outbox) are identical
     * across both intake paths. Returns the persisted order's id + resolved status + stored total.
     *
     * <p>Customer-authenticated (not ADMIN) — the storefront proxies the shopper's JWT; this is
     * the one facade endpoint {@code SecurityConfig} opens to the customer role. Idempotent by the
     * caller-supplied {@code orderId} (the storefront's synchronizer token): {@code receiveOrder}
     * no-ops on an id it already has, and we read the stored order back either way, so a duplicate
     * submit returns the ALREADY-stored id + status rather than creating a second order.
     *
     * <p>Example request — {@link CheckoutRequest} body:
     * <pre>{@code
     * POST /api/orders/intake
     * Authorization: Bearer <customer-jwt>
     * Content-Type: application/json
     *
     * {"orderId":"1042","userId":"asmith","emailId":"asmith@example.com","locale":"en_US",
     *  "currency":"USD","totalPrice":33.0,
     *  "lines":[{"itemId":"EST-1","productId":"FI-SW-01","categoryId":"FISH","quantity":2,"unitPrice":16.5}],
     *  "shipTo":{...},"billTo":{...}}
     * }</pre>
     *
     * <p>Example response — {@link CheckoutResponse} (200):
     * <pre>{@code
     * {"orderId":"1042","status":"APPROVED","totalPrice":33.0}
     * }</pre>
     *
     * <p>Errors: 400 ({@code validation_failed}) on a blank {@code orderId}/{@code userId} or an
     * empty {@code lines} list (bean validation); 401 when the JWT is missing/invalid.
     */
    @PostMapping(OrderProcessingEndpoints.ORDER_INTAKE)
    public CheckoutResponse intake(@Valid @RequestBody CheckoutRequest req) {
        fulfilment.receiveOrder(toDomain(req));
        // Read back the stored order so the response reflects the ACTUAL persisted state — the
        // auto-approval decision (PENDING vs APPROVED) and, for a duplicate submit, the pre-existing
        // order rather than what this (ignored) request carried.
        WarehouseOrder stored = orders.findById(req.orderId()).orElse(null);
        if (stored == null) {
            // Should not happen (receiveOrder just persisted it), but never NPE the shopper.
            return new CheckoutResponse(req.orderId(), OrderStatus.PENDING.name(), req.totalPrice());
        }
        return new CheckoutResponse(stored.orderId(), stored.status().name(), stored.totalPrice());
    }

    /**
     * Order IDs in a given workflow status (defaults to PENDING — the human-review queue).
     * The {@code ?status=} value is upper-cased and parsed to an {@link OrderStatus}, so an
     * unknown value yields a 400 rather than an empty list.
     *
     * <p>Example request (status omitted ⇒ PENDING):
     * <pre>{@code
     * GET /api/orders?status=APPROVED
     * Authorization: Bearer <admin-jwt>
     * }</pre>
     *
     * <p>Example response — {@link OrdersByStatus} (200):
     * <pre>{@code
     * {"status":"APPROVED","orderIds":["1001","1002"],"count":2}
     * }</pre>
     *
     * <p>Errors: 400 ({@code bad_request}) when {@code ?status=} is not one of
     * PENDING/APPROVED/DENIED/COMPLETED (unknown enum → {@code IllegalArgumentException}).
     */
    @GetMapping(OrderProcessingEndpoints.ORDERS)
    public OrdersByStatus byStatus(
            @RequestParam(value = OrderProcessingEndpoints.PARAM_STATUS, defaultValue = DEFAULT_STATUS) String status) {
        OrderStatus s = OrderStatus.valueOf(status.toUpperCase());
        List<String> ids = admin.ordersByStatus(s);
        return new OrdersByStatus(s.name(), ids, ids.size());
    }

    /**
     * All orders as lightweight summaries, most-recently-received first — backs the
     * admin "All Orders" overview. One call (no per-order round-trips); carries the
     * workflow status and received timestamp so the console can render + sort directly.
     * Mapped above {@code /api/orders/{id}} so the literal "all" wins over the path var.
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/orders/all
     * Authorization: Bearer <admin-jwt>
     * }</pre>
     *
     * <p>Example response — a list of {@link OrderSummaryDto} (200):
     * <pre>{@code
     * [
     *   {"orderId":"1002","userId":"jdoe","totalPrice":420.0,"status":"PENDING",
     *    "created":"2026-07-24T10:15:30Z","lineCount":3},
     *   {"orderId":"1001","userId":"asmith","totalPrice":89.5,"status":"COMPLETED",
     *    "created":"2026-07-23T08:02:11Z","lineCount":1}
     * ]
     * }</pre>
     * Always 200 (empty list if no orders exist).
     */
    @GetMapping(OrderProcessingEndpoints.ORDERS_ALL)
    public List<OrderSummaryDto> allOrders() {
        return admin.allOrders().stream()
                .map(o -> new OrderSummaryDto(o.orderId(), o.userId(), o.totalPrice(),
                        o.status().name(), o.created(), o.lines().size()))
                .toList();
    }

    /**
     * Full order detail (lines + contacts) by id; 404 when no such order exists.
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/orders/1001
     * Authorization: Bearer <admin-jwt>
     * }</pre>
     *
     * <p>Example response — {@link OrderView} (200):
     * <pre>{@code
     * {"orderId":"1001","userId":"asmith","emailId":"asmith@example.com","locale":"en_US",
     *  "totalPrice":89.5,"status":"COMPLETED",
     *  "lines":[{"itemId":"EST-1","productId":"FL-DSH-01","categoryId":"FISH",
     *            "quantity":1,"unitPrice":89.5}]}
     * }</pre>
     *
     * <p>Errors: 404 (empty body) when {@code id} matches no stored order.
     */
    @GetMapping(OrderProcessingEndpoints.ORDER_BY_ID)
    public ResponseEntity<OrderView> getOrder(@PathVariable String id) {
        return orders.findById(id).map(o -> ResponseEntity.ok(toView(o)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Just the workflow status of an order (lightweight poll for callers); 404 if unknown.
     *
     * <p>Example request:
     * <pre>{@code
     * GET /api/orders/1001/status
     * Authorization: Bearer <admin-jwt>
     * }</pre>
     *
     * <p>Example response — {@link StatusView} (200):
     * <pre>{@code
     * {"orderId":"1001","status":"APPROVED"}
     * }</pre>
     *
     * <p>Errors: 404 (empty body) when {@code id} matches no stored order.
     */
    @GetMapping(OrderProcessingEndpoints.ORDER_STATUS)
    public ResponseEntity<StatusView> status(@PathVariable String id) {
        OrderStatus s = admin.statusOf(id);
        return s == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(new StatusView(id, s.name()));
    }

    /**
     * Approve a single PENDING order → APPROVED (emits OrderApprovedEvent after commit).
     *
     * <p>Example request (no body):
     * <pre>{@code
     * POST /api/orders/1002/approve
     * Authorization: Bearer <admin-jwt>
     * }</pre>
     *
     * <p>Example response — {@link StatusView} (200):
     * <pre>{@code
     * {"orderId":"1002","status":"APPROVED"}
     * }</pre>
     *
     * <p>Errors: 400 ({@code bad_request}) when the order is unknown ("No such order" →
     * {@code IllegalArgumentException}); 409 ({@code conflict}) when it is not PENDING
     * (illegal transition, e.g. already DENIED/COMPLETED) or a concurrent approve/deny
     * lost the optimistic-lock race.
     */
    @PostMapping(OrderProcessingEndpoints.ORDER_APPROVE)
    public ResponseEntity<StatusView> approve(@PathVariable String id) {
        admin.approve(id);
        return ResponseEntity.ok(new StatusView(id, OrderStatus.APPROVED.name()));
    }

    /**
     * Deny a single PENDING order → DENIED (emits OrderStatusEvent after commit; terminal).
     *
     * <p>Example request (no body):
     * <pre>{@code
     * POST /api/orders/1002/deny
     * Authorization: Bearer <admin-jwt>
     * }</pre>
     *
     * <p>Example response — {@link StatusView} (200):
     * <pre>{@code
     * {"orderId":"1002","status":"DENIED"}
     * }</pre>
     *
     * <p>Errors: 400 ({@code bad_request}) when the order is unknown; 409 ({@code conflict})
     * when it is not PENDING (illegal transition) or a concurrent write lost the
     * optimistic-lock race.
     */
    @PostMapping(OrderProcessingEndpoints.ORDER_DENY)
    public ResponseEntity<StatusView> deny(@PathVariable String id) {
        admin.deny(id);
        return ResponseEntity.ok(new StatusView(id, OrderStatus.DENIED.name()));
    }

    /**
     * Atomic batch status update (legacy updateOrders/OrderApproval). All-or-nothing:
     * either every change is validated + applied in one transaction, or the whole batch
     * rolls back and nothing is published. The echoed {@code status} is the synthetic
     * label {@code "UPDATED"} (see {@link #STATUS_UPDATED}), not an {@link OrderStatus}.
     *
     * <p>Example request — {@link OrderApprovalDto} body:
     * <pre>{@code
     * POST /api/orders/approvals
     * Authorization: Bearer <admin-jwt>
     * Content-Type: application/json
     *
     * {"orders":[{"orderId":"1002","newStatus":"APPROVED"},
     *            {"orderId":"1003","newStatus":"DENIED"}]}
     * }</pre>
     *
     * <p>Example response — {@link OrdersByStatus} (200):
     * <pre>{@code
     * {"status":"UPDATED","orderIds":["1002","1003"],"count":2}
     * }</pre>
     *
     * <p>Errors: 400 ({@code validation_failed}) on an empty {@code orders} list or a blank
     * {@code orderId}/{@code newStatus} (bean validation); 400 ({@code bad_request}) on an
     * unknown {@code newStatus} enum or a missing order in the batch; 409 ({@code conflict})
     * on any illegal transition or lost optimistic-lock race — the whole batch rolls back.
     */
    @PostMapping(OrderProcessingEndpoints.ORDER_APPROVALS)
    public ResponseEntity<OrdersByStatus> updateOrders(@Valid @RequestBody OrderApprovalDto approval) {
        List<OrderStatusChange> changes = approval.orders().stream()
                .map(c -> new OrderStatusChange(c.orderId(), OrderStatus.valueOf(c.newStatus().toUpperCase())))
                .toList();
        admin.updateOrders(changes);
        List<String> ids = changes.stream().map(OrderStatusChange::orderId).toList();
        return ResponseEntity.ok(new OrdersByStatus(STATUS_UPDATED, ids, ids.size()));
    }

    /**
     * Sales aggregation over a date range (legacy getChartInfo). {@code start}/{@code end}
     * are ISO dates (yyyy-MM-dd, inclusive, UTC); {@code category} optional (group by item
     * within it, else by category).
     *
     * <p>Example request (grouped by category — {@code category} omitted):
     * <pre>{@code
     * GET /api/sales?start=2026-07-01&end=2026-07-31
     * Authorization: Bearer <admin-jwt>
     * }</pre>
     * Or grouped by item within one category:
     * <pre>{@code
     * GET /api/sales?start=2026-07-01&end=2026-07-31&category=FISH
     * }</pre>
     *
     * <p>Example response — {@link SalesReportDto} (200):
     * <pre>{@code
     * {"groupBy":"category",
     *  "buckets":[{"key":"FISH","revenue":1240.5,"quantity":14},
     *             {"key":"DOGS","revenue":890.0,"quantity":6}]}
     * }</pre>
     *
     * <p>Errors: 400 ({@code bad_request}) when {@code start} or {@code end} is not a valid
     * ISO date (yyyy-MM-dd → {@code DateTimeParseException}).
     */
    @GetMapping(OrderProcessingEndpoints.SALES)
    public SalesReportDto sales(
            @RequestParam(OrderProcessingEndpoints.PARAM_START) String start,
            @RequestParam(OrderProcessingEndpoints.PARAM_END) String end,
            @RequestParam(value = OrderProcessingEndpoints.PARAM_CATEGORY, required = false) String category) {
        Instant from = LocalDate.parse(start).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = LocalDate.parse(end).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);
        SalesReport report = admin.salesReport(from, to, category);
        List<SalesBucketDto> buckets = report.buckets().stream()
                .map(b -> new SalesBucketDto(b.key(), b.revenue(), b.quantity()))
                .toList();
        return new SalesReportDto(report.groupBy(), buckets);
    }

    /**
     * Map an intake {@link CheckoutRequest} to a PENDING domain {@link WarehouseOrder}. Status is
     * always PENDING here — {@link FulfilmentService#receiveOrder} recomputes it (APPROVED or
     * PENDING) from the auto-approval policy; {@code created} is left null so the service stamps
     * it (parity with the queue path, where {@code OrderListener} derived it from the envelope).
     */
    private static WarehouseOrder toDomain(CheckoutRequest req) {
        List<OrderLine> lines = (req.lines() == null ? List.<LineDto>of() : req.lines()).stream()
                .map(l -> new OrderLine(l.itemId(), l.productId(), l.categoryId(), l.quantity(), l.unitPrice()))
                .toList();
        return new WarehouseOrder(req.orderId(), req.userId(), req.emailId(), req.locale(),
                req.currency(), req.totalPrice(), OrderStatus.PENDING, lines,
                toContact(req.shipTo()), toContact(req.billTo()), null);
    }

    private static ContactInfo toContact(ContactInfoDto c) {
        if (c == null) {
            return null;
        }
        return new ContactInfo(c.familyName(), c.givenName(), c.streetName1(), c.streetName2(),
                c.city(), c.state(), c.zipCode(), c.country(), c.telephone(), c.email());
    }

    private static OrderView toView(WarehouseOrder o) {
        List<LineDto> lines = o.lines().stream()
                .map(l -> new LineDto(l.itemId(), l.productId(), l.categoryId(), l.quantity(), l.unitPrice()))
                .toList();
        return new OrderView(o.orderId(), o.userId(), o.emailId(), o.locale(),
                o.totalPrice(), o.status().name(), lines, o.currency());
    }
}
