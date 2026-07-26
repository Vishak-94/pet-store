package com.petstore.web;

import com.petstore.messaging.Correlation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Seeds the correlation id for every storefront request — the ORIGIN of the checkout trace.
 * A shopper's checkout publishes a {@code PurchaseOrderEvent} whose {@code EventMeta} pulls the
 * id from the {@link Correlation} MDC, so the id set here follows the order across every
 * downstream service and event (OPC → inventory → notification).
 *
 * <p>Reuses an inbound {@code X-Correlation-Id} header if present (so a gateway/caller can supply
 * one), else mints a fresh id. Mirrors customer-service's {@code CorrelationIdFilter}; the MDC key
 * is the shared {@link Correlation#MDC_KEY} so the whole fleet stitches logs on the same field.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cid = req.getHeader(HEADER);
        if (cid == null || cid.isBlank()) {
            cid = UUID.randomUUID().toString();
        }
        Correlation.set(cid);
        res.setHeader(HEADER, cid);
        try {
            chain.doFilter(req, res);
        } finally {
            Correlation.clear();   // never leak across pooled request threads
        }
    }
}
