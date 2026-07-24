package com.petstore.catalog.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of the legacy {@code product} base table (productid, catid).
 * Holds the product→category membership; locale-specific text lives in
 * {@link ProductDetailEntity}.
 */
@Entity
@Table(name = "product")
class ProductBaseEntity {

    @Id
    @Column(name = "productid")
    String productid;

    @Column(name = "catid")
    String catid;
}
