package com.petstore.opc.web;

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
 * Seeds the correlation id for every OPC admin-API request. An admin approve/deny/batch call
 * publishes {@code OrderApprovedEvent}/{@code OrderStatusEvent} via the transactional outbox,
 * whose {@code EventMeta} pulls the id from the {@link Correlation} MDC — so the admin action and
 * every event + downstream service (inventory, notification) it triggers stay on one trace.
 *
 * <p>Reuses an inbound {@code X-Correlation-Id} header when present (so a correlation id set by
 * admin-office-service is honoured), else mints one. Same shared {@link Correlation#MDC_KEY} as
 * the rest of the fleet.
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
            Correlation.clear();
        }
    }
}
