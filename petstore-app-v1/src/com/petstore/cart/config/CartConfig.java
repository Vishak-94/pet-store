package com.petstore.cart.config;

import com.petstore.cart.CartOperations;
import com.petstore.cart.CartStore;
import com.petstore.catalog.client.CatalogServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the embeddable cart library to run IN-PROCESS inside the monolith. The
 * cart is session-local state, so there's no standalone cart server and no HTTP
 * hop — the store and operations are plain Spring beans in this JVM.
 *
 * <p>The {@link CartStore} owns a daemon TTL-sweeper thread; it's registered with
 * {@code destroyMethod = "close"} so the thread is stopped on context shutdown.
 * Item price resolution still goes to catalog-service (a genuinely remote
 * service) via the injected {@link CatalogServiceClient}.
 */
@Configuration
public class CartConfig {

    @Bean(destroyMethod = "close")
    CartStore cartStore(@Value("${cart.ttl-minutes:15}") long ttlMinutes,
                        @Value("${cart.sweep-interval-seconds:60}") long sweepIntervalSeconds) {
        return new CartStore(ttlMinutes, sweepIntervalSeconds);
    }

    @Bean
    CartOperations cartOperations(CartStore cartStore, CatalogServiceClient catalog) {
        return new CartOperations(cartStore, catalog);
    }
}
