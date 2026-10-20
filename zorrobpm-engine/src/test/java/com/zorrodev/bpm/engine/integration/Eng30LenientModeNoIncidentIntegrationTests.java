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
 * WO-ENG-30 критерий 1 на реальном прод-пути: ПРОД-дефолтный профиль
 * ({@code zorrobpm.engine.io-mapping.strict-missing=false}) — output-маппинг на
 * отсутствующую переменную НЕ поднимает инцидент.
 *
 * <p><b>WO-C8-54 (2026-10-19) изменил и значение, и смысл профиля.</b> Живой
 * Zeebe 8.5 (F1-кампания, {@code docs/analysis/c8-oracle/feel-iomapping.js},
 * кейс {@code missing-bare}: {@code completed  work={"r":null}}) отдаёт NULL
 * ЗНАЧЕНИЕ — задание создаётся, переменная — JSON null, процесс идёт дальше.
 * Премиса WO-ENG-29 («Zeebe поднимает инцидент») оказалась неверной, поэтому
 * теперь оба профиля (strict и lenient) ведут себя одинаково, а значение —
 * JSON null, а не тихая пустая строка. Флаг
 * {@code strict-missing} стал OBSOLETE (см. {@code ElementSupport}).
 *
 * <p>Та же BPMN-фикстура и тот же сценарий, что criterion1 в
 * {@code Eng29FeelNullIoMappingIncidentIntegrationTests} — дельта поведения доказывается
 * парой тестов на одном прод-пути.
 *
 * <p>POF: вернуть прежнее поведение (тихая пустая строка в lenient) или
 * прежний инцидент (в strict) → этот тест КРАСНЫЙ; вернуть null-семантику →
 * GREEN. Дословные выводы — в отчёте.
 */
@SpringBootTest(classes = TestMain.class,
    properties = "zorrobpm.engine.io-mapping.strict-missing=false")
@ActiveProfiles("test")
public class Eng30LenientModeNoIncidentIntegrationTests {

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

    private Map<String, String> rootVars(UUID processInstanceId) {
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId).stream()
            .collect(Collectors.toMap(ProcessVariableEntity::getName, ProcessVariableEntity::getTextValue,
                (a, b) -> b));
    }

    @Transactional
    @Test
    void lenient_outputMappingOnMissingJobVariable_completesWithAJsonNull() {
        UUID pi;
        try {
            pi = start("test-eng29-stage-activity.bpmn", List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // Тот же шаг, что в Eng29-criterion1: воркер НЕ вернул routeStageUuid.
        // Lenient (и strict — с WO-C8-54 оба профиля одинаковы): инцидента нет,
        // output-маппинг пишет NULL-ЗНАЧЕНИЕ (live 8.5: {"r":null}).
        UUID stage = activeServiceTask(pi, "srvStageCreate").getId();
        runtimeService.completeServiceTask(stage, List.of());

        assertThat(incidentsOf(pi))
            .as("the production profile must NOT raise an incident on a missing job variable")
            .isEmpty();
        ProcessVariableEntity written = variableRepository
            .findByProcessInstanceIdAndScopeIdIsNull(pi).stream()
            .filter(e -> "routeStageUuid".equals(e.getName()))
            .findFirst()
            .orElse(null);
        assertThat(written).as("the output mapping still writes the variable").isNotNull();
        assertThat(written.getType())
            .as("a null is carried as a JSON null (live 8.5 parity)")
            .isEqualTo(ProcessVariableType.JSON);
        assertThat(written.getTextValue()).isEqualTo("null");

        // Цепочка идёт дальше и завершается — модель не паркуется.
        UUID activity = activeServiceTask(pi, "srvActivityCreate").getId();
        runtimeService.completeServiceTask(activity, List.of());
        assertThat(incidentsOf(pi)).isEmpty();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("process with legacy '' mapping completes normally in lenient mode")
            .isNotNull();
    }
}
