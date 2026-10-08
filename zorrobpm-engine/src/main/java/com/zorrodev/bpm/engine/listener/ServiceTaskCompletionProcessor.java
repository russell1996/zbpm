package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * WO-ENG-35 (NEW2-16): транзакционная половина AMQP-completion'а.
 *
 * <p>Выделено из {@code ServiceTaskCompleteListener} по прецеденту
 * {@code TimerScheduler}/{@code TimerBatchProcessor} (P-18: self-invocation
 * через прокси не открывает транзакцию): listener ждёт admission-гейт ВНЕ
 * транзакции, а этот бин выполняет тело ВНУТРИ неё. Граница та же, что была
 * (весь {@code process}), двигалось только ожидание — семантика отката
 * (транзакция целиком при перегрузке → контейнерный retry) сохранена.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceTaskCompletionProcessor {

    private final RuntimeService runtimeService;

    @Transactional
    public void process(ServiceTaskCompleted serviceTaskCompleted) {
        UUID serviceTaskId = serviceTaskCompleted.getServiceTaskId();

        // a worker reports failure via status="FAILED" -> retries/incident, otherwise it completes the task
        // WO-C8-36 (CR-01): идентификатор вызова — в перегрузки (null = legacy без проверки).
        if ("FAILED".equalsIgnoreCase(serviceTaskCompleted.getStatus())) {
            runtimeService.failServiceTask(serviceTaskId, serviceTaskCompleted.getErrorMessage(), null,
                serviceTaskCompleted.getDispatchPhase(), serviceTaskCompleted.getDispatchIndex(),
                serviceTaskCompleted.getCompletionId());
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
        // контейнерный retry (2/4/8/16с, exponential) переиграет то же
        // сообщение позже. Стабильная перегрузка за 5 попыток всё равно уйдёт
        // в DLQ по общей политике — это уже не «временная», а sustained, и
        // отдельный redrive-механизм для неё — вне scope этого WO (см. отчёт).
        // P-46: guard узкий — только ScriptOverloadException; любой другой
        // RuntimeException идёт прежним путём (транзакция откатывается,
        // контейнер решает по общей политике, без новой семантики здесь).
        try {
            runtimeService.completeServiceTask(serviceTaskId, variables,
                serviceTaskCompleted.getDispatchPhase(), serviceTaskCompleted.getDispatchIndex());
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException overloaded) {
            log.info("Service task {} completion deferred: FEEL pool overloaded, "
                + "container retry will redeliver (retry-after ~{}s)",
                serviceTaskId, overloaded.getRetryAfterSeconds());
            throw overloaded;
        }
    }
}
