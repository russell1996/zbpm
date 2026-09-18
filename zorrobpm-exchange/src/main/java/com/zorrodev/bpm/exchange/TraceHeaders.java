package com.zorrodev.bpm.exchange;

/**
 * WO-OBS-8 (WB-004): single home for trace-propagation wire names.
 *
 * <p>Lives in {@code zorrobpm-exchange} (not engine) on purpose: the RabbitMQ hop
 * is crossed by three modules ({@code engine} produces, {@code rabbitmq} bridges,
 * {@code job-handler-starter} consumes) and only {@code exchange} is a dependency
 * of all three — importing {@code engine.tracing} anywhere outside engine would
 * drag a module dependency against the layering (P-24). Pure string logic, zero
 * OTel/SLF4J imports: each call site does its own {@code MDC.put} (one line) or
 * opens its own span, so this file never grows an SDK dependency.
 *
 * <p>Wire format is W3C {@code traceparent} ({@code 00-traceid-spanid-flags});
 * {@code processInstanceId} rides alongside as a plain header so the receive side
 * gets lookup-free MDC (the completion path must not pay a DB read per message).
 */
public final class TraceHeaders {

    /** AMQP header carrying the W3C traceparent of the producing span. */
    public static final String TRACE_PARENT_HEADER = "traceparent";
    /** AMQP header carrying the process instance id (lookup-free MDC on receive). */
    public static final String PROCESS_INSTANCE_ID_HEADER = "processInstanceId";
    /** MDC/logback key for the OTel hex trace id (same key Boot correlation uses). */
    public static final String MDC_TRACE_ID = "traceId";
    /** MDC/logback key for the business instance id. */
    public static final String MDC_PROCESS_INSTANCE_ID = "processInstanceId";

    private TraceHeaders() {
    }

    /**
     * Hex trace id from a W3C traceparent, or {@code null} when absent/malformed.
     * Never throws — a bad header must not break delivery (fail-open receive,
     * fail-closed authz is untouched: this only feeds logs/spans).
     */
    public static String extractTraceId(String traceParent) {
        if (traceParent == null || traceParent.isBlank()) {
            return null;
        }
        String[] parts = traceParent.trim().split("-");
        if (parts.length != 4 || parts[1].length() != 32 || !isHex(parts[1])) {
            return null;
        }
        return parts[1].toLowerCase(java.util.Locale.ROOT);
    }

    /** Structural check only (length + hex); sampling/version bits are irrelevant here. */
    public static boolean isValidTraceParent(String traceParent) {
        return extractTraceId(traceParent) != null;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
