package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceTaskCompleteListener {

    private final RuntimeService runtimeService;
    private final TracingSupport tracing;

    @Transactional
    @EventListener
    public void on(ServiceTaskCompleted serviceTaskCompleted) {
        // WO-OBS-8: continue the worker-forwarded trace (same thread as the RabbitMQ
        // completion listener, which set MDC but has no SDK — the span is opened here).
        // Lookup-free PI: the worker forwarded it, no DB read just for logging.
        try (TracingSupport.TraceScope ignored = tracing.openChildSpan(
                serviceTaskCompleted.getTraceParent(), "completion.process",
                serviceTaskCompleted.getProcessInstanceId(),
                TracingSupport.attrs("serviceTask.id", String.valueOf(serviceTaskCompleted.getServiceTaskId()),
                    "completion.status", String.valueOf(serviceTaskCompleted.getStatus())))) {
            process(serviceTaskCompleted);
        }
    }

    private void process(ServiceTaskCompleted serviceTaskCompleted) {
        UUID serviceTaskId = serviceTaskCompleted.getServiceTaskId();

        // a worker reports failure via status="FAILED" -> retries/incident, otherwise it completes the task
        if ("FAILED".equalsIgnoreCase(serviceTaskCompleted.getStatus())) {
            runtimeService.failServiceTask(serviceTaskId, serviceTaskCompleted.getErrorMessage(), null);
            return;
        }

        List<ProcessVariable> variables = serviceTaskCompleted.getVariables() == null ? List.of()
            : serviceTaskCompleted.getVariables().stream()
                .map(v -> {
                    ProcessVariable pv = new ProcessVariable();
                    pv.setName(v.getName());
                    pv.setValue(v.getValue());
                    pv.setType(ProcessVariableType.valueOf(v.getType()));
                    return pv;
                })
                .toList();
        // WO-QW-5 (NEW2-15): временная FEEL-перегрузка (ScriptOverloadException
        // из io-mapping/condition-eval внутри complete) — НЕ бизнес-ошибка и
        // НЕ повод в DLQ: исключение пробрасывается наружу без обёртки, и
        // контейнерный retry (2/4/8/16с, exponential) переигрывает то же
        // сообщение позже. Стабильная перегрузка за 5 попыток всё равно уйдёт
        // в DLQ по общей политике — это уже не «временная», а sustained, и
        // отдельный redrive-механизм для неё — вне scope этого WO (см. отчёт).
        // P-46: guard узкий — только ScriptOverloadException; любой другой
        // RuntimeException идёт прежним путём (транзакция откатывается,
        // контейнер решает по общей политике, без новой семантики здесь).
        try {
            runtimeService.completeServiceTask(serviceTaskId, variables);
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException overloaded) {
            log.info("Service task {} completion deferred: FEEL pool overloaded, "
                + "container retry will redeliver (retry-after ~{}s)",
                serviceTaskId, overloaded.getRetryAfterSeconds());
            throw overloaded;
        }
    }
}
