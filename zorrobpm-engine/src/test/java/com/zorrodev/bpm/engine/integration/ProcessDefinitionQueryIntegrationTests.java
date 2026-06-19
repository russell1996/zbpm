package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ProcessDefinitionQueryIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Transactional
    @Test
    void getProcessDefinitionsFiltersByNameCaseInsensitively() throws Exception {
        processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/test-signal.bpmn")));
        processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/test-send-receive.bpmn")));

        // partial, case-insensitive match on the process name
        ProcessDefinitionsQueryParameters byName = new ProcessDefinitionsQueryParameters();
        byName.setName("SEND-RECEIVE");
        PagedDataDTO<ProcessDefinition> matched = processDefinitionService.getProcessDefinitions(byName);

        assertThat(matched.getData()).isNotEmpty();
        assertThat(matched.getData()).allMatch(d -> d.getName().toLowerCase().contains("send-receive"));
        assertThat(matched.getData()).noneMatch(d -> d.getName().equals("test-signal"));
    }

    @Transactional
    @Test
    void getProcessDefinitionsFiltersByKey() throws Exception {
        processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/test-signal.bpmn")));

        ProcessDefinitionsQueryParameters byKey = new ProcessDefinitionsQueryParameters();
        byKey.setProcessDefinitionKey("test-signal");
        PagedDataDTO<ProcessDefinition> matched = processDefinitionService.getProcessDefinitions(byKey);

        assertThat(matched.getData()).hasSize(1);
        assertThat(matched.getData().get(0).getKey()).isEqualTo("test-signal");
    }
}
