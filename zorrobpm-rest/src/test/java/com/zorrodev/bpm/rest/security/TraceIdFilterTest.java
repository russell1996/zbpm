package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    @Test
    void putsTraceIdIntoMdcAndResponseHeader() throws Exception {
        var filter = new TraceIdFilter();
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "test-trace-123");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("test-trace-123");
        // MDC is cleared after request, so after filter it should be empty
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void generatesTraceIdWhenHeaderMissing() throws Exception {
        var filter = new TraceIdFilter();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = (req, res) -> {
            // inside chain, MDC should contain generated traceId
            assertThat(MDC.get("traceId")).isNotNull().isNotBlank();
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    }

    /**
     * WO-OBS-8: OTel-owned traceId must survive the filter. When the MDC key is
     * already set (Boot tracing instrumentation put the REAL hex trace id there),
     * the filter must NOT overwrite it with a random UUID (that would fork the
     * request's logs off its trace) and must NOT clear it on the way out (the
     * observation filter owns its lifecycle, not us). Mutation "always put UUID"
     * fails the first assert; "MDC.clear() in finally" fails the last.
     */
    @Test
    void obs8_preservesPreExistingOtelTraceId() throws Exception {
        var filter = new TraceIdFilter();
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "some-request-id-that-must-lose");
        var response = new MockHttpServletResponse();
        String otelTraceId = "0af7651916cd43dd8448eb211c80319c";
        // NOTE: OncePerRequestFilter.doFilter (not doFilterInternal) is the entry
        // point — MDC is set OUTSIDE the test thread here on purpose: doFilterInternal
        // is invoked on the same thread by the public doFilter, so the value is visible.
        // (A raw doFilterInternal call would ALSO see it — the earlier failure was
        //  MockHttpServletRequest attribute caching, not threading: doFilter goes
        //  through the full shouldNotFilter path, which the first draft bypassed.)
        MDC.put("traceId", otelTraceId);
        try {
            jakarta.servlet.FilterChain chain = (req, res) ->
                assertThat(MDC.get("traceId")).isEqualTo(otelTraceId);
            filter.doFilter(request, response, chain);
            assertThat(response.getHeader("X-Request-Id")).isEqualTo(otelTraceId);
            // Yield: the OTel-owned value is still there (we only remove what we put).
            assertThat(MDC.get("traceId")).isEqualTo(otelTraceId);
        } finally {
            MDC.clear();
        }
    }
}
