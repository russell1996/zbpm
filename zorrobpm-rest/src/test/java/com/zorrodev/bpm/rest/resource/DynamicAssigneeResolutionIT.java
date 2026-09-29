package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.QueryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-INT-1: Dynamic assignee/candidateGroups resolution from process variables.
 *
 * #1: assignee=${managerId}, start with managerId=U → task.assignee = U
 * #2: FEEL expression in assignee resolves
 * #3: candidateGroups=${deptGroup} resolves
 * #4: literal assignee (no expression) still works
 * #5: unresolvable variable → null, instance doesn't crash
 * #6: proof-of-failure — before fix, field contains literal "${managerId}"
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamicAssigneeResolutionIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private QueryService queryService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String superAdminToken;
    private String dynamicAssigneeKey;
    private String feelAssigneeKey;
    private String literalAssigneeKey;

    @BeforeAll
    void setup() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");
        MvcResult loginResult = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        AuthResponse auth = mapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class);
        superAdminToken = auth.getToken();

        dynamicAssigneeKey = deployProcess("dynamic-assignee-process",
            "src/test/files/dynamic-assignee.bpmn");
        feelAssigneeKey = deployProcess("feel-assignee-process",
            "src/test/files/feel-assignee.bpmn");
        literalAssigneeKey = deployProcess("assignee-process",
            "src/test/files/assignee-task.bpmn");
    }

    @Test
    @Order(1)
    void criterion1_dynamicAssignee_resolvesVariable() throws Exception {
        UUID piId = startProcess(dynamicAssigneeKey, "managerId", "alice", "deptGroup", "engineering");
        UserTaskEntity entity = findUserTask(piId);
        assertThat(entity.getAssignee()).isEqualTo("alice");
    }

    @Test
    @Order(2)
    void criterion2_feelExpression_resolves() throws Exception {
        // FEEL expression: =managerId + "_lead" → transforms the variable value
        UUID piId = startProcess(feelAssigneeKey, "managerId", "bob");
        UserTaskEntity entity = findUserTask(piId);
        // FEEL evaluates "bob" + "_lead" → "bob_lead" (real transformation, not plain lookup)
        assertThat(entity.getAssignee()).isEqualTo("bob_lead");
    }

    @Test
    @Order(3)
    void criterion3_candidateGroups_resolvesVariable() throws Exception {
        UUID piId = startProcess(dynamicAssigneeKey, "managerId", "charlie", "deptGroup", "engineering,platform");
        UserTaskEntity entity = findUserTask(piId);
        assertThat(entity.getCandidateGroups()).isEqualTo("engineering,platform");
    }

    @Test
    @Order(4)
    void criterion4_literalAssignee_notBroken() throws Exception {
        UUID piId = startProcess(literalAssigneeKey);
        UserTaskEntity entity = findUserTask(piId);
        assertThat(entity.getAssignee()).isEqualTo("user1");
    }

    @Test
    @Order(5)
    void criterion5_missingVariable_returnsNull() throws Exception {
        UUID piId = startProcess(dynamicAssigneeKey);
        UserTaskEntity entity = findUserTask(piId);
        assertThat(entity.getAssignee()).isNull();
    }

    @Test
    @Order(6)
    void criterion6_proofOfFailure_literalVsResolved() throws Exception {
        UUID piId = startProcess(dynamicAssigneeKey, "managerId", "alice", "deptGroup", "engineering");
        UserTaskEntity entity = findUserTask(piId);
        assertThat(entity.getAssignee()).isEqualTo("alice");
        assertThat(entity.getAssignee()).isNotEqualTo("${managerId}");
    }

    private UserTaskEntity findUserTask(UUID processInstanceId) throws Exception {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        query.setPageIndex(0);
        query.setPageSize(100);
        var tasks = queryService.findUserTasks(query, null);
        assertThat(tasks.getData()).hasSize(1);
        return userTaskRepository.findById(tasks.getData().get(0).getId()).orElseThrow();
    }

    private String deployProcess(String key, String bpmnPath) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get(bpmnPath)), StandardCharsets.UTF_8);
        bpmn = bpmn.replace("id=\"" + key + "\"", "id=\"" + key + "\"")
                   .replace("name=\"" + key + "\"", "name=\"" + key + "\"")
                   .replace("process id=\"" + key + "\"", "process id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        return key;
    }

    private UUID startProcess(String processKey, String... vars) throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey(processKey);
        List<ProcessVariable> variables = new ArrayList<>();
        for (int i = 0; i < vars.length; i += 2) {
            ProcessVariable pv = new ProcessVariable();
            pv.setName(vars[i]);
            pv.setValue(vars[i + 1]);
            pv.setType(com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
            variables.add(pv);
        }
        dto.setVariables(variables);

        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
