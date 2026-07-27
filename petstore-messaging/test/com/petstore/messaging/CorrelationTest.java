package com.petstore.messaging;

import com.petstore.messaging.events.PurchaseOrderEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the correlation-id carrier: {@link Events#meta(String)} stamps the ambient
 * {@link Correlation} id onto the envelope, so a producer publishing while a request/inbound
 * event is in scope propagates the trace with no explicit plumbing. Also checks set/clear
 * hygiene (no leak) and the null-safe fallback.
 */
class CorrelationTest {

    @AfterEach
    void tearDown() {
        Correlation.clear();   // guard against a leaked id bleeding into the next test
    }

    @Test
    void metaPullsCorrelationIdFromScope() {
        Correlation.set("corr-42");
        var event = new PurchaseOrderEvent(Events.meta(PurchaseOrderEvent.TYPE),
                "1001", "u", "u@x.com", "en_US", 10.0, java.util.List.of(), null, null, "USD");
        assertThat(event.meta().correlationId()).isEqualTo("corr-42");
    }

    @Test
    void metaIsNullWhenNoIdInScope() {
        assertThat(Correlation.current()).isNull();
        assertThat(Events.meta(PurchaseOrderEvent.TYPE).correlationId()).isNull();
    }

    @Test
    void setIgnoresBlankAndClearRemoves() {
        Correlation.set("  ");
        assertThat(Correlation.current()).isNull();   // blank is a no-op, not an empty id

        Correlation.set("corr-1");
        assertThat(Correlation.current()).isEqualTo("corr-1");
        Correlation.clear();
        assertThat(Correlation.current()).isNull();
    }

    @Test
    void explicitCorrelationIdOverloadStillWins() {
        Correlation.set("ambient");
        // The two-arg overload takes an explicit id and does NOT consult the MDC.
        assertThat(Events.meta(PurchaseOrderEvent.TYPE, "explicit").correlationId()).isEqualTo("explicit");
    }
}
