package com.petstore.order.service;

import com.petstore.order.domain.PurchaseOrder;

/**
 * Messaging <b>port</b> for handing a completed purchase order to the async
 * fulfilment backbone. Replaces the legacy {@code AsyncSenderEJB} which sent the
 * PO as XML onto the JMS PurchaseOrderQueue. Keeping this as a port lets the
 * checkout service stay ignorant of the transport (JMS/Artemis).
 */
public interface OrderMessagePublisher {

    void publishNewOrder(PurchaseOrder order);
}
