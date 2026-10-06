package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-33 критерий 3: сквозной путь — output io-mapping {@code =now()} при
 * завершении service task даёт переменную STRING, которую парсит Go-layout
 * {@code 2006-01-02T15:04:05Z07:00} без ручной обработки; инцидента нет,
 * инстанс завершается.
 *
 * <p>Идёт реальным прод-путём ({@code RuntimeService} →
 * {@code CompletionService} → {@code ElementSupport.evaluateMapping} →
 * {@code toProcessVariable} — G-N), на BPMN-фикстуре
 * {@code test-eng33-now-output.bpmn}.
 *
 * <p>POF-мутация (P-67): убрать {@code TemporalAccessor}-ветку из
 * {@code toProcessVariable} → этот тест КРАСНЫЙ (actual содержит
 * {@code [Asia/Almaty]}); вернуть ветку → GREEN. Дословные выводы — в отчёте.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Eng33NowIoMappingIT {

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

    @Transactional
    @Test
    void criterion3_nowOutputMapping_storesGoParsableIsoOffsetString() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-eng33-now-output.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of());
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        UUID task = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("srvStamp")
                && (a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no active activity srvStamp"))
            .getId();
        runtimeService.completeServiceTask(task, List.of());

        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
            .filter(i -> {
                ActivityEntity a = activityRepository.findById(i.getActivityId()).orElse(null);
                return a != null && a.getProcessInstanceId().equals(pi);
            })
            .toList();
        assertThat(incidents)
            .as("=now() output mapping must not raise an incident")
            .isEmpty();

        Map<String, ProcessVariableEntity> vars = variableRepository
            .findByProcessInstanceIdAndScopeIdIsNull(pi).stream()
            .collect(Collectors.toMap(ProcessVariableEntity::getName, v -> v, (a, b) -> b));
        ProcessVariableEntity started = vars.get("started");
        assertThat(started)
            .as("output mapping =now() must write the 'started' variable")
            .isNotNull();
        assertThat(started.getType())
            .as("contract unchanged: still a STRING variable")
            .isEqualTo(ProcessVariableType.STRING);
        assertThat(started.getTextValue())
            .as("no Java [ZoneId] suffix — this exact suffix broke the Go consumer")
            .doesNotContain("[")
            .doesNotContain("]");
        OffsetDateTime parsed = OffsetDateTime.parse(started.getTextValue(),
            DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertThat(java.time.Duration.between(parsed.toInstant(), Instant.now()).abs())
            .as("stored value must be a real ~now timestamp in RFC 3339 form")
            .isLessThan(java.time.Duration.ofMinutes(1));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("instance completes normally after the stamped mapping")
            .isNotNull();
    }
}
