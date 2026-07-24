package com.petstore.opc.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A line item of a warehouse order (read-model from the JMS message). */
@Entity
@Table(name = "wh_line")
class WarehouseLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "item_id") String itemId;
    @Column(name = "product_id") String productId;
    @Column(name = "category_id") String categoryId;
    @Column(name = "quantity") int quantity;
    @Column(name = "unit_price") double unitPrice;
}
