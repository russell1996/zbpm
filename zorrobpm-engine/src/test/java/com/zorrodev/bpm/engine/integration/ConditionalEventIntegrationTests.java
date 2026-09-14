package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ConditionalEventIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private com.zorrodev.bpm.engine.handler.ExecutionContext executionContext;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private ScriptService scriptService;

    /**
     * WO-C8-29: hermeticity переменной-трекера. Записи ThreadLocal переживают
     * rollback транзакций и общий тред surefire — без сброса чужой linger
     * (например, `approved` из соседнего теста) попал бы в consume моего триггера
     * и дал бы ложную оценку (только лишнюю, но POF на `never()` требует тишины).
     */
    @org.junit.jupiter.api.BeforeEach
    void discardPendingVariableChanges() {
        executionContext.consumeVariableChanges();
    }

    private ProcessVariable bool(String name, boolean value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.BOOLEAN);
        v.setValue(Boolean.toString(value));
        return v;
    }

    private ProcessVariable str(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private List<ActivityEntity> activitiesOf(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
    }

    private ActivityEntity active(UUID processInstanceId, String bpmnElementId) {
        return activitiesOf(processInstanceId).stream()
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow();
    }

    @Transactional
    @Test
    void conditionalCatchFiresWhenAVariableChangeMakesItTrue() throws Exception {
        // parallel split: branch A's user task sets approved=true; branch B parks on a conditional catch
        // (approved = true). Completing the user task re-evaluates the condition, fires the catch, and the
        // join completes.
        String bpmn = Files.readString(Paths.get("src/test/files/test-conditional-catch.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        // branch B is parked on the conditional catch (condition not yet true)
        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // complete branch A's user task with approved=true -> the conditional catch fires
        ActivityEntity setApproved = active(processInstanceId, "setApproved");
        runtimeService.completeUserTask(setApproved.getId(), List.of(bool("approved", true)));

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("condCatch") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("join") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void conditionalCatchPassesThroughWhenConditionAlreadyTrueOnEntry() throws Exception {
        // started with approved=true on a linear process, the conditional catch is satisfied the moment the
        // token reaches it, so it passes straight through and the instance completes without any external
        // trigger.
        String bpmn = Files.readString(Paths.get("src/test/files/test-conditional-catch-entry.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(bool("approved", true)));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("condCatch") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void conditionalBoundaryFiresInterruptingWhenItsConditionBecomesTrue() throws Exception {        // host "work" carries an interrupting conditional boundary (abort = true). A parallel branch's user
        // task sets abort=true; completing it re-evaluates the boundary, which interrupts the host and routes
        // flow to the boundary's end.
        String bpmn = Files.readString(Paths.get("src/test/files/test-conditional-boundary.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        // both hosts are parked: the work task (with the conditional boundary) and the trigger task
        assertThat(active(processInstanceId, "work").getStatus()).isEqualTo(ActivityStatus.CREATED);
        ActivityEntity trigger = active(processInstanceId, "trigger");

        runtimeService.completeUserTask(trigger.getId(), List.of(bool("abort", true)));

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        // the host was interrupted and flow continued from the boundary
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("work") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("boundaryEnd") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    // ==================== WO-C8-29: zeebe:conditionalFilter ====================

    private UUID startWithVariables(String bpmnFile, List<ProcessVariable> variables) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(variables);
        return runtimeService.startProcessInstance(dto).getId();
    }

    @Transactional
    @Test
    void conditionalFilterSkipsReevaluationOnIrrelevantChange() throws Exception {
        // WO-C8-29, критерий 3 (POF): фильтр variableNames="approved" — создание
        // чужой переменной НЕ запускает переоценку (вид изменения подходит, имя —
        // нет). Наблюдаемо напрямую: evaluateScript с условием не вызывается вообще
        // (поведенчески отличить нельзя — условие ложно в обоих мирах, поэтому шпион).
        UUID processInstanceId = startWithVariables("test-conditional-filter.bpmn", List.of());
        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        clearInvocations(scriptService);

        ActivityEntity setFlag = active(processInstanceId, "setFlag");
        runtimeService.completeUserTask(setFlag.getId(), List.of(str("note", "hello")));

        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        verify(scriptService, never()).evaluateScript(eq("approved = true"), any());
    }

    @Transactional
    @Test
    void conditionalFilterEvaluatesAndFiresOnRelevantCreate() throws Exception {
        // WO-C8-29, критерий 4: создание отфильтрованной переменной — переоценка
        // идёт, условие истинно, событие срабатывает (инстанс завершается).
        UUID processInstanceId = startWithVariables("test-conditional-filter.bpmn", List.of());
        clearInvocations(scriptService);

        ActivityEntity setFlag = active(processInstanceId, "setFlag");
        runtimeService.completeUserTask(setFlag.getId(), List.of(bool("approved", true)));

        verify(scriptService, atLeastOnce()).evaluateScript(eq("approved = true"), any());
        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();
        assertThat(activitiesOf(processInstanceId))
            .anyMatch(a -> a.getBpmnElementId().equals("condCatch") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void conditionalFilterEvaluatesOnUpdateOfNamedVariable() throws Exception {
        // WO-C8-29, критерий 6 (значение update): approved существует заранее —
        // запись идёт как update, фильтр пропускает, событие срабатывает.
        UUID processInstanceId = startWithVariables("test-conditional-filter.bpmn",
            List.of(bool("approved", false)));
        clearInvocations(scriptService);

        ActivityEntity setFlag = active(processInstanceId, "setFlag");
        runtimeService.completeUserTask(setFlag.getId(), List.of(bool("approved", true)));

        verify(scriptService, atLeastOnce()).evaluateScript(eq("approved = true"), any());
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
    }

    @Transactional
    @Test
    void conditionalFilterCreateOnlySkipsUpdate() throws Exception {
        // WO-C8-29, критерий 6 (значение create): фильтр variableEvents="create" —
        // update отфильтрованной переменной переоценку НЕ запускает.
        UUID processInstanceId = startWithVariables("test-conditional-filter-create-only.bpmn",
            List.of(bool("approved", false)));
        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        clearInvocations(scriptService);

        ActivityEntity setFlag = active(processInstanceId, "setFlag");
        runtimeService.completeUserTask(setFlag.getId(), List.of(bool("approved", true)));

        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        verify(scriptService, never()).evaluateScript(eq("approved = true"), any());
    }

    @Transactional
    @Test
    void conditionalFilterUpdateOnlySkipsCreate() throws Exception {
        // WO-C8-29, критерий 6 (значение update): фильтр variableEvents="update" —
        // create отфильтрованной переменной переоценку НЕ запускает.
        UUID processInstanceId = startWithVariables("test-conditional-filter-update-only.bpmn", List.of());
        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        clearInvocations(scriptService);

        ActivityEntity setFlag = active(processInstanceId, "setFlag");
        runtimeService.completeUserTask(setFlag.getId(), List.of(bool("approved", true)));

        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        verify(scriptService, never()).evaluateScript(eq("approved = true"), any());
    }

    @Transactional
    @Test
    void conditionalFilterUpdateOnlyFiresOnUpdate() throws Exception {
        // WO-C8-29, критерий 6: тот же update-only фильтр — update срабатывает штатно.
        UUID processInstanceId = startWithVariables("test-conditional-filter-update-only.bpmn",
            List.of(bool("approved", false)));
        clearInvocations(scriptService);

        ActivityEntity setFlag = active(processInstanceId, "setFlag");
        runtimeService.completeUserTask(setFlag.getId(), List.of(bool("approved", true)));

        verify(scriptService, atLeastOnce()).evaluateScript(eq("approved = true"), any());
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
    }

    // ==================== WO-REL-31 CR-2: double-write in startProcessInstanceAt POF ====================

    @Transactional
    @Test
    void initialStartVarsDoNotSpuriouslyTriggerUpdateFilterOnUnrelatedCompletion() throws Exception {
        // WO-REL-31, criterion 2 (POF): the redundant second variable write
        // (find-then-update) in EventTrigger.startProcessInstanceAt left a stale
        // {approved:"update"} change record in the thread-local map after start.
        // An unrelated later completion (note=hello) then matched the update-only filter
        // on that stale record and spuriously re-evaluated the condition — observeably:
        // evaluateScript ran although the completion touched only note.
        //
        // With the duplicate write removed, the start records nothing (INSERT only),
        // the map carries only {note:"create"} at completion, and the update-only
        // filter misses — no evaluation, no spurious conditional trigger.
        UUID processInstanceId = startWithVariables("test-conditional-filter-update-only.bpmn",
            List.of(bool("approved", false)));
        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        clearInvocations(scriptService);

        ActivityEntity setFlag = active(processInstanceId, "setFlag");
        runtimeService.completeUserTask(setFlag.getId(), List.of(str("note", "hello")));

        assertThat(active(processInstanceId, "condCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        verify(scriptService, never()).evaluateScript(eq("approved = true"), any());
    }

    @Transactional
    @Test
    void conditionalFilterSkipsBoundaryOnIrrelevantChange() throws Exception {
        // WO-C8-29: вторая точка консультации (boundary-ветка triggerConditionalEvents)
        // идёт тем же хелпером — чужое изменение не переоценивает и boundary.
        UUID processInstanceId = startWithVariables("test-conditional-filter-boundary.bpmn", List.of());
        assertThat(active(processInstanceId, "work").getStatus()).isEqualTo(ActivityStatus.CREATED);
        clearInvocations(scriptService);

        ActivityEntity trigger = active(processInstanceId, "trigger");
        runtimeService.completeUserTask(trigger.getId(), List.of(str("note", "hello")));

        assertThat(active(processInstanceId, "work").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(activitiesOf(processInstanceId)).noneMatch(a -> a.getBpmnElementId().equals("boundaryEnd"));
        verify(scriptService, never()).evaluateScript(eq("abort = true"), any());
    }

    @Transactional
    @Test
    void conditionalFilterFiresBoundaryOnRelevantChange() throws Exception {
        // WO-C8-29: релевантное изменение — boundary оценивается и прерывает хост.
        UUID processInstanceId = startWithVariables("test-conditional-filter-boundary.bpmn", List.of());
        clearInvocations(scriptService);

        ActivityEntity trigger = active(processInstanceId, "trigger");
        runtimeService.completeUserTask(trigger.getId(), List.of(bool("abort", true)));

        verify(scriptService, atLeastOnce()).evaluateScript(eq("abort = true"), any());
        List<ActivityEntity> activities = activitiesOf(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("work") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("boundaryEnd") && a.getStatus() == ActivityStatus.COMPLETED);
    }
}
