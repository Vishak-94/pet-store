package com.petstore.inventory.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Inventory stock levels (from legacy supplier.ear InventoryEJBTable). */
@Entity
@Table(name = "inventory")
class InventoryEntity {

    @Id
    @Column(name = "item_id")
    String itemId;

    @Column(name = "quantity", nullable = false)
    int quantity;
}
