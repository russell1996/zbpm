package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
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
        runtimeService.completeServiceTask(serviceTaskId, variables);
    }
}
