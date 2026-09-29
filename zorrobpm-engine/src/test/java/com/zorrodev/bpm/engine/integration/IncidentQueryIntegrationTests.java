package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class IncidentQueryIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Transactional
    @Test
    void findIncidentsFiltersByProcessInstanceId() throws Exception {
        // produce an incident (gateway with no matching condition / no default), then verify the
        // processInstanceId filter on findIncidents (previously ignored -> all filters were dropped).
        String bpmn = Files.readString(Paths.get("src/test/files/test-gateway-no-default.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable action = new ProcessVariable();
        action.setName("action");
        action.setType(ProcessVariableType.STRING);
        action.setValue("C");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(action));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        // filter matches the instance that owns the incident
        IncidentQuery match = new IncidentQuery();
        match.setProcessInstanceId(processInstanceId);
        PagedDataDTO<Incident> matched = queryService.findIncidents(match, null);
        assertThat(matched.getData()).hasSize(1);

        // filter by an unrelated instance returns nothing (proves the filter is actually applied)
        IncidentQuery noMatch = new IncidentQuery();
        noMatch.setProcessInstanceId(UUID.randomUUID());
        PagedDataDTO<Incident> none = queryService.findIncidents(noMatch, null);
        assertThat(none.getData()).isEmpty();
    }
}
