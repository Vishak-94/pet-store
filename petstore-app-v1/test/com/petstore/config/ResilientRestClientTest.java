package com.petstore.config;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the safety-critical resilience contract: idempotent GETs are retried, but a
 * non-idempotent POST is executed exactly once (never double-submitted), and the
 * breaker opens after sustained failures.
 */
class ResilientRestClientTest {

    private static Retry retry() {
        return Retry.of("t", RetryConfig.custom()
                .maxAttempts(3).waitDuration(Duration.ofMillis(1)).build());
    }

    private static CircuitBreaker breaker() {
        return CircuitBreaker.of("t", CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(100)   // effectively never opens mid-test
                .build());
    }

    /** Counts executions and always fails, so we can see how many attempts happened. */
    private static int attemptsFor(HttpMethod method, CircuitBreaker cb, Retry rt) {
        AtomicInteger calls = new AtomicInteger();
        ClientHttpRequestExecution failing = (req, body) -> {
            calls.incrementAndGet();
            throw new IOException("boom");
        };
        ClientHttpRequestInterceptor interceptor = ResilientRestClient.resilienceInterceptor(cb, rt);
        try {
            interceptor.intercept(new MockClientHttpRequest(method, URI.create("/x")), new byte[0], failing);
        } catch (Exception expected) {
            // failure propagates after attempts are exhausted
        }
        return calls.get();
    }

    @Test
    void get_isRetried_threeAttempts() {
        assertThat(attemptsFor(HttpMethod.GET, breaker(), retry())).isEqualTo(3);
    }

    @Test
    void post_isNotRetried_executedExactlyOnce() {
        assertThat(attemptsFor(HttpMethod.POST, breaker(), retry())).isEqualTo(1);
    }

    @Test
    void put_isNotRetried_executedExactlyOnce() {
        assertThat(attemptsFor(HttpMethod.PUT, breaker(), retry())).isEqualTo(1);
    }

    @Test
    void openBreaker_failsFastWithoutExecuting() throws Exception {
        CircuitBreaker cb = CircuitBreaker.of("open", CircuitBreakerConfig.custom()
                .slidingWindowSize(1).minimumNumberOfCalls(1).failureRateThreshold(1f).build());
        cb.transitionToOpenState();   // force open
        AtomicInteger calls = new AtomicInteger();
        ClientHttpRequestExecution exec = (req, body) -> {
            calls.incrementAndGet();
            throw new IOException("should not be called");
        };
        ClientHttpRequestInterceptor interceptor = ResilientRestClient.resilienceInterceptor(cb, retry());
        assertThatThrownBy(() -> interceptor.intercept(
                new MockClientHttpRequest(HttpMethod.POST, URI.create("/x")), new byte[0], exec))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(calls.get()).isZero();   // failed fast — downstream never touched
    }
}
