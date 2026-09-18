package com.zorrodev.bpm.exchange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OBS-8: wire-format unit tests for the trace-propagation constants.
 *
 * <p>Every hop (outbox column → Spring event → AMQP header → MDC/span) keys off
 * these names — a typo'd header forks the trace silently (GREEN tests, dead
 * diagnostics), so the exact wire values are pinned here.
 */
class TraceHeadersTest {

    @Test
    void wireNames_areW3cAndMdcCompatible() {
        assertThat(TraceHeaders.TRACE_PARENT_HEADER).isEqualTo("traceparent");
        assertThat(TraceHeaders.PROCESS_INSTANCE_ID_HEADER).isEqualTo("processInstanceId");
        assertThat(TraceHeaders.MDC_TRACE_ID).isEqualTo("traceId");
        assertThat(TraceHeaders.MDC_PROCESS_INSTANCE_ID).isEqualTo("processInstanceId");
    }

    @Test
    void extractTraceId_validTraceparent_returnsHexTraceId() {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String traceParent = "00-" + traceId + "-b7ad6b7169203331-01";
        assertThat(TraceHeaders.extractTraceId(traceParent)).isEqualTo(traceId);
    }

    @Test
    void extractTraceId_malformed_returnsNull_neverThrows() {
        assertThat(TraceHeaders.extractTraceId(null)).isNull();
        assertThat(TraceHeaders.extractTraceId("")).isNull();
        assertThat(TraceHeaders.extractTraceId("   ")).isNull();
        assertThat(TraceHeaders.extractTraceId("not-a-traceparent")).isNull();
        assertThat(TraceHeaders.extractTraceId("00-tooshort-01")).isNull();
        // Right shape, non-hex trace id → not a trace id.
        assertThat(TraceHeaders.extractTraceId(
            "00-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-b7ad6b7169203331-01")).isNull();
        assertThat(TraceHeaders.isValidTraceParent("garbage")).isFalse();
        assertThat(TraceHeaders.isValidTraceParent(
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")).isTrue();
    }
}
