package com.petstore.order.repository.jpa;

import com.petstore.order.domain.PurchaseOrder;
import com.petstore.order.repository.OrderRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** JPA adapter implementing the {@link OrderRepository} port (order creation only). */
@Repository
public class JpaOrderRepository implements OrderRepository {

    private final PurchaseOrderJpaRepository orders;

    JpaOrderRepository(PurchaseOrderJpaRepository orders) {
        this.orders = orders;
    }

    @Override
    public PurchaseOrder save(PurchaseOrder order) {
        return orders.save(PurchaseOrderEntity.fromDomain(order)).toDomain();
    }

    @Override
    public Optional<PurchaseOrder> findById(String orderId) {
        return orders.findById(orderId).map(PurchaseOrderEntity::toDomain);
    }
}
