package com.petstore.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

/**
 * Builds {@link RestClient}s that wrap every downstream call in a Resilience4j
 * circuit breaker and — for idempotent reads only — a bounded retry. This lives in
 * the consuming service (NOT the thin SDK jars, which depend only on spring-web) and
 * is layered on top of the SDK's own connect/read timeouts.
 *
 * <p><b>Why method-aware:</b> retrying a POST/PUT (login, register, provision,
 * approve/deny, checkout) could double-submit a non-idempotent side effect, so retry
 * is applied to {@code GET}/{@code HEAD}/{@code OPTIONS} only. The circuit breaker is
 * applied to ALL methods — it never re-executes a call, it just fails fast once a
 * downstream is clearly unhealthy, which is safe for writes too.
 */
public final class ResilientRestClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientRestClient.class);

    /** Same bounded timeouts the SDKs use, so this factory is a drop-in for their default. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private ResilientRestClient() {
    }

    /**
     * A {@link RestClient} for {@code baseUrl} guarded by a circuit breaker + GET-only
     * retry, both named after {@code name} (for logs/metrics).
     */
    public static RestClient forService(String name, String baseUrl) {
        CircuitBreaker breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowSize(20)                       // decide over the last 20 calls
                .failureRateThreshold(50f)                   // open at ≥50% failures
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slowCallDurationThreshold(READ_TIMEOUT)     // a read-timeout-slow call counts as a failure
                .slowCallRateThreshold(50f)
                .build());
        Retry retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(3)                              // 1 try + 2 retries
                .waitDuration(Duration.ofMillis(200))
                .build());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeoutFactory())
                .requestInterceptor(resilienceInterceptor(breaker, retry))
                .build();
    }

    /** Package-visible for testing the method-aware retry / breaker wrapping. */
    static ClientHttpRequestInterceptor resilienceInterceptor(CircuitBreaker breaker, Retry retry) {
        return (request, body, execution) -> {
            boolean idempotent = isIdempotent(request.getMethod());
            try {
                // Circuit breaker wraps EVERY call (fail-fast, no re-execution). Retry wraps
                // only idempotent reads so a write is never silently submitted twice.
                if (idempotent) {
                    return Retry.decorateCheckedSupplier(retry,
                            () -> CircuitBreaker.decorateCheckedSupplier(breaker,
                                    () -> execute(request, body, execution)).get()).get();
                }
                return CircuitBreaker.decorateCheckedSupplier(breaker,
                        () -> execute(request, body, execution)).get();
            } catch (IOException | RuntimeException e) {
                throw e;
            } catch (Throwable t) {                          // from the checked-supplier signature
                throw new IllegalStateException(t);
            }
        };
    }

    private static org.springframework.http.client.ClientHttpResponse execute(
            org.springframework.http.HttpRequest request, byte[] body,
            org.springframework.http.client.ClientHttpRequestExecution execution) throws IOException {
        return execution.execute(request, body);
    }

    private static boolean isIdempotent(HttpMethod method) {
        return HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.OPTIONS.equals(method);
    }

    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        f.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return f;
    }
}
