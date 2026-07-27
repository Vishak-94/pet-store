package com.petstore.opc.repository.jpa;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.petstore.opc.domain.OrderStatus;

import java.time.Instant;
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
    /** ISO 4217 currency the total is denominated in (see V4 migration); {@link com.petstore.opc.domain.ApprovalPolicy} keys the auto-approval threshold on it. */
    @Column(name = "currency") String currency;
    @Column(name = "total_price") double totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    OrderStatus status;

    /**
     * Optimistic-lock version (see V3 migration). Guards against the approve+deny race: two admins
     * acting on the same PENDING order both read version N, both write; the first commit bumps it to
     * N+1, the second's {@code UPDATE ... WHERE version = N} matches no row and Hibernate throws
     * {@code OptimisticLockingFailureException} (surfaced as 409 by the OPC exception handler).
     * No pessimistic lock needed — the whole transition is a single-row status write.
     */
    @Version
    @Column(name = "version")
    long version;

    /** Order-received timestamp (legacy PurchaseOrder poDate) — for date-range sales aggregation. */
    @Column(name = "created")
    Instant created;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    List<WarehouseLineEntity> lines = new ArrayList<>();

    /** Ship-to contact info collected at checkout (legacy PurchaseOrder shipping address). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "familyName", column = @Column(name = "ship_family_name")),
            @AttributeOverride(name = "givenName", column = @Column(name = "ship_given_name")),
            @AttributeOverride(name = "streetName1", column = @Column(name = "ship_street1")),
            @AttributeOverride(name = "streetName2", column = @Column(name = "ship_street2")),
            @AttributeOverride(name = "city", column = @Column(name = "ship_city")),
            @AttributeOverride(name = "state", column = @Column(name = "ship_state")),
            @AttributeOverride(name = "zipCode", column = @Column(name = "ship_zip")),
            @AttributeOverride(name = "country", column = @Column(name = "ship_country")),
            @AttributeOverride(name = "telephone", column = @Column(name = "ship_telephone")),
            @AttributeOverride(name = "email", column = @Column(name = "ship_email"))
    })
    ContactInfoEmbeddable shipTo;

    /** Bill-to contact info collected at checkout (legacy PurchaseOrder billing address). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "familyName", column = @Column(name = "bill_family_name")),
            @AttributeOverride(name = "givenName", column = @Column(name = "bill_given_name")),
            @AttributeOverride(name = "streetName1", column = @Column(name = "bill_street1")),
            @AttributeOverride(name = "streetName2", column = @Column(name = "bill_street2")),
            @AttributeOverride(name = "city", column = @Column(name = "bill_city")),
            @AttributeOverride(name = "state", column = @Column(name = "bill_state")),
            @AttributeOverride(name = "zipCode", column = @Column(name = "bill_zip")),
            @AttributeOverride(name = "country", column = @Column(name = "bill_country")),
            @AttributeOverride(name = "telephone", column = @Column(name = "bill_telephone")),
            @AttributeOverride(name = "email", column = @Column(name = "bill_email"))
    })
    ContactInfoEmbeddable billTo;
}
