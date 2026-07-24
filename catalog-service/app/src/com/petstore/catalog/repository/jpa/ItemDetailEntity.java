package com.petstore.catalog.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA mapping of the legacy {@code item_details} table (locale-split).
 * PK = (itemid, locale). Carries pricing (listprice/unitcost) and attr1..attr5.
 */
@Entity
@Table(name = "item_details")
@IdClass(ItemDetailEntity.Key.class)
class ItemDetailEntity {

    @Id
    @Column(name = "itemid")
    String itemid;

    @Id
    @Column(name = "locale")
    String locale;

    @Column(name = "listprice")
    BigDecimal listprice;

    @Column(name = "unitcost")
    BigDecimal unitcost;

    @Column(name = "descn")
    String descn;

    @Column(name = "image")
    String image;

    @Column(name = "attr1")
    String attr1;
    @Column(name = "attr2")
    String attr2;
    @Column(name = "attr3")
    String attr3;
    @Column(name = "attr4")
    String attr4;
    @Column(name = "attr5")
    String attr5;

    static class Key implements Serializable {
        String itemid;
        String locale;

        Key() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(itemid, k.itemid) && Objects.equals(locale, k.locale);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemid, locale);
        }
    }
}
