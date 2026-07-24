package com.petstore.catalog.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of the legacy {@code item} base table (itemid, productid).
 * Holds item→product membership; locale-specific text/pricing lives in
 * {@link ItemDetailEntity}.
 */
@Entity
@Table(name = "item")
class ItemBaseEntity {

    @Id
    @Column(name = "itemid")
    String itemid;

    @Column(name = "productid")
    String productid;
}
