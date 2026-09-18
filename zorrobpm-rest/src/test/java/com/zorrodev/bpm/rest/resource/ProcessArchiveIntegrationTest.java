package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessArchiveIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String userToken;
    private UUID userId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        // regular user without grants
        userId = UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(userId);
        u.setUsername("archive-tester-" + userId.toString().substring(0, 8));
        u.setPasswordHash(passwordHasher.hash("pass"));
        u.setFullName("Archive Tester");
        u.setRole("USER");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
        userToken = login(u.getUsername(), "pass");
    }

    @Test
    void archive_hidesFromDefaultList_includeArchivedShowsAgain() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/process1.bpmn"));
        String key = "arch-" + UUID.randomUUID().toString().substring(0, 8);
        bpmn = bpmn.replace("process1", key).replace("Process 1", key);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult deploy = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        String deployedKey = mapper.readTree(deploy.getResponse().getContentAsString()).get("key").asText();

        // default list contains it
        MvcResult before = mockMvc.perform(get("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .param("processDefinitionKey", deployedKey))
            .andExpect(status().isOk()).andReturn();
        assertThat(before.getResponse().getContentAsString()).contains(deployedKey);

        // archive
        mockMvc.perform(post("/processes/" + deployedKey + "/archive")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        // default list hides it
        MvcResult hidden = mockMvc.perform(get("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .param("processDefinitionKey", deployedKey))
            .andExpect(status().isOk()).andReturn();
        JsonNode hiddenData = mapper.readTree(hidden.getResponse().getContentAsString()).get("data");
        assertThat(hiddenData.isArray()).isTrue();
        assertThat(hiddenData.size()).isEqualTo(0);

        // includeArchived=true shows it
        MvcResult withArchived = mockMvc.perform(get("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .param("processDefinitionKey", deployedKey)
                .param("includeArchived", "true"))
            .andExpect(status().isOk()).andReturn();
        JsonNode withData = mapper.readTree(withArchived.getResponse().getContentAsString()).get("data");
        assertThat(withData.size()).isEqualTo(1);
        assertThat(withData.get(0).get("key").asText()).isEqualTo(deployedKey);
        assertThat(withData.get(0).get("archived").asBoolean()).isTrue();

        // unarchive
        mockMvc.perform(post("/processes/" + deployedKey + "/unarchive")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        MvcResult afterUnarchive = mockMvc.perform(get("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .param("processDefinitionKey", deployedKey))
            .andExpect(status().isOk()).andReturn();
        JsonNode afterData = mapper.readTree(afterUnarchive.getResponse().getContentAsString()).get("data");
        assertThat(afterData.size()).isEqualTo(1);
        assertThat(afterData.get(0).get("archived").asBoolean()).isFalse();
    }

    @Test
    void archive_requiresDeployAuth() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/process1.bpmn"));
        String key = "arch-auth-" + UUID.randomUUID().toString().substring(0, 8);
        bpmn = bpmn.replace("process1", key).replace("Process 1", key);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/processes/" + key + "/archive")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void archivedProcess_runningInstanceContinues() throws Exception {
        String key = "arch-run-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" id=\"Defs\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n"
            + "  <bpmn:process id=\"" + key + "\" name=\"" + key + "\" isExecutable=\"true\">\n"
            + "    <bpmn:startEvent id=\"startEvent\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>\n"
            + "    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"startEvent\" targetRef=\"svc\"/>\n"
            + "    <bpmn:serviceTask id=\"svc\" name=\"svc\"><bpmn:extensionElements><zeebe:taskDefinition type=\"testsvc\" retries=\"3\"/></bpmn:extensionElements><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>\n"
            + "    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"svc\" targetRef=\"endEvent\"/>\n"
            + "    <bpmn:endEvent id=\"endEvent\"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>\n"
            + "  </bpmn:process>\n"
            + "</bpmn:definitions>";
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult deploy = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        String deployedKey = mapper.readTree(deploy.getResponse().getContentAsString()).get("key").asText();
        UUID pdId = UUID.fromString(mapper.readTree(deploy.getResponse().getContentAsString()).get("id").asText());

        // start instance
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(pdId);
        MvcResult start = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(startDto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        UUID piId = UUID.fromString(mapper.readTree(start.getResponse().getContentAsString()).get("id").asText());

        // archive while instance is active
        mockMvc.perform(post("/processes/" + deployedKey + "/archive")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        // instance still active, can be completed via service task
        // find service task id via query (simplify: complete via runtime)
        // we need to fetch service tasks for this instance
        MvcResult tasks = mockMvc.perform(get("/service-tasks")
                .header("Authorization", "Bearer " + adminToken)
                .param("processInstanceId", piId.toString()))
            .andExpect(status().isOk()).andReturn();
        JsonNode tasksData = mapper.readTree(tasks.getResponse().getContentAsString()).get("data");
        assertThat(tasksData.size()).isGreaterThanOrEqualTo(1);
        String taskId = tasksData.get(0).get("id").asText();

        mockMvc.perform(post("/service-tasks/" + taskId + "/complete")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        // instance should be completed
        MvcResult pi = mockMvc.perform(get("/process-instances/" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk()).andReturn();
        JsonNode piNode = mapper.readTree(pi.getResponse().getContentAsString());
        assertThat(piNode.get("completedAt").isNull()).isFalse();
    }

    private String login(String username, String password) throws Exception {
        com.zorrodev.bpm.contract.dto.LoginDTO dto = new com.zorrodev.bpm.contract.dto.LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        String body = mapper.writeValueAsString(dto);
        MvcResult r = mockMvc.perform(post("/auth/login").content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }
}
