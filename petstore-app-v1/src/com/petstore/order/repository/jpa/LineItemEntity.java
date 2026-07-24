package com.petstore.order.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.petstore.order.domain.LineItem;

/**
 * JPA mapping of an order line item. Uses a surrogate generated id (replaces the
 * legacy __PMPrimaryKey); the FK to the order is a join column on the parent.
 */
@Entity
@Table(name = "line_item")
class LineItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "category_id") String categoryId;
    @Column(name = "product_id") String productId;
    @Column(name = "item_id") String itemId;
    @Column(name = "line_number") String lineNumber;
    @Column(name = "quantity") int quantity;
    @Column(name = "unit_price") double unitPrice;

    protected LineItemEntity() {
    }

    static LineItemEntity fromDomain(LineItem li) {
        LineItemEntity e = new LineItemEntity();
        e.categoryId = li.getCategoryId();
        e.productId = li.getProductId();
        e.itemId = li.getItemId();
        e.lineNumber = li.getLineNumber();
        e.quantity = li.getQuantity();
        e.unitPrice = li.getUnitPrice();
        return e;
    }

    LineItem toDomain() {
        return new LineItem(categoryId, productId, itemId, lineNumber, quantity, unitPrice);
    }
}
