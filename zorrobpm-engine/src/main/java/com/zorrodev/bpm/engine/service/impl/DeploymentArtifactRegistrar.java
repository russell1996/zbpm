package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * WO-DEBT-5b — deployment artifact registration cluster extracted byte-for-byte from
 * {@code ProcessDefinitionServiceImpl} (start subscriptions, timer jobs, binding
 * carry-forward). Add-only foundation: the original still calls its own copies;
 * delegation happens in Phase 5c. Visibility private → public where stated, bodies
 * and javadoc otherwise verbatim.
 */
@Component
@RequiredArgsConstructor
public class DeploymentArtifactRegistrar {

    private final DBService dbService;
    private final ElementSupport elementSupport;
    private final ElementArtifactBindingRepository bindingRepository;
    private final FormRepository formRepository;

    /**
     * Registers (and supersedes prior versions of) message start subscriptions for the deployed
     * definition, so a correlated message of that name starts a new instance of the latest version.
     */
    public void registerMessageStartSubscriptions(String key, UUID processDefinitionId, BpmnProcessDefinitionModel model) {
        var messageStarts = model.getMessageStartEvents();
        if (messageStarts.isEmpty()) {
            return;
        }
        dbService.deleteMessageStartSubscriptionsByKey(key);
        for (BpmnElementModel start : messageStarts) {
            String messageName = Optional.ofNullable(start.getExtensions())
                .map(BpmnElementExtensionModel::getMessageEventExtension)
                .map(com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel::getMessageName)
                .orElse(null);
            if (messageName != null) {
                dbService.createMessageStartSubscription(key, processDefinitionId, start.getId(), messageName);
            }
        }
    }

    /**
     * Registers (and supersedes prior versions of) signal start subscriptions for the deployed
     * definition, so a broadcast signal of that name starts a new instance of the latest version.
     */
    public void registerSignalStartSubscriptions(String key, UUID processDefinitionId, BpmnProcessDefinitionModel model) {
        var signalStarts = model.getSignalStartEvents();
        if (signalStarts.isEmpty()) {
            return;
        }
        dbService.deleteSignalStartSubscriptionsByKey(key);
        for (BpmnElementModel start : signalStarts) {
            String signalName = Optional.ofNullable(start.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel::getName)
                .orElse(null);
            if (signalName != null) {
                dbService.createSignalStartSubscription(key, processDefinitionId, start.getId(), signalName);
            }
        }
    }

    /**
     * Registers (and supersedes prior versions of) timer start jobs for the deployed definition.
     * Duration timers are due relative to deploy time; date timers at the given instant.
     */
    public void registerTimerStartJobs(String key, UUID processDefinitionId, BpmnProcessDefinitionModel model) {
        var timerStarts = model.getTimerStartEvents();
        if (timerStarts.isEmpty()) {
            return;
        }
        dbService.deleteTimerStartJobsByKey(key);
        for (BpmnElementModel start : timerStarts) {
            com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel timer = Optional.ofNullable(start.getExtensions())
                .map(BpmnElementExtensionModel::getTimerEventExtension)
                .orElse(null);
            if (timer == null || timer.getType() == null || timer.getExpression() == null) {
                continue;
            }
            // WO-ENG-4: delegate to ElementSupport which uses businessZone (not ZoneId.systemDefault())
            // for CYCLE, and provides a single source of truth for all timer literal parsing.
            // This also eliminates the code-clone switch that duplicated ElementSupport.computeDueAtFallback.
            Instant dueAt = elementSupport.computeDueAt(timer, start.getId());
            // WO-REL-14 (R-04, defect 2): persist the initial remaining-repetitions count for a
            // bounded cycle (R<n>/...) so TimerStartJobExecutor can decrement the PERSISTED value
            // on each fire instead of recomputing repeatCount from the BPMN model every time
            // (which meant a bounded cycle never actually exhausted). Null = infinite/non-cycle.
            Integer remainingCount = timer.getType() == com.zorrodev.bpm.engine.bpmn.model.TimerEventType.CYCLE
                ? initialRemainingCount(timer.getExpression())
                : null;
            dbService.createTimerStartJob(key, processDefinitionId, start.getId(), dueAt, remainingCount);
        }
    }

    /** WO-REL-14: repeatCount - 1, or null for unbounded (R/...) / cron cycles. */
    private Integer initialRemainingCount(String cycleExpression) {
        int repeatCount = com.zorrodev.bpm.engine.scheduler.TimerExpressions.repeatCount(cycleExpression);
        return repeatCount > 0 ? repeatCount - 1 : null;
    }

    /**
     * WO-C8-12: stores embedded {@code zeebe:userTaskForm} JSON bodies of the deployed definition
     * in the shared forms table (same mechanism as manual REST upload — unconditional new version
     * per deploy, consistent with {@code carryForwardBindings}, no dedup).
     *
     * @param deploymentId WO-C8-23: batch id stamped on the rows ({@code POST /deployments});
     *                     null on single deploys keeps behaviour byte-identical (WO-C8-18 pattern).
     */
    public void registerUserTaskForms(UUID processDefinitionId, BpmnProcessDefinitionModel model, UUID deploymentId) {
        var forms = model.getUserTaskForms();
        if (forms == null || forms.isEmpty()) {
            return;
        }
        for (var form : forms) {
            if (form.getId() == null || form.getBody() == null) {
                continue;
            }
            String formKey = "camunda-forms:bpmn:userTaskForm_" + form.getId();
            int version = formRepository.findMaxVersionByFormKey(formKey) + 1;
            FormEntity entity = new FormEntity();
            entity.setId(UUID.randomUUID());
            entity.setFormKey(formKey);
            // WO-C8-22: embedded forms carry their Modeler id — addressable by formId too.
            entity.setFormId(form.getId());
            // WO-C8-31: top-level "versionTag" of the embedded .form JSON (WO-C8-27;
            // lenient — absent on most forms → null, never matches a versionTag resolve).
            entity.setVersionTag(FormEntity.extractVersionTag(form.getBody()));
            // WO-C8-23: batch stamp for bindingType="deployment" resolve (null = single deploy).
            entity.setDeploymentId(deploymentId);
            entity.setVersion(version);
            entity.setSchemaJson(form.getBody());
            entity.setKind(FormArtifactKind.FORM_JS);
            entity.setCreatedAt(Instant.now());
            formRepository.save(entity);
        }
    }

    /**
     * WO-VM-9a: Carry-forward element_artifact_bindings from previous PD version to new version.
     * Copies bindings by elementId and re-pins artifact_version to current artifact version.
     */
    public void carryForwardBindings(String key, int oldVersion, ProcessDefinitionEntity newPd) {
        var oldBindings = bindingRepository.findByKeyAndOldVersion(key, oldVersion);
        for (var oldBinding : oldBindings) {
            // Find current artifact version
            formRepository.findTopByFormKeyOrderByVersionDesc(oldBinding.getArtifactKey()).ifPresent(currentArtifact -> {
                var newBinding = new com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity();
                newBinding.setId(java.util.UUID.randomUUID());
                newBinding.setProcessDefinitionId(newPd.getId());
                newBinding.setProcessDefinitionVersion(newPd.getVersion());
                newBinding.setElementId(oldBinding.getElementId());
                newBinding.setArtifactKey(oldBinding.getArtifactKey());
                newBinding.setArtifactVersion(currentArtifact.getVersion());
                newBinding.setCreatedAt(java.time.Instant.now());
                bindingRepository.save(newBinding);
            });
        }
    }
}
