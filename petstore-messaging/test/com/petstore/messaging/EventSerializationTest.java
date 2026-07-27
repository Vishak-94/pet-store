package com.petstore.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.petstore.messaging.events.InvoiceEvent;
import com.petstore.messaging.events.PurchaseOrderEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire format: enveloped events serialize to JSON with the metadata +
 * payload and round-trip back to the same record. Also checks the type-id map is
 * complete (every event type is registered for routing).
 */
class EventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void purchaseOrderEvent_roundTrips_withEnvelope() throws Exception {
        var shipTo = new PurchaseOrderEvent.ContactInfo("Doe", "Jane", "1 Main St", null,
                "Seattle", "WA", "98101", "US", "555-1234", "jane@x.com");
        var billTo = new PurchaseOrderEvent.ContactInfo("Doe", "Jane", "2 Bill Ave", "Apt 3",
                "Seattle", "WA", "98102", "US", "555-5678", "jane@x.com");
        var event = new PurchaseOrderEvent(
                Events.meta(PurchaseOrderEvent.TYPE, "corr-1"),
                "1001", "u-1", "u@x.com", "en_US", 35.0,
                List.of(new PurchaseOrderEvent.Line("EST-1", "FI-SW-01", "FISH", 1, 16.5)),
                shipTo, billTo, "USD");

        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"eventId\"").contains("\"type\":\"PurchaseOrder\"")
                .contains("\"correlationId\":\"corr-1\"").contains("\"orderId\":\"1001\"")
                .contains("\"currency\":\"USD\"");

        PurchaseOrderEvent back = mapper.readValue(json, PurchaseOrderEvent.class);
        assertThat(back.orderId()).isEqualTo("1001");
        assertThat(back.meta().correlationId()).isEqualTo("corr-1");
        assertThat(back.currency()).isEqualTo("USD");
        assertThat(back.lines()).singleElement()
                .satisfies(l -> assertThat(l.unitPrice()).isEqualTo(16.5));
        assertThat(back.shipTo().city()).isEqualTo("Seattle");
        assertThat(back.shipTo().streetName2()).isNull();
        assertThat(back.billTo().streetName1()).isEqualTo("2 Bill Ave");
    }

    @Test
    void purchaseOrderEvent_withoutCurrency_stillDeserializes() throws Exception {
        // An older producer's message has no `currency` field. It must still deserialize on a
        // newer consumer (additive-field rule): the missing field maps to null, and OPC treats
        // a null currency as USD. FAIL_ON_UNKNOWN/absent must not break the round-trip.
        String legacyJson = "{\"meta\":{\"eventId\":\"e1\",\"type\":\"PurchaseOrder\","
                + "\"occurredAt\":\"2020-01-01T00:00:00Z\",\"correlationId\":null},"
                + "\"orderId\":\"900\",\"userId\":\"u-1\",\"emailId\":\"u@x.com\","
                + "\"locale\":\"en_US\",\"totalPrice\":10.0,\"lines\":[],"
                + "\"shipTo\":null,\"billTo\":null}";
        PurchaseOrderEvent back = mapper.readValue(legacyJson, PurchaseOrderEvent.class);
        assertThat(back.orderId()).isEqualTo("900");
        assertThat(back.currency()).isNull();   // absent → null → USD default downstream
    }

    @Test
    void invoiceEvent_roundTrips() throws Exception {
        var e = new InvoiceEvent(Events.meta(InvoiceEvent.TYPE), "1001", "u-1", "u@x.com", true, 35.0);
        InvoiceEvent back = mapper.readValue(mapper.writeValueAsString(e), InvoiceEvent.class);
        assertThat(back.shipped()).isTrue();
        assertThat(back.orderId()).isEqualTo("1001");
    }

    @Test
    void typeIdMap_coversAllEvents() {
        assertThat(MessagingConfig.TYPE_IDS)
                .containsKeys("PurchaseOrder", "OrderApproved", "Invoice", "OrderStatus");
    }

    @Test
    void destinations_haveCorrectKind() {
        assertThat(Destinations.PURCHASE_ORDER.topic()).isFalse();
        assertThat(Destinations.APPROVED_ORDER.topic()).isFalse();
        assertThat(Destinations.INVOICE.topic()).isTrue();   // legacy InvoiceTopic
    }
}
