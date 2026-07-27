package com.petstore.order.service;

import com.petstore.cart.domain.CartItem;
import com.petstore.cart.service.CartService;
import com.petstore.messaging.events.PurchaseOrderEvent;
import com.petstore.opc.client.OrderDtos.CheckoutRequest;
import com.petstore.opc.client.OrderDtos.CheckoutResponse;
import com.petstore.opc.client.OrderDtos.ContactInfoDto;
import com.petstore.opc.client.OrderDtos.LineDto;
import com.petstore.opc.client.OrderProcessingClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checkout orchestration — faithful to the legacy {@code OrderEJBAction}: generate an
 * order id, build the order from the cart (validating non-empty, computing the total),
 * and hand it to the Order Processing Center (OPC). It does NOT persist the order — the
 * OPC (order-processing-service) is the authoritative order store.
 *
 * <p><b>Intake is now SYNCHRONOUS REST</b> ({@code POST /api/orders/intake} via
 * {@link OrderProcessingClient}), replacing the old fire-and-forget publish of a
 * {@code PurchaseOrderEvent} to {@code PurchaseOrderQueue} (see {@code DECISIONS.md}).
 * The storefront proxies the shopper's JWT so OPC can authorize the intake endpoint for
 * the customer role, and OPC returns the persisted id + resolved status (PENDING/APPROVED)
 * so the result page needs no follow-up fetch. Downstream fulfilment stays JMS-based; only
 * the storefront→OPC intake hop changed.
 *
 * <p>Flow: empty cart → EmptyCartException; build the request from the cart (server-minted
 * order id); call OPC; empty the cart <b>only on success</b>. If OPC is unreachable the call
 * throws {@link OrderIntakeUnavailableException} and the cart is left intact so the shopper
 * can retry (the controllers map it to a clean 503).
 */
@Service
public class OrderService {

    private static final String LOCALE = Locale.US.toString();

    /**
     * ISO 4217 currency the published order total is denominated in. Fixed to {@code USD}
     * because the storefront hardcodes {@code locale = en_US} on every order (a documented
     * legacy quirk), so today all totals are in US dollars. This makes the money dimension
     * EXPLICIT rather than inferred from the locale downstream (OPC keys the auto-approval
     * threshold on currency). Making it vary per order — from the shopper's locale or a
     * checkout selector — is a separate feature; for now it mirrors the always-USD reality.
     */
    private static final String CURRENCY = "USD";

    private final CartService cart;
    private final OrderProcessingClient orderProcessing;
    private final OrderIdGenerator ids;

    public OrderService(CartService cart, OrderProcessingClient orderProcessing, OrderIdGenerator ids) {
        this.cart = cart;
        this.orderProcessing = orderProcessing;
        this.ids = ids;
    }

    /** The outcome of a checkout — the id assigned and the total (for the result page). */
    public record OrderPlaced(String orderId, double total) {
    }

    /**
     * Places an order from the current cart: build the intake request, POST it to the Order
     * Processing Center synchronously, empty the cart. No local persistence.
     *
     * <p>{@code shipTo}/{@code billTo} carry the ship-to and bill-to contact info the legacy
     * {@code OrderEJBAction} collected at checkout; they are forwarded on the intake request
     * (may be null for the API path that doesn't collect them). {@code bearer} is the acting
     * shopper's JWT — proxied to OPC so it can authorize the intake endpoint for the customer role.
     *
     * @throws EmptyCartException             if the cart has no resolvable items
     * @throws OrderIntakeUnavailableException if OPC cannot be reached (cart is left intact to retry)
     */
    public OrderPlaced checkout(String bearer, String userId, String emailId,
                                PurchaseOrderEvent.ContactInfo shipTo,
                                PurchaseOrderEvent.ContactInfo billTo) {
        return checkout(bearer, ids.nextId(), userId, emailId, shipTo, billTo);
    }

    /**
     * Places an order using a <b>caller-supplied</b> {@code orderId} instead of minting a fresh
     * one — the idempotent checkout path. The HTML storefront mints the id when it renders the
     * checkout page (a server-side synchronizer token) and passes it here on submit, so a refresh
     * / double-click / multi-tab replay carries the <i>same</i> id and OPC's {@code findById} dedup
     * (keyed on the {@code order_id} primary key) collapses it to a no-op — a duplicate submit
     * returns the already-stored order. The id must be a server-minted {@link OrderIdGenerator}
     * value — never trusted straight from the client.
     *
     * @throws EmptyCartException             if the cart has no resolvable items
     * @throws OrderIntakeUnavailableException if OPC cannot be reached (cart is left intact to retry)
     */
    public OrderPlaced checkout(String bearer, String orderId, String userId, String emailId,
                                PurchaseOrderEvent.ContactInfo shipTo,
                                PurchaseOrderEvent.ContactInfo billTo) {
        List<CartItem> items = cart.getItems();
        if (items.isEmpty()) {
            throw new EmptyCartException();
        }

        List<LineDto> lines = new ArrayList<>();
        double total = 0d;
        for (CartItem ci : items) {
            double cost = ci.getUnitCost();
            total += cost * ci.getQuantity();
            lines.add(new LineDto(ci.getItemId(), ci.getProductId(), ci.getCategory(),
                    ci.getQuantity(), cost));
        }

        CheckoutRequest request = new CheckoutRequest(
                orderId, userId, emailId, LOCALE, CURRENCY, total, lines,
                toDto(shipTo), toDto(billTo));

        CheckoutResponse response;
        try {
            response = orderProcessing.checkout(request, bearer);   // → order-processing-service
        } catch (RestClientException e) {
            // OPC down / breaker open — surface as a retryable 503; do NOT empty the cart.
            throw new OrderIntakeUnavailableException(
                    "Order intake is temporarily unavailable; the order was not placed", e);
        }

        cart.empty();
        double placedTotal = response != null ? response.totalPrice() : total;
        return new OrderPlaced(orderId, placedTotal);
    }

    /** Convenience overload for callers that don't collect ship/bill contact info. */
    public OrderPlaced checkout(String bearer, String userId, String emailId) {
        return checkout(bearer, userId, emailId, null, null);
    }

    /** Maps the messaging {@code ContactInfo} the controllers collect to the intake wire DTO. */
    private static ContactInfoDto toDto(PurchaseOrderEvent.ContactInfo c) {
        if (c == null) {
            return null;
        }
        return new ContactInfoDto(c.familyName(), c.givenName(), c.streetName1(), c.streetName2(),
                c.city(), c.state(), c.zipCode(), c.country(), c.telephone(), c.email());
    }
}
