package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-29: FEEL output/input-маппинг, чей source ссылается на переменную,
 * которой нет в контексте (воркер не вернул её в результате джобы), поднимает
 * инцидент через существующий {@code IncidentService}-механизм — а не тихо
 * превращается в {@code ""}.
 *
 * <p>Все тесты идут реальным прод-путём ({@code RuntimeService} →
 * {@code CompletionService.finishServiceTaskCompletion} /
 * {@code ActivityServiceImpl.execute} → {@code ElementSupport.evaluateMapping} —
 * G-N), на тех же BPMN-фикстурах, что описывают живой репорт
 * ({@code srvStageCreate} → {@code routeStageUuid} → {@code srvActivityCreate}).
 *
 * <p>POF (критерий 7): откат {@code evaluateMapping} на JSR-223
 * {@code scriptService.evaluateExpression} → criterion1 КРАСНЫЙ (инцидента нет,
 * тихая {@code ""}); возврат нативного API → GREEN. Дословные выводы — в отчёте.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Eng29FeelNullIoMappingIncidentIT {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private VariableRepository variableRepository;

    private static ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    private UUID start(String bpmnFile, List<ProcessVariable> variables) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(variables);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ActivityEntity activeServiceTask(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId)
                && (a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no active activity " + bpmnElementId));
    }

    private List<IncidentEntity> incidentsOf(UUID processInstanceId) {
        return incidentRepository.findAll().stream()
            .filter(i -> {
                ActivityEntity a = activityRepository.findById(i.getActivityId()).orElse(null);
                return a != null && a.getProcessInstanceId().equals(processInstanceId);
            })
            .toList();
    }

    /** Root-scope names → values (WO-ENG-14 read semantic). */
    private Map<String, String> rootVars(UUID processInstanceId) {
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId).stream()
            .collect(Collectors.toMap(ProcessVariableEntity::getName, ProcessVariableEntity::getTextValue,
                (a, b) -> b));
    }

    // ─── Критерий 1: output-маппинг на отсутствующую переменную → инцидент ───

    @Transactional
    @Test
    void criterion1_outputMappingOnMissingJobVariable_raisesIncidentNotSilentEmpty() {
        UUID pi;
        try {
            pi = start("test-eng29-stage-activity.bpmn", List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // Живой репорт: srvStageCreate завершён, воркер НЕ вернул routeStageUuid.
        UUID stage = activeServiceTask(pi, "srvStageCreate").getId();
        runtimeService.completeServiceTask(stage, List.of());

        List<IncidentEntity> incidents = incidentsOf(pi);
        assertThat(incidents)
            .as("output mapping '=routeStageUuid' on a missing job variable must raise an incident")
            .hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .as("incident message must carry the distinguishable FEEL-evaluation failure")
            .contains("FeelEvaluationException")
            .contains("routeStageUuid");

        assertThat(rootVars(pi))
            .as("the missing variable must NOT silently become '' at root scope")
            .doesNotContainKey("routeStageUuid");

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("instance stays parked on the incident, not completed")
            .isNull();
    }

    // ─── Критерий 2: input-маппинг на отсутствующую переменную → инцидент ────

    @Transactional
    @Test
    void criterion2_inputMappingOnMissingVariable_raisesIncidentViaExistingMechanism() {
        UUID pi;
        try {
            pi = start("test-eng29-missing-input.bpmn", List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // Input-маппинг вычисляется при входе — инстанс паркуется сразу на старте.
        List<IncidentEntity> incidents = incidentsOf(pi);
        assertThat(incidents)
            .as("input mapping '=neverProvidedVar' on activation must raise an incident")
            .hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .contains("FeelEvaluationException")
            .contains("neverProvidedVar");

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
    }

    // ─── Критерий 3: легитимный null — как раньше, без инцидента ─────────────

    @Transactional
    @Test
    void criterion3_explicitNullLiteral_keepsLegacyBehaviourOnBothPaths() {
        UUID pi;
        try {
            pi = start("test-eng29-honest-null.bpmn", List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // Input =null при входе — без инцидента, задача создана и активна.
        assertThat(incidentsOf(pi))
            .as("explicit =null input must not raise an incident")
            .isEmpty();
        UUID svc = activeServiceTask(pi, "svcNull").getId();

        // Output =null при завершении — без инцидента, процесс завершается.
        runtimeService.completeServiceTask(svc, List.of());
        assertThat(incidentsOf(pi))
            .as("explicit =null output must not raise an incident")
            .isEmpty();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("process with legitimate null mappings completes normally")
            .isNotNull();

        assertThat(rootVars(pi).get("nullEcho"))
            .as("explicit =null output keeps the pre-fix '' STRING behaviour")
            .isEqualTo("");
    }

    // ─── Счастливый путь цепочки из репорта — регресс ────────────────────────

    @Transactional
    @Test
    void happyPath_chainWithReturnedVariable_completesEndToEnd() {
        UUID pi;
        try {
            pi = start("test-eng29-stage-activity.bpmn", List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // Воркер вернул routeStageUuid — output-маппинг пишет его в root,
        // input-маппинг следующего таска читает как раньше.
        UUID stage = activeServiceTask(pi, "srvStageCreate").getId();
        runtimeService.completeServiceTask(stage,
            List.of(var("routeStageUuid", ProcessVariableType.STRING, "9f6d1a2b")));
        assertThat(incidentsOf(pi)).isEmpty();
        assertThat(rootVars(pi).get("routeStageUuid")).isEqualTo("9f6d1a2b");

        UUID activity = activeServiceTask(pi, "srvActivityCreate").getId();
        runtimeService.completeServiceTask(activity, List.of());
        assertThat(incidentsOf(pi)).isEmpty();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
    }
}
