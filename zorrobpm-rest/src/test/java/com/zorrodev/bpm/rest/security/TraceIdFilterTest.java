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
}
