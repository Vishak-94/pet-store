package com.petstore.catalog.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA mapping of the legacy {@code category_details} table (locale-split).
 * PK = (catid, locale). Internal to the JPA adapter — never leaks to the domain.
 */
@Entity
@Table(name = "category_details")
@IdClass(CategoryDetailEntity.Key.class)
class CategoryDetailEntity {

    @Id
    @Column(name = "catid")
    String catid;

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
        String catid;
        String locale;

        Key() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(catid, k.catid) && Objects.equals(locale, k.locale);
        }

        @Override
        public int hashCode() {
            return Objects.hash(catid, locale);
        }
    }
}
