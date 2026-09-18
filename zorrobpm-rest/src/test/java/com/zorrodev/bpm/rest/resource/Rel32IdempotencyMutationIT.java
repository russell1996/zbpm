package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
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

/**
 * WO-REL-32: idempotency on mutation endpoints (complete/fail/resolve/claim/cancel) + batch.
 * Same (key, endpoint, actor, body) → replay; same key+actor different body → 422.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class Rel32IdempotencyMutationIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    private String login(String u, String p) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(u);
        dto.setPassword(p);
        MvcResult r = mockMvc.perform(post("/auth/login")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private UUID deployProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn")))
            .replace("formKey=\"orderForm\"", "formKey=\"orderForm\"")
            .replace("id=\"form-process\"", "id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult r = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID startAndGetTask(UUID pdId) throws Exception {
        String body = "{\"processDefinitionId\":\"" + pdId + "\",\"variables\":[]}";
        MvcResult started = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        UUID piId = UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());
        MvcResult tasks = mockMvc.perform(get("/user-tasks?processInstanceId=" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk()).andReturn();
        return UUID.fromString(mapper.readTree(tasks.getResponse().getContentAsString()).get("data").get(0).get("id").asText());
    }

    @Test
    void complete_sameBody_replays() throws Exception {
        UUID taskId = startAndGetTask(deployProcess("rel32-c1-" + UUID.randomUUID().toString().substring(0,4)));
        String key = UUID.randomUUID().toString();
        String body = "{\"variables\":[{\"name\":\"x\",\"value\":\"1\",\"type\":\"STRING\"}]}";
        MvcResult first = mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void complete_differentBody_422() throws Exception {
        UUID taskId = startAndGetTask(deployProcess("rel32-c2-" + UUID.randomUUID().toString().substring(0,4)));
        String key = UUID.randomUUID().toString();
        String body1 = "{\"variables\":[{\"name\":\"x\",\"value\":\"1\",\"type\":\"STRING\"}]}";
        String body2 = "{\"variables\":[{\"name\":\"x\",\"value\":\"2\",\"type\":\"STRING\"}]}";
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body1).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body2).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void claim_sameBody_replays() throws Exception {
        UUID taskId = startAndGetTask(deployProcess("rel32-cl-" + UUID.randomUUID().toString().substring(0,4)));
        String key = UUID.randomUUID().toString();
        // claim has no body, but we send empty json; endpoint includes resourceId so key is per-task
        MvcResult first = mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key))
            .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key))
            .andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void cancel_sameBody_replays() throws Exception {
        String keyProc = "rel32-ca-" + UUID.randomUUID().toString().substring(0,4);
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/test-cancel-process.bpmn")))
            .replace("id=\"test-cancel-process\"", "id=\"" + keyProc + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult dep = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        UUID pdId = UUID.fromString(mapper.readTree(dep.getResponse().getContentAsString()).get("id").asText());
        String startBody = "{\"processDefinitionId\":\"" + pdId + "\",\"variables\":[]}";
        MvcResult started = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(startBody).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        String piId = mapper.readTree(started.getResponse().getContentAsString()).get("id").asText();
        String key = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(post("/process-instances/" + piId + "/cancel")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key))
            .andExpect(status().isAccepted()).andReturn();
        MvcResult second = mockMvc.perform(post("/process-instances/" + piId + "/cancel")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key))
            .andExpect(status().isAccepted()).andReturn();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void batchDeploy_sameBody_replays() throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        String body = "{\"resources\":[{\"type\":\"BPMN\",\"content\":" + mapper.writeValueAsString(bpmn) + "}]}";
        String key = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        MvcResult second = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        String id1 = mapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String id2 = mapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assertThat(id2).isEqualTo(id1);
    }
}
