package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ElementListenerPhaseRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.service.AdmissionLease;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
import com.zorrodev.bpm.engine.trust.CompletionTrustMonitor;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.util.Optional;
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
    @Mock private ScriptService scriptService;

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
        // WO-ENG-35: гейт admission — мок с no-op лизой (место «есть сразу»);
        // транзакционное тело — настоящий процессор на мок-RuntimeService.
        // WO-AUDIT-18: монитор доверия — настоящий (на мок-репозиториях): он не
        // меняет исход, но обязан быть вызван на живом пути приёма completion'ов.
        org.mockito.Mockito.when(scriptService.admitOutsideTx())
            .thenReturn(AdmissionLease.noop());
        listener = new ServiceTaskCompleteListener(tracing, scriptService,
            new ServiceTaskCompletionProcessor(runtimeService, trustMonitor(), new BpmMetrics(new SimpleMeterRegistry())));
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        exporter.reset();
    }

    /**
     * WO-AUDIT-18: настоящий монитор доверия на мок-репозиториях (в этом тесте
     * интересует трассировка, а не модель доверия — её проверяет
     * {@code CompletionTrustMonitorIntegrationTests}). Моки Mockito по умолчанию
     * отдают {@code Optional.empty()} / {@code false} — этого достаточно, чтобы
     * монитор отработал ветку «id неизвестен» и НЕ менял исход completion'а.
     */
    private static CompletionTrustMonitor trustMonitor() {
        ActivityRepository activities = org.mockito.Mockito.mock(ActivityRepository.class);
        ServiceTaskRepository serviceTasks = org.mockito.Mockito.mock(ServiceTaskRepository.class);
        ElementListenerPhaseRepository phases = org.mockito.Mockito.mock(ElementListenerPhaseRepository.class);
        return new CompletionTrustMonitor(activities, serviceTasks, phases,
            new BpmMetrics(new SimpleMeterRegistry()));
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

        // WO-C8-36: listener пробрасывает фазу из сообщения (здесь null — legacy).
        verify(runtimeService).completeServiceTask(any(), any(), any(), any());
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

        // WO-C8-36: 6-арная перегрузка (serviceTaskId, errorMessage, retries,
        // dispatchPhase, dispatchIndex, completionId) — именно её зовёт listener
        // на FAILED. Пять матчеров проверяли 5-арную и падали бы на mismatch
        // арности (поймано полным clean verify, не точечным прогоном).
        verify(runtimeService).failServiceTask(any(), any(), any(), any(), any(), any());
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
            .completeServiceTask(any(), any(), any(), any());
        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(UUID.randomUUID());
        completed.setStatus("SUCCESS");
        completed.setTraceParent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        completed.setProcessInstanceId("pi-overload");

        assertThatThrownBy(() -> listener.on(completed))
            .as("overload обязана выйти наружу без обёртки — контейнер retry её переиграет")
            .isSameAs(overload);
        org.mockito.Mockito.verify(runtimeService, org.mockito.Mockito.never())
            .failServiceTask(any(), any(), any(), any(), any());
    }
}
