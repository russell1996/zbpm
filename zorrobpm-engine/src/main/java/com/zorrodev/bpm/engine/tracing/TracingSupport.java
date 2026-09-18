package com.zorrodev.bpm.engine.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import com.zorrodev.bpm.exchange.TraceHeaders;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * WO-OBS-8 (WB-004): single home for trace/MDC propagation.
 *
 * <p>Why explicit instead of baggage/auto-instrumentation: Spring AMQP has no Boot
 * observation (HTTP/scheduled/JDBC do, the broker does not), so the queue hop is
 * manual in any design — W3C {@code traceparent} carried in AMQP headers here.
 * The {@code @Scheduled} hop (poll thread was never on the author's stack) crosses
 * via the {@code outbox.trace_parent} column (persistence half of WO-REL-26),
 * never via a live MDC. MDC carries the human-greppable pair
 * ({@code traceId}, {@code processInstanceId}); baggage would auto-ride only
 * instrumented transports, which the broker is not — explicit is testable
 * (asserts on concrete values, P-67).
 *
 * <p>No-op safe: with no OTLP endpoint the SDK still mints valid spans (dropped
 * on export); with the starter absent (plain unit tests) use {@link #noop()}.
 */
@Slf4j
@Component
public class TracingSupport {

    /** Wire names live in {@link TraceHeaders} (shared with rabbitmq/worker modules). */
    public static final String TRACE_PARENT_HEADER = TraceHeaders.TRACE_PARENT_HEADER;
    /** AMQP header carrying the process instance id (lookup-free MDC on receive). */
    public static final String PROCESS_INSTANCE_ID_HEADER = TraceHeaders.PROCESS_INSTANCE_ID_HEADER;
    /** MDC/logback key for the OTel hex trace id (same key Boot correlation uses). */
    public static final String MDC_TRACE_ID = TraceHeaders.MDC_TRACE_ID;
    /** MDC/logback key for the business instance id. */
    public static final String MDC_PROCESS_INSTANCE_ID = TraceHeaders.MDC_PROCESS_INSTANCE_ID;

    private final Tracer tracer;

    public TracingSupport(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("zorrobpm");
    }

    /** No-op instance for manually constructed collaborators outside Spring. */
    public static TracingSupport noop() {
        return new TracingSupport(OpenTelemetry.noop());
    }

    private static final TextMapSetter<Map<String, String>> SETTER =
        (carrier, key, value) -> {
            if (carrier != null && key != null && value != null) {
                carrier.put(key, value);
            }
        };

    private static final TextMapGetter<Map<String, ?>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, ?> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, ?> carrier, String key) {
            if (carrier == null) {
                return null;
            }
            Object v = carrier.get(key);
            return v != null ? v.toString() : null;
        }
    };

    /**
     * W3C traceparent of the CURRENT span, or null outside any valid span.
     * Stored into {@code outbox.trace_parent} by producers.
     */
    public String captureTraceParent() {
        Span span = Span.current();
        if (span == null || !span.getSpanContext().isValid()) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, SETTER);
        return carrier.get(TRACE_PARENT_HEADER);
    }

    /**
     * Injects the current traceparent into AMQP headers (tolerant: no-op when
     * there is no valid current span — e.g. unsampled/test paths).
     */
    public void injectCurrent(Map<String, Object> headers) {
        String traceParent = captureTraceParent();
        if (traceParent != null && headers != null) {
            headers.put(TRACE_PARENT_HEADER, traceParent);
        }
    }

    /**
     * Parent context from AMQP headers; {@link Context#root()} when absent or
     * malformed (never throws — a bad header must not break delivery).
     */
    public Context extractParent(Map<String, ?> headers) {
        if (headers == null || headers.isEmpty()) {
            return Context.root();
        }
        try {
            Context extracted = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), headers, GETTER);
            return extracted != null ? extracted : Context.root();
        } catch (RuntimeException e) {
            log.warn("Ignoring malformed traceparent header", e);
            return Context.root();
        }
    }

    /**
     * Opens a child span of the given W3C traceparent (or a root span when null),
     * makes it current, and puts {@code traceId} + {@code processInstanceId} into
     * MDC. Restores prior MDC values on close; ends the span (error when
     * {@code failed}).
     *
     * <p>Use try-with-resources; one scope per processed unit (outbox entry, job,
     * completion, SSE dispatch).
     *
     * <p>WO-OBS-8r2: the prior-MDC snapshot is taken BEFORE any OTel
     * {@code Context} call ({@code startSpan} is pure construction, but
     * {@code makeCurrent} is not). Boot's OTel autoconfiguration installs a
     * JVM-global {@code ContextStorage} wrapper
     * ({@code OpenTelemetryEventPublisherBeansApplicationListener$Wrapper},
     * permanent for the process once any Spring context initializes it) whose
     * {@code Slf4JEventListener} writes {@code MDC.put("traceId", hex)} on EVERY
     * scope attach — same key we use. Reading priors after {@code makeCurrent}
     * captured the bridge-written span hex instead of the caller's value, so
     * {@code close()} "restored" the hex, not the caller's MDC (CI flake:
     * {@code expected: "outer-trace" but was: "<hex>"}, order-dependent on which
     * tests shared the surefire fork). Snapshot-first + restore-last brackets the
     * bridge on both sides: attach-time puts happen before our snapshot, and our
     * restore runs after the bridge's close-time remove+put inside
     * {@code scope.close()}. Disabling the bridge instead (option a) was rejected:
     * it would kill Boot's auto-correlation for every instrumented span, and the
     * manual path must compose with it, not replace it.
     */
    public TraceScope openChildSpan(String traceParent, String spanName, String processInstanceId,
                                    Map<String, String> attributes) {
        // WO-OBS-8r2: snapshot BEFORE touching OTel Context (see javadoc) —
        // after makeCurrent the bridge may already have overwritten these keys.
        String priorTraceId = MDC.get(MDC_TRACE_ID);
        String priorPi = MDC.get(MDC_PROCESS_INSTANCE_ID);
        Context parent = Context.root();
        if (traceParent != null && !traceParent.isBlank()) {
            Map<String, String> carrier = Map.of(TRACE_PARENT_HEADER, traceParent.trim());
            try {
                parent = W3CTraceContextPropagator.getInstance()
                    .extract(Context.root(), carrier, GETTER);
            } catch (RuntimeException e) {
                log.warn("Ignoring malformed trace_parent value", e);
                parent = Context.root();
            }
        }
        var builder = tracer.spanBuilder(spanName != null ? spanName : "zbpm.work")
            .setParent(parent)
            .setSpanKind(SpanKind.INTERNAL);
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            builder.setAttribute(MDC_PROCESS_INSTANCE_ID, processInstanceId);
        }
        if (attributes != null) {
            attributes.forEach((k, v) -> {
                if (k != null && v != null) {
                    builder.setAttribute(k, v);
                }
            });
        }
        Span span = builder.startSpan();
        Scope scope = span.makeCurrent();
        MDC.put(MDC_TRACE_ID, span.getSpanContext().getTraceId());
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            MDC.put(MDC_PROCESS_INSTANCE_ID, processInstanceId);
        }
        return new TraceScope(span, scope, priorTraceId, priorPi);
    }

    /** Puts {@code processInstanceId} into MDC (explicit, testable — not baggage). */
    public void putProcessInstanceId(String processInstanceId) {
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            MDC.put(MDC_PROCESS_INSTANCE_ID, processInstanceId);
        }
    }

    /** Removes only the {@code processInstanceId} key (leaves {@code traceId} alone). */
    public void clearProcessInstanceId() {
        MDC.remove(MDC_PROCESS_INSTANCE_ID);
    }

    /** Scope handle: ends the span and restores the MDC values from before open. */
    public static final class TraceScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private final String priorTraceId;
        private final String priorProcessInstanceId;
        private boolean failed;

        private TraceScope(Span span, Scope scope, String priorTraceId, String priorProcessInstanceId) {
            this.span = span;
            this.scope = scope;
            this.priorTraceId = priorTraceId;
            this.priorProcessInstanceId = priorProcessInstanceId;
        }

        /** Marks the span failed (error status + message event) without throwing. */
        public void failed(Throwable e) {
            this.failed = true;
            try {
                span.recordException(e);
            } catch (RuntimeException ignored) {
                // recording must never break the business path
            }
        }

        /** Current span's W3C trace id (hex) — for asserts, not stringly-typed magic. */
        public String traceId() {
            return span.getSpanContext().getTraceId();
        }

        @Override
        public void close() {
            try {
                if (failed) {
                    span.setStatus(StatusCode.ERROR);
                }
                span.end();
            } finally {
                try {
                    scope.close();
                } finally {
                    // WO-OBS-8r2: restore AFTER scope.close() — the bridge's own
                    // close-time remove+put runs inside scope.close(), so ours
                    // (snapshot taken before makeCurrent) wins by being last.
                    restore(MDC_TRACE_ID, priorTraceId);
                    restore(MDC_PROCESS_INSTANCE_ID, priorProcessInstanceId);
                }
            }
        }

        private static void restore(String key, String prior) {
            if (prior == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, prior);
            }
        }
    }

    /** Attributes helper — keeps call sites free of OTel imports. */
    public static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] != null && kv[i + 1] != null) {
                m.put(kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    /** Current span, for exotic call sites (prefer {@link #openChildSpan}). */
    public Span currentSpan() {
        return Span.current();
    }
}
