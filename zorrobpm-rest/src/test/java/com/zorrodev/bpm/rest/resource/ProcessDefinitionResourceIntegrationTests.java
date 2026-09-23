package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.FileService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessDefinitionResourceIntegrationTests {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProcessDefinitionRepository processDefinitionRepository;

    @Autowired
    private FileService fileService;

    private String validToken;

    @BeforeAll
    void login() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        validToken = authResponse.getToken();
    }

    @Test
    void testSuccessfulAddProcessDefinition() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/process1.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO requestDTO = new AddProcessDefinitionDTO();
        requestDTO.setBpmn(bpmn);
        String requestJson = mapper.writeValueAsString(requestDTO);

        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + validToken)
                        .content(requestJson)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        ProcessDefinition processDefinition = mapper.readValue(responseJson, ProcessDefinition.class);

        assertThat(processDefinition).isNotNull();
        assertThat(processDefinition.getKey()).isEqualTo("process1");
        assertThat(processDefinition.getVersion()).isNotNull();
        assertThat(processDefinition.getName()).isEqualTo("Process 1");

        UUID id = processDefinition.getId();
        assertThat(processDefinitionRepository.existsById(id)).isTrue();

        ProcessDefinitionEntity processDefinitionEntity = processDefinitionRepository.findById(id).orElseThrow();
        assertThat(processDefinitionEntity).isNotNull();
        assertThat(processDefinitionEntity.getId()).isEqualTo(id);
        assertThat(processDefinitionEntity.getKey()).isEqualTo("process1");
        assertThat(processDefinitionEntity.getVersion()).isNotNull();
        assertThat(processDefinitionEntity.getName()).isEqualTo("Process 1");

        Optional<String> savedBpmn = fileService.getFileBytes(id);

        assertThat(savedBpmn).isPresent();
    }

    @Test
    void getProcessDefinitions_orderDesc_returnsDescending() throws Exception {
        // Deploy assignee-task.bpmn (name "Assignee Process") and process1.bpmn (name "Process 1")
        deploy("assignee-task.bpmn");
        deploy("process1.bpmn");

        // When: query with order=desc
        // pageSize is deliberately generous: this test checks SORTING, not pagination —
        // with ~50 process definitions deployed across the full suite (incl. the
        // ACL-epic deployers), pageSize=50 pushed "Assignee Process" off the page.
        MvcResult result = mockMvc.perform(get("/process-definitions")
                        .param("order", "desc")
                        .param("pageSize", "500")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andReturn();

        PagedDataDTO<ProcessDefinition> page = mapper.readValue(
            result.getResponse().getContentAsString(),
            new TypeReference<PagedDataDTO<ProcessDefinition>>() {});

        // Then: "Process 1" before "Assignee Process" (descending on name)
        List<String> names = page.getData().stream()
                .filter(pd -> pd.getName().equals("Process 1") || pd.getName().equals("Assignee Process"))
                .map(ProcessDefinition::getName)
                .toList();

        assertThat(names)
            .as("order=desc should return 'Process 1' before 'Assignee Process'")
            .containsExactly("Process 1", "Assignee Process");
    }

    private void deploy(String fileName) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fileName), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + validToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }
}
