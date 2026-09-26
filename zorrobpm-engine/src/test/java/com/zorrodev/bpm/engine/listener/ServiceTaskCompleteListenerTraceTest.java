package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * WO-OBS-8 (completion point): the engine-side completion listener continues the
 * worker-forwarded trace — the child span's trace id EQUALS the incoming
 * traceparent's trace id (not a fresh trace), and MDC carries PI for the
 * completion logs. Real SDK + in-memory exporter (never noop, see
 * {@code TracingSupportTest}).
 */
@ExtendWith(MockitoExtension.class)
class ServiceTaskCompleteListenerTraceTest {

    @Mock private RuntimeService runtimeService;

    private InMemorySpanExporter exporter;
    private ServiceTaskCompleteListener listener;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        TracingSupport tracing = new TracingSupport(OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .build());
        listener = new ServiceTaskCompleteListener(runtimeService, tracing);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        exporter.reset();
    }

    @Test
    void obs8_completion_continuesWorkerTrace_sameTraceId() {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(UUID.randomUUID());
        completed.setStatus("SUCCESS");
        completed.setTraceParent("00-" + traceId + "-b7ad6b7169203331-01");
        completed.setProcessInstanceId("pi-completion");

        listener.on(completed);

        verify(runtimeService).completeServiceTask(any(), any());
        assertThat(exporter.getFinishedSpanItems())
            .filteredOn(span -> span.getName().equals("completion.process"))
            .hasSize(1)
            .allSatisfy(span -> assertThat(span.getSpanContext().getTraceId()).isEqualTo(traceId));
    }

    @Test
    void obs8_completion_failedStatus_stillTraced() {
        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(UUID.randomUUID());
        completed.setStatus("FAILED");
        completed.setErrorMessage("boom");
        completed.setTraceParent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        completed.setProcessInstanceId("pi-fail");

        listener.on(completed);

        verify(runtimeService).failServiceTask(any(), any(), any());
        assertThat(exporter.getFinishedSpanItems())
            .filteredOn(span -> span.getName().equals("completion.process"))
            .hasSize(1);
    }

    /**
     * WO-QW-5 (NEW2-15): FEEL-перегрузка внутри complete — не бизнес-ошибка:
     * исключение пробрасывается наружу БЕЗ обёртки (контейнерный retry
     * переиграет сообщение; транзакция откатывается штатно), а не глотается
     * и не превращается в FAILED-completion. POF-мутация: убрать catch —
     * поведение то же (проброс), но без info-строки; мутация «завернуть в
     * EngineException» — этот тест КРАСНЫЙ (isSameAs падает).
     */
    @Test
    void qw5_completion_overload_propagatesUnwrapped_noFailCall() {
        com.zorrodev.bpm.engine.service.ScriptOverloadException overload =
            new com.zorrodev.bpm.engine.service.ScriptOverloadException("pool overloaded (test)", 5);
        org.mockito.Mockito.doThrow(overload).when(runtimeService)
            .completeServiceTask(any(), any());
        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(UUID.randomUUID());
        completed.setStatus("SUCCESS");
        completed.setTraceParent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        completed.setProcessInstanceId("pi-overload");

        assertThatThrownBy(() -> listener.on(completed))
            .as("overload обязана выйти наружу без обёртки — контейнер retry её переиграет")
            .isSameAs(overload);
        org.mockito.Mockito.verify(runtimeService, org.mockito.Mockito.never())
            .failServiceTask(any(), any(), any());
    }
}
