package com.zorrodev.bpm.engine.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OBS-8: {@link TracingSupport} unit tests against a REAL SDK (in-memory
 * exporter) — never the noop tracer: with noop every span is invalid and
 * {@code captureTraceParent()} is structurally null, so a noop-based test
 * could not fail even if the propagation code were deleted (P-67).
 */
class TracingSupportTest {

    private InMemorySpanExporter exporter;
    private TracingSupport tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .build();
        tracing = new TracingSupport(sdk);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        exporter.reset();
    }

    @Test
    void captureTraceParent_outsideAnySpan_returnsNull() {
        assertThat(tracing.captureTraceParent()).isNull();
    }

    @Test
    void openChildSpan_setsMdcTraceIdAndProcessInstanceId_restoresAfter() {
        MDC.put(TracingSupport.MDC_TRACE_ID, "outer-trace");
        MDC.put(TracingSupport.MDC_PROCESS_INSTANCE_ID, "outer-pi");

        try (TracingSupport.TraceScope scope = tracing.openChildSpan(
                null, "test.work", "pi-123", TracingSupport.attrs("k", "v"))) {
            assertThat(MDC.get(TracingSupport.MDC_TRACE_ID))
                .isNotEqualTo("outer-trace")
                .matches("[0-9a-f]{32}");
            assertThat(MDC.get(TracingSupport.MDC_PROCESS_INSTANCE_ID)).isEqualTo("pi-123");
            assertThat(scope.traceId()).isEqualTo(MDC.get(TracingSupport.MDC_TRACE_ID));
        }

        assertThat(MDC.get(TracingSupport.MDC_TRACE_ID)).isEqualTo("outer-trace");
        assertThat(MDC.get(TracingSupport.MDC_PROCESS_INSTANCE_ID)).isEqualTo("outer-pi");
    }

    @Test
    void traceParentRoundTrip_childSharesTraceIdWithParent() {
        String parentTraceParent;
        try (TracingSupport.TraceScope parent = tracing.openChildSpan(null, "parent", "pi-1", null)) {
            parentTraceParent = tracing.captureTraceParent();
            assertThat(parentTraceParent).startsWith("00-");
        }
        String parentTraceId = parentTraceParent.split("-")[1];

        try (TracingSupport.TraceScope child = tracing.openChildSpan(parentTraceParent, "child", "pi-1", null)) {
            assertThat(child.traceId()).isEqualTo(parentTraceId);
            assertThat(MDC.get(TracingSupport.MDC_TRACE_ID)).isEqualTo(parentTraceId);
        }

        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        assertThat(exporter.getFinishedSpanItems())
            .allSatisfy(span -> assertThat(span.getSpanContext().getTraceId()).isEqualTo(parentTraceId));
    }

    @Test
    void malformedTraceParent_startsNewTrace_neverThrows() {
        try (TracingSupport.TraceScope scope = tracing.openChildSpan(
                "garbage-value", "recovered", "pi-9", null)) {
            assertThat(scope.traceId()).matches("[0-9a-f]{32}");
            assertThat(MDC.get(TracingSupport.MDC_TRACE_ID)).isEqualTo(scope.traceId());
            assertThat(MDC.get(TracingSupport.MDC_PROCESS_INSTANCE_ID)).isEqualTo("pi-9");
        }
    }

    @Test
    void injectExtractHeaders_roundTrip() {
        String traceParent;
        try (TracingSupport.TraceScope scope = tracing.openChildSpan(null, "producer", null, null)) {
            Map<String, Object> headers = new HashMap<>();
            tracing.injectCurrent(headers);
            traceParent = (String) headers.get(TracingSupport.TRACE_PARENT_HEADER);
            assertThat(traceParent).startsWith("00-");
        }

        Map<String, Object> received = Map.of(TracingSupport.TRACE_PARENT_HEADER, traceParent);
        io.opentelemetry.context.Context parent = tracing.extractParent(received);
        assertThat(parent).isNotEqualTo(io.opentelemetry.context.Context.root());
        assertThat(tracing.extractParent(null)).isEqualTo(io.opentelemetry.context.Context.root());
        assertThat(tracing.extractParent(Map.of())).isEqualTo(io.opentelemetry.context.Context.root());
    }

    @Test
    void injectCurrent_outsideSpan_writesNoHeader() {
        Map<String, Object> headers = new HashMap<>();
        tracing.injectCurrent(headers);
        assertThat(headers).doesNotContainKey(TracingSupport.TRACE_PARENT_HEADER);
    }

    @Test
    void currentSpanOutsideScope_isInvalid() {
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    /**
     * WO-OBS-8r2, структурный тест гонки (п.3 задачи раунда): в форке поднят
     * конкурирующий {@code ContextStorage}-wrapper, дословно повторяющий поведение
     * прод-бриджа (Boot's {@code EventPublishingContextWrapper} +
     * {@code Slf4JEventListener}: {@code MDC.put(traceId, hex)} на attach,
     * {@code remove + put(restored)} на close — тот же ключ {@code traceId}).
     * Restore обязан побеждать — assert на КОНКРЕТНЫЕ значения caller'а, а не
     * "что-то восстановилось" (P-67). На старом коде (snapshot после makeCurrent)
     * этот тест красный с той же сигнатурой, что CI: {@code expected:
     * "outer-trace" but was: "<hex>"} — проверено мутацией, см. отчёт.
     *
     * <p>"Проходит 100 раз подряд" гонку бы не доказал (зависит от порядка/форка) —
     * этот тест поднимает конфликт явно, детерминированно, в любом порядке.
     */
    @Test
    void openChildSpan_restoresCallerMdc_despiteCompetingContextStorageWrapper() {
        CompetingMdcBridge.ENABLED.set(true);
        try {
            MDC.put(TracingSupport.MDC_TRACE_ID, "outer-trace");
            MDC.put(TracingSupport.MDC_PROCESS_INSTANCE_ID, "outer-pi");

            try (TracingSupport.TraceScope scope = tracing.openChildSpan(
                    null, "test.work", "pi-123", null)) {
                assertThat(MDC.get(TracingSupport.MDC_TRACE_ID)).isEqualTo(scope.traceId());
                assertThat(MDC.get(TracingSupport.MDC_PROCESS_INSTANCE_ID)).isEqualTo("pi-123");
            }

            assertThat(MDC.get(TracingSupport.MDC_TRACE_ID)).isEqualTo("outer-trace");
            assertThat(MDC.get(TracingSupport.MDC_PROCESS_INSTANCE_ID)).isEqualTo("outer-pi");
        } finally {
            CompetingMdcBridge.ENABLED.set(false);
        }
    }

    /**
     * Тест-локальный двойник прод-бриджа. Ставится ОДИН раз на весь форк
     * (JVM-глобальный {@code ContextStorage.addWrapper} отмотать нельзя — как и
     * настоящий бридж), но вне теста — чистый passthrough (флаг выключен: ноль
     * касаний MDC, ноль влияния на остальные тесты форка).
     */
    static final class CompetingMdcBridge {
        static final AtomicBoolean ENABLED = new AtomicBoolean(false);

        static {
            ContextStorage.addWrapper(storage -> new ContextStorage() {
                @Override
                public Scope attach(Context toAttach) {
                    Scope delegate = storage.attach(toAttach);
                    if (ENABLED.get()) {
                        // Как Slf4JEventListener.onScopeAttached: безусловный put
                        // hex'а аттачащегося спана (включая невалидный — бридж
                        // isValid не проверяет, проверено байткодом).
                        MDC.put(TracingSupport.MDC_TRACE_ID,
                            Span.fromContext(toAttach).getSpanContext().getTraceId());
                    }
                    return () -> {
                        delegate.close();
                        if (ENABLED.get()) {
                            // Как onScopeClosed + onScopeRestored: remove, затем
                            // put восстановленного (тоже без isValid-гарда).
                            MDC.remove(TracingSupport.MDC_TRACE_ID);
                            MDC.put(TracingSupport.MDC_TRACE_ID,
                                Span.current().getSpanContext().getTraceId());
                        }
                    };
                }

                @Override
                public Context current() {
                    return storage.current();
                }
            });
        }
    }
}
