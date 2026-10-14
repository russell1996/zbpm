package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * WO-C8-46: model-aware половина единой зачистки скоуп-состояния при
 * операторской отмене инстанса.
 *
 * <p>Model-free половина (снос ВСЕХ скоуп-строк по отменённым/снятым activity —
 * тот же {@code deleteVariables}, что хвосты нормального завершения) живёт в
 * {@code VariableDbOperations.deleteScopedVariablesByActivityIds}. Эта половина
 * добивает то, что в скоупах НЕ лежит, но принадлежит отменённому MI-телу:
 * предсозданный outputCollection и batch-маркер входа (оба — в containing
 * scope тела, см. {@code ElementSupport.deleteMiBodyState}, WO-C8-41 F5).
 *
 * <p>Границы вызывающего: только живые MI-тела этого инстанса
 * ({@code cancelActiveActivities} уже перевёл их в CANCELLED — читаем строки
 * терминального статуса по elementId из модели); root-переменные, кроме
 * outputCollection отменённого тела, не трогаем; историю не пишем
 * (lifecycle-мусор, как {@code deleteVariables}).
 */
@Slf4j
@Service
public class CancelledScopeStateCleaner {

    private final ProcessInstanceRepository processInstanceRepository;
    private final ActivityRepository activityRepository;
    private final VariableDbOperations variableDbOperations;
    private final BpmnService bpmnService;

    public CancelledScopeStateCleaner(ProcessInstanceRepository processInstanceRepository,
            ActivityRepository activityRepository,
            VariableDbOperations variableDbOperations,
            @Lazy BpmnService bpmnService) {
        this.processInstanceRepository = processInstanceRepository;
        this.activityRepository = activityRepository;
        this.variableDbOperations = variableDbOperations;
        this.bpmnService = bpmnService;
    }

    /**
     * Единая зачистка скоуп-состояния отменённого инстанса, две половины одной
     * транзакции (порядок — model-free первой: она не зависит от BPMN-модели
     * и обязана сработать, даже если модель недоступна):
     * <ol>
     *   <li>model-free: снос ВСЕХ строк скоупов, привязанных к activity-строкам
     *       инстанса ({@code deleteScopedVariablesByActivityIds} — тот же
     *       DELETE, что хвосты завершения: item/loopCounter MI-инстансов,
     *       ioMapping-входы);</li>
     *   <li>model-aware: снос body-state отменённых MI-тел из containing scope
     *       ({@code deleteMiBodyState}-семантика: предсозданный outputCollection
     *       + batch-маркер входа).</li>
     * </ol>
     */
    public void clear(UUID processInstanceId) {
        variableDbOperations.deleteAllScopedVariables(processInstanceId);
        clearMiBodyState(processInstanceId);
    }

    /**
     * Model-aware половина: body-state отменённых MI-тел из containing scope.
     */
    private void clearMiBodyState(UUID processInstanceId) {
        ProcessInstanceEntity pi = processInstanceRepository.findById(processInstanceId).orElseThrow();
        BpmnProcessDefinitionModel bpmn;
        try {
            bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
        } catch (RuntimeException e) {
            log.warn("{}: cancelled-scope cleanup skipped — BPMN model unavailable: {}",
                processInstanceId, e.getMessage());
            return;
        }
        List<BpmnElementModel> miElements = bpmn.getElements().stream()
            .filter(el -> el.getExtensions() != null
                && el.getExtensions().getMultiInstanceExtension() != null
                && (el.getType() == BpmnElementType.SUB_PROCESS
                    || el.getType() == BpmnElementType.USER_TASK
                    || el.getType() == BpmnElementType.SERVICE_TASK))
            .toList();
        if (miElements.isEmpty()) {
            return;
        }
        for (BpmnElementModel miElement : miElements) {
            List<UUID> cancelledRows = activityRepository
                .findByProcessInstanceIdAndBpmnElementIdAndCancelled(processInstanceId, miElement.getId());
            if (cancelledRows.isEmpty()) {
                continue;
            }
            MultiInstanceExtensionModel mi = miElement.getExtensions().getMultiInstanceExtension();
            for (UUID rowId : cancelledRows) {
                UUID containingScope = variableDbOperations.findScopeOfActivity(processInstanceId, rowId);
                if (mi.getOutputCollection() != null && !mi.getOutputCollection().isBlank()) {
                    variableDbOperations.deleteVariable(processInstanceId, containingScope,
                        mi.getOutputCollection());
                }
                variableDbOperations.deleteVariable(processInstanceId, containingScope,
                    "_mi_batch_" + miElement.getId());
                log.info("{}/{}: MI body {} cancelled by operator — dropped its output state in scope {}",
                    processInstanceId, rowId, miElement.getId(),
                    containingScope == null ? "root" : containingScope);
            }
        }
    }
}
