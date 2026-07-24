package com.petstore.order.repository;

import com.petstore.order.domain.PurchaseOrder;

import java.util.Optional;

/**
 * Persistence <b>port</b> for orders. The monolith now owns only order CREATION
 * (purchase_order + line_item); workflow status + fulfilment moved to
 * warehouse-service.
 */
public interface OrderRepository {

    /** Persist a new purchase order (with its line items). */
    PurchaseOrder save(PurchaseOrder order);

    Optional<PurchaseOrder> findById(String orderId);
}
