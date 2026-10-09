package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.engine.service.AdmissionLease;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceTaskCompleteListener {

    private final TracingSupport tracing;
    /**
     * WO-ENG-35 (NEW2-16): admission-гейт script-пула. Метод намеренно БЕЗ
     * {@code @Transactional} (транзакция — в {@link ServiceTaskCompletionProcessor}):
     * sustained-перегрузка пула даёт тот же контейнерный retry, но ожидание
     * окна не удерживает соединение из Hikari-пула.
     */
    private final ScriptService scriptService;
    private final ServiceTaskCompletionProcessor completionProcessor;

    @EventListener
    public void on(ServiceTaskCompleted serviceTaskCompleted) {
        // WO-OBS-8: continue the worker-forwarded trace (same thread as the RabbitMQ
        // completion listener, which set MDC but has no SDK — the span is opened here).
        // Lookup-free PI: the worker forwarded it, no DB read just for logging.
        try (TracingSupport.TraceScope ignored = tracing.openChildSpan(
                serviceTaskCompleted.getTraceParent(), "completion.process",
                serviceTaskCompleted.getProcessInstanceId(),
                TracingSupport.attrs("serviceTask.id", String.valueOf(serviceTaskCompleted.getServiceTaskId()),
                    "completion.status", String.valueOf(serviceTaskCompleted.getStatus())));
             // WO-ENG-35: место в script-пуле — до транзакции (см. поле).
             AdmissionLease ignoredLease = scriptService.admitOutsideTx()) {
            completionProcessor.process(serviceTaskCompleted);
        }
    }
}
