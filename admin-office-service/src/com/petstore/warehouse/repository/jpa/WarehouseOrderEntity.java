package com.petstore.warehouse.repository.jpa;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.petstore.warehouse.domain.OrderStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Warehouse's read-model of an order received from checkout (via JMS) PLUS the
 * workflow status it owns. This combines what the legacy OPC stored (order +
 * ManagerEJBTable status) — warehouse is now the single writer of order status.
 */
@Entity
@Table(name = "wh_order")
class WarehouseOrderEntity {

    @Id
    @Column(name = "order_id")
    String orderId;

    @Column(name = "user_id") String userId;
    @Column(name = "email_id") String emailId;
    @Column(name = "locale") String locale;
    @Column(name = "total_price") double totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    OrderStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    List<WarehouseLineEntity> lines = new ArrayList<>();
}
