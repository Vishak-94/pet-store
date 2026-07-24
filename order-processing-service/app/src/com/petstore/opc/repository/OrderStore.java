package com.petstore.opc.repository;

import com.petstore.opc.domain.OrderStatus;
import com.petstore.opc.domain.WarehouseOrder;

import java.util.List;
import java.util.Optional;

/** Port for the warehouse's order read-model + workflow status (owned here). */
public interface OrderStore {

    /** Store an order received from checkout with its initial status. */
    WarehouseOrder save(WarehouseOrder order);

    Optional<WarehouseOrder> findById(String orderId);

    void updateStatus(String orderId, OrderStatus status);

    Optional<OrderStatus> statusOf(String orderId);

    List<String> orderIdsByStatus(OrderStatus status);
}
