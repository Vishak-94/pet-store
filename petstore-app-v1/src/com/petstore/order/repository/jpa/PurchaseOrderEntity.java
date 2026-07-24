package com.petstore.order.repository.jpa;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.petstore.order.domain.LineItem;
import com.petstore.order.domain.PurchaseOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JPA mapping of the purchase order — redesigned from the legacy CMP
 * PurchaseOrderEJBTable (real types: timestamp date, decimal total, proper
 * 1—N to line items via a join column instead of the __PMPrimaryKey scheme).
 */
@Entity
@Table(name = "purchase_order")
class PurchaseOrderEntity {

    @Id
    @Column(name = "order_id")
    String orderId;

    @Column(name = "user_id") String userId;
    @Column(name = "email_id") String emailId;
    @Column(name = "order_date") Instant orderDate;
    @Column(name = "locale") String locale;
    @Column(name = "total_price") double totalPrice;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = jakarta.persistence.FetchType.EAGER)
    @jakarta.persistence.JoinColumn(name = "order_id")
    List<LineItemEntity> lineItems = new ArrayList<>();

    protected PurchaseOrderEntity() {
    }

    static PurchaseOrderEntity fromDomain(PurchaseOrder po) {
        PurchaseOrderEntity e = new PurchaseOrderEntity();
        e.orderId = po.getOrderId();
        e.userId = po.getUserId();
        e.emailId = po.getEmailId();
        e.orderDate = po.getOrderDate();
        e.locale = po.getLocale() == null ? null : po.getLocale().toString();
        e.totalPrice = po.getTotalPrice();
        for (LineItem li : po.getLineItems()) {
            e.lineItems.add(LineItemEntity.fromDomain(li));
        }
        return e;
    }

    PurchaseOrder toDomain() {
        List<LineItem> items = new ArrayList<>();
        for (LineItemEntity le : lineItems) {
            items.add(le.toDomain());
        }
        Locale loc = locale == null ? Locale.US : Locale.forLanguageTag(locale.replace('_', '-'));
        return new PurchaseOrder(orderId, userId, emailId, orderDate, loc, totalPrice, items);
    }
}
