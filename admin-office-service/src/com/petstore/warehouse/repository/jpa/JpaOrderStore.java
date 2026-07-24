package com.petstore.warehouse.repository.jpa;

import com.petstore.warehouse.domain.OrderLine;
import com.petstore.warehouse.domain.OrderStatus;
import com.petstore.warehouse.domain.WarehouseOrder;
import com.petstore.warehouse.repository.OrderStore;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaOrderStore implements OrderStore {

    private final WarehouseOrderJpaRepository jpa;

    JpaOrderStore(WarehouseOrderJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public WarehouseOrder save(WarehouseOrder o) {
        WarehouseOrderEntity e = new WarehouseOrderEntity();
        e.orderId = o.orderId();
        e.userId = o.userId();
        e.emailId = o.emailId();
        e.locale = o.locale();
        e.totalPrice = o.totalPrice();
        e.status = o.status();
        for (OrderLine l : o.lines()) {
            WarehouseLineEntity le = new WarehouseLineEntity();
            le.itemId = l.itemId();
            le.productId = l.productId();
            le.categoryId = l.categoryId();
            le.quantity = l.quantity();
            le.unitPrice = l.unitPrice();
            e.lines.add(le);
        }
        return toDomain(jpa.save(e));
    }

    @Override
    public Optional<WarehouseOrder> findById(String orderId) {
        return jpa.findById(orderId).map(this::toDomain);
    }

    @Override
    public void updateStatus(String orderId, OrderStatus status) {
        jpa.findById(orderId).ifPresent(e -> {
            e.status = status;
            jpa.save(e);
        });
    }

    @Override
    public Optional<OrderStatus> statusOf(String orderId) {
        return jpa.findById(orderId).map(e -> e.status);
    }

    @Override
    public List<String> orderIdsByStatus(OrderStatus status) {
        return jpa.findByStatus(status).stream().map(e -> e.orderId).toList();
    }

    private WarehouseOrder toDomain(WarehouseOrderEntity e) {
        List<OrderLine> lines = new ArrayList<>();
        for (WarehouseLineEntity le : e.lines) {
            lines.add(new OrderLine(le.itemId, le.productId, le.categoryId, le.quantity, le.unitPrice));
        }
        return new WarehouseOrder(e.orderId, e.userId, e.emailId, e.locale, e.totalPrice, e.status, lines);
    }
}
