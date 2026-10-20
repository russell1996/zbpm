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
 * WO-ENG-29 (incident part SUPERSEDED by WO-C8-54) — FEEL output/input-маппинг, чей
 * source ссылается на переменную, которой нет в контексте (воркер не вернул её в
 * результате джобы).
 *
 * <p><b>WO-C8-54 (2026-10-19) changed this contract</b>, because the live Zeebe 8.5
 * measurement of the F1 campaign disproved the premise WO-ENG-29 was built on
 * («Zeebe raises an incident on a missing io-mapping variable»). Live 8.5: the
 * source evaluates to a NULL VALUE — the job is created, the mapped variable is a
 * JSON null, the process continues, NO incident
 * ({@code docs/analysis/c8-oracle/feel-iomapping.js}, case {@code missing-bare}:
 * {@code completed  work={"r":null}}). The strict/lenient duality of WO-ENG-30
 * existed only to roll that assumption out safely; with the assumption gone it is
 * gone too (both profiles follow live 8.5).</p>
 *
 * <p>What this class pins NOW (WO-C8-54), on the real prod path
 * ({@code RuntimeService} → {@code CompletionService.finishServiceTaskCompletion} /
 * {@code ActivityServiceImpl.execute} → {@code ElementSupport.evaluateMapping} —
 * G-N), on the same BPMN fixtures the live report describes
 * ({@code srvStageCreate} → {@code routeStageUuid} → {@code srvActivityCreate}):</p>
 * <ol>
 *   <li>an output mapping on an absent job variable → a JSON null at root, NO
 *       incident, the flow continues;</li>
 *   <li>an input mapping on an absent variable → the task is created with a JSON
 *       null, NO incident;</li>
 *   <li>an explicit {@code =null} → a JSON null, no incident, the process ends.</li>
 * </ol>
 *
 * <p>POF (критерий 7): откат {@code evaluateMapping} на JSR-223
 * {@code scriptService.evaluateExpression} → criterion1 КРАСНЫЙ (тихая {@code ""}
 * вместо JSON null); возврат нативного API → GREEN. Дословные выводы — в отчёте.
 * </p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Eng29FeelNullIoMappingIncidentIntegrationTests {

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

    /** Root-scope row of a variable (type + text value) — the null representation matters. */
    private ProcessVariableEntity rootVar(UUID processInstanceId, String name) {
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId).stream()
            .filter(e -> name.equals(e.getName()))
            .findFirst()
            .orElse(null);
    }

    // ─── Критерий 1: output-маппинг на отсутствующую переменную → null ────────

    @Transactional
    @Test
    void criterion1_outputMappingOnMissingJobVariable_writesJsonNullAndDoesNotIncident() {
        UUID pi;
        try {
            pi = start("test-eng29-stage-activity.bpmn", List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // Живой репорт: srvStageCreate завершён, воркер НЕ вернул routeStageUuid.
        UUID stage = activeServiceTask(pi, "srvStageCreate").getId();
        runtimeService.completeServiceTask(stage, List.of());

        assertThat(incidentsOf(pi))
            .as("a missing io-mapping variable is a null VALUE in live 8.5 — no incident")
            .isEmpty();

        ProcessVariableEntity written = rootVar(pi, "routeStageUuid");
        assertThat(written)
            .as("the output mapping must still write the variable — as a JSON null")
            .isNotNull();
        assertThat(written.getType())
            .as("null is carried as a JSON null (the representation live 8.5 delivers)")
            .isEqualTo(ProcessVariableType.JSON);
        assertThat(written.getTextValue()).isEqualTo("null");

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the instance must NOT park on an incident")
            .isNull();
    }

    // ─── Критерий 2: input-маппинг на отсутствующую переменную → null ─────────

    @Transactional
    @Test
    void criterion2_inputMappingOnMissingVariable_createsTheTaskWithAJsonNull() {
        UUID pi;
        try {
            pi = start("test-eng29-missing-input.bpmn", List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // Input-маппинг вычисляется при входе — задача создаётся, инцидента нет.
        assertThat(incidentsOf(pi))
            .as("input mapping '=neverProvidedVar' on activation must not raise an incident")
            .isEmpty();
        UUID svc = activeServiceTask(pi, "svcIn").getId();
        assertThat(svc).as("the task must be created (live 8.5 creates the job)").isNotNull();
    }

    // ─── Критерий 3: легитимный null — значение, не ошибка ────────────────────

    @Transactional
    @Test
    void criterion3_explicitNullLiteral_keepsTheNullValueOnBothPaths() {
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

        ProcessVariableEntity written = rootVar(pi, "nullEcho");
        assertThat(written).as("the explicit =null output is written").isNotNull();
        assertThat(written.getType())
            .as("an explicit =null is a JSON null (live 8.5: {\"r\":null})")
            .isEqualTo(ProcessVariableType.JSON);
        assertThat(written.getTextValue()).isEqualTo("null");
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
