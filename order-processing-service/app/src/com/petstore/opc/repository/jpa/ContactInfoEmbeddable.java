package com.petstore.opc.repository.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Persisted ship-to / bill-to contact info, embedded twice on
 * {@link WarehouseOrderEntity} with distinct column prefixes. Mirrors the legacy
 * ContactInfo + Address the OPC stored on the PurchaseOrder.
 */
@Embeddable
class ContactInfoEmbeddable {

    @Column(name = "family_name") String familyName;
    @Column(name = "given_name") String givenName;
    @Column(name = "street1") String streetName1;
    @Column(name = "street2") String streetName2;
    @Column(name = "city") String city;
    @Column(name = "state") String state;
    @Column(name = "zip") String zipCode;
    @Column(name = "country") String country;
    @Column(name = "telephone") String telephone;
    @Column(name = "email") String email;
}
