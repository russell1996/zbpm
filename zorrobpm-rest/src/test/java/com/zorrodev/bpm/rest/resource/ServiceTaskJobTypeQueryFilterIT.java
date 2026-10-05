package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-IN-1 through the endpoint the bug was reported on: {@code GET /service-tasks?jobType=X}.
 *
 * <p>Full-context IT (V11): real JwtAuthFilter chain, real Spring MVC binding of {@code jobType}
 * onto {@code ServiceTaskQuery}, real engine and DB. The BPMN fixture keeps two service tasks
 * with different job types open at once, so a response containing both of them proves the
 * parameter was swallowed.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class ServiceTaskJobTypeQueryFilterIT {

    private static final String ALPHA_JOB = "in1-alpha-job";
    private static final String BETA_JOB = "in1-beta-job";

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private UUID processInstanceId;
    private UUID alphaTaskId;
    private UUID betaTaskId;

    @BeforeAll
    void setUp() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
        UUID pdId = deployProcess();
        processInstanceId = startInstance(pdId);
        List<JsonNode> open = serviceTasks(null, null);
        assertThat(open).as("fixture precondition: both jobs open").extracting(this::jobOf)
            .containsExactlyInAnyOrder(ALPHA_JOB, BETA_JOB);
        alphaTaskId = idOfJob(open, ALPHA_JOB);
        betaTaskId = idOfJob(open, BETA_JOB);
    }

    /** Criterion 1 (the bug, as reported): ?jobType=X must narrow the page to that job only. */
    @Test
    void serviceTasks_withJobType_returnsOnlyThatJob() throws Exception {
        assertThat(serviceTasks(ALPHA_JOB, null)).extracting(this::jobOf)
            .containsExactly(ALPHA_JOB);
        assertThat(idsOf(serviceTasks(ALPHA_JOB, null))).containsExactly(alphaTaskId);

        assertThat(serviceTasks(BETA_JOB, null)).extracting(this::jobOf)
            .containsExactly(BETA_JOB);
        assertThat(idsOf(serviceTasks(BETA_JOB, null))).containsExactly(betaTaskId);

        assertThat(serviceTasks("no-such-job-type", null)).isEmpty();
    }

    /** Golden check: no parameter → the page is unchanged (both jobs of the instance). */
    @Test
    void serviceTasks_withoutJobType_returnsBothJobs() throws Exception {
        assertThat(serviceTasks(null, null)).extracting(this::jobOf)
            .containsExactlyInAnyOrder(ALPHA_JOB, BETA_JOB);
    }

    /** Criterion 2: blank parameter == absent parameter (no filter, not "match nothing"). */
    @Test
    void serviceTasks_withBlankJobType_returnsBothJobs() throws Exception {
        assertThat(serviceTasks("", null)).extracting(this::jobOf)
            .containsExactlyInAnyOrder(ALPHA_JOB, BETA_JOB);
    }

    /** jobType + completed over HTTP: both axes must apply. */
    @Test
    void serviceTasks_withJobTypeAndCompleted_narrowsOnBothAxes() throws Exception {
        assertThat(serviceTasks(ALPHA_JOB, "false")).extracting(this::jobOf).containsExactly(ALPHA_JOB);
        assertThat(serviceTasks(BETA_JOB, "false")).extracting(this::jobOf).containsExactly(BETA_JOB);
        assertThat(serviceTasks(ALPHA_JOB, "true")).isEmpty();
        assertThat(serviceTasks(BETA_JOB, "true")).isEmpty();

        mockMvc.perform(post("/service-tasks/" + alphaTaskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful());

        assertThat(serviceTasks(ALPHA_JOB, "true")).extracting(this::jobOf).containsExactly(ALPHA_JOB);
        assertThat(serviceTasks(BETA_JOB, "true")).isEmpty();
        assertThat(serviceTasks(BETA_JOB, "false")).extracting(this::jobOf).containsExactly(BETA_JOB);
        assertThat(serviceTasks(null, "true")).extracting(this::jobOf).containsExactly(ALPHA_JOB);
    }

    // ------------------------------------------------------------------ helpers

    /** {@code null} for either parameter means "do not send it at all". */
    private List<JsonNode> serviceTasks(String jobType, String completed) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; page < 5; page++) {
            var request = get("/service-tasks")
                .header("Authorization", "Bearer " + adminToken)
                .param("processInstanceId", processInstanceId.toString())
                .param("pageSize", "50")
                .param("pageIndex", String.valueOf(page));
            if (jobType != null) {
                request = request.param("jobType", jobType);
            }
            if (completed != null) {
                request = request.param("completed", completed);
            }
            MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
            JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
            List<JsonNode> data = new ArrayList<>();
            body.get("data").forEach(data::add);
            out.addAll(data);
            long total = body.get("totalElements").asLong();
            if (out.size() >= total || data.isEmpty()) {
                break;
            }
        }
        return out;
    }

    private String jobOf(JsonNode node) {
        return node.get("job").asText();
    }

    private List<UUID> idsOf(List<JsonNode> nodes) {
        return nodes.stream().map(n -> UUID.fromString(n.get("id").asText())).toList();
    }

    private UUID idOfJob(List<JsonNode> nodes, String job) {
        return nodes.stream()
            .filter(n -> job.equals(jobOf(n)))
            .map(n -> UUID.fromString(n.get("id").asText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no task with job " + job + " in " + nodes));
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private UUID deployProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/in1-jobtype-two-tasks.bpmn"));
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID startInstance(UUID pdId) throws Exception {
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(pdId);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}