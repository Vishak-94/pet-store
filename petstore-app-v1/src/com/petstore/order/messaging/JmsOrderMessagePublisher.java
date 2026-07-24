package com.petstore.order.messaging;

import com.petstore.messaging.Destinations;
import com.petstore.messaging.Events;
import com.petstore.messaging.MessagePublisher;
import com.petstore.messaging.events.PurchaseOrderEvent;
import com.petstore.order.domain.LineItem;
import com.petstore.order.domain.PurchaseOrder;
import com.petstore.order.service.OrderMessagePublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publishes a {@link PurchaseOrderEvent} (enveloped JSON) to the shared
 * PurchaseOrderQueue via the {@link MessagePublisher} from petstore-messaging.
 * No local JMS config or message class — the destination, envelope, and converter
 * all come from the library (single-sourced contract).
 */
@Component
public class JmsOrderMessagePublisher implements OrderMessagePublisher {

    private final MessagePublisher publisher;

    public JmsOrderMessagePublisher(MessagePublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publishNewOrder(PurchaseOrder order) {
        publisher.publish(Destinations.PURCHASE_ORDER, toEvent(order));
    }

    private PurchaseOrderEvent toEvent(PurchaseOrder po) {
        List<PurchaseOrderEvent.Line> lines = po.getLineItems().stream()
                .map(this::toLine).toList();
        return new PurchaseOrderEvent(
                Events.meta(PurchaseOrderEvent.TYPE),   // fresh eventId + occurredAt
                po.getOrderId(), po.getUserId(), po.getEmailId(),
                po.getLocale() == null ? null : po.getLocale().toString(),
                po.getTotalPrice(), lines);
    }

    private PurchaseOrderEvent.Line toLine(LineItem li) {
        return new PurchaseOrderEvent.Line(li.getItemId(), li.getProductId(), li.getCategoryId(),
                li.getQuantity(), li.getUnitPrice());
    }
}
