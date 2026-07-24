package com.petstore.catalog.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA mapping of the legacy {@code product_details} table (locale-split).
 * PK = (productid, locale). The base {@code product} table (productid, catid)
 * is queried via {@link ProductBaseRepository} to resolve category membership.
 */
@Entity
@Table(name = "product_details")
@IdClass(ProductDetailEntity.Key.class)
class ProductDetailEntity {

    @Id
    @Column(name = "productid")
    String productid;

    @Id
    @Column(name = "locale")
    String locale;

    @Column(name = "name")
    String name;

    @Column(name = "descn")
    String descn;

    @Column(name = "image")
    String image;

    static class Key implements Serializable {
        String productid;
        String locale;

        Key() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(productid, k.productid) && Objects.equals(locale, k.locale);
        }

        @Override
        public int hashCode() {
            return Objects.hash(productid, locale);
        }
    }
}
