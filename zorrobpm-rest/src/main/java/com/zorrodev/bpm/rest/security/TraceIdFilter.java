package com.zorrodev.bpm.rest.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * WO-REL-23: traceId in MDC — taken from incoming X-Request-Id or generated, put into
 * MDC for the whole request, cleared afterwards. Pattern %X{traceId} in logback-spring.xml.
 * Async propagation (outbox/job) must use MDC.getCopyOfContextMap() — plain clear() loses it.
 *
 * <p>WO-OBS-8: with the OTel SDK on the classpath the Boot tracing instrumentation puts
 * the REAL hex trace id into the same MDC key BEFORE/AFTER this filter in the chain
 * (order vs. the observation filter is not contractual) — do NOT overwrite a present
 * value with a random UUID: that would fork the request's logs off its trace. Only
 * mint when the key is absent; still echo the effective value back as X-Request-Id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String HEADER = "X-Request-Id";

    /**
     * WO-SEC-77 (S-DOS-2): the external id is untrusted input — it lands in
     * MDC (log lines), in the echoed response header and potentially in
     * downstream propagation. Only printable ASCII without whitespace
     * (visible chars {@code 0x21..0x7E}) up to {@value #MAX_TRACE_ID_LENGTH}
     * chars is accepted as-is; anything else (CRLF/log injection,
     * non-printable/unicode, overlong, blank) is replaced by a minted id.
     * 128 keeps every realistic client correlation id (UUIDs are 36) while
     * bounding log/header bloat.
     */
    static final int MAX_TRACE_ID_LENGTH = 128;

    static boolean isAcceptableTraceId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TRACE_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = MDC.get(TRACE_ID);
        boolean owned = false;
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader(HEADER);
            if (!isAcceptableTraceId(traceId)) {
                traceId = UUID.randomUUID().toString();
            }
            MDC.put(TRACE_ID, traceId);
            owned = true;
        }
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Yield: only clear what we put. An OTel-owned traceId outlives the request
            // scope (the observation filter manages its lifecycle, not us) — clearing it
            // here would blank the trace for any after-completion logging.
            if (owned) {
                MDC.remove(TRACE_ID);
            }
        }
    }
}
