package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-DIFF-5: public message/error-throw API end-to-end over HTTP.
 *
 * <ul>
 *   <li>{@code POST /service-tasks/{id}/throw-error} on an S-046-style fork (boundary directly
 *       on the throwing task) fires the escape branch and carries thrown variables;</li>
 *   <li>{@code POST /messages/publish} correlates a waiting message boundary (counted);</li>
 *   <li>global publish is SUPER_ADMIN-only (fail-closed); stale throw is 409; both paths replay
 *       idempotently.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class Diff5MessageErrorApiIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String userToken;

    private static final String THROW_BPMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" id=\"Defs\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n"
        + "  <bpmn:error id=\"throwErr\" name=\"Throw Error\" errorCode=\"E-THROW\" />\n"
        + "  <bpmn:process id=\"%s\" name=\"%s\" isExecutable=\"true\">\n"
        + "    <bpmn:startEvent id=\"startEvent\"><bpmn:outgoing>fStart</bpmn:outgoing></bpmn:startEvent>\n"
        + "    <bpmn:sequenceFlow id=\"fStart\" sourceRef=\"startEvent\" targetRef=\"forkTask\"/>\n"
        + "    <bpmn:serviceTask id=\"forkTask\" name=\"forkTask\"><bpmn:extensionElements><zeebe:taskDefinition type=\"fork\"/></bpmn:extensionElements><bpmn:incoming>fStart</bpmn:incoming><bpmn:outgoing>fToWork</bpmn:outgoing><bpmn:outgoing>fToGuarded</bpmn:outgoing></bpmn:serviceTask>\n"
        + "    <bpmn:sequenceFlow id=\"fToWork\" sourceRef=\"forkTask\" targetRef=\"work\"/>\n"
        + "    <bpmn:sequenceFlow id=\"fToGuarded\" sourceRef=\"forkTask\" targetRef=\"guarded\"/>\n"
        + "    <bpmn:serviceTask id=\"work\" name=\"work\"><bpmn:extensionElements><zeebe:taskDefinition type=\"work\"/></bpmn:extensionElements><bpmn:incoming>fToWork</bpmn:incoming><bpmn:outgoing>fWorkEnd</bpmn:outgoing></bpmn:serviceTask>\n"
        + "    <bpmn:sequenceFlow id=\"fWorkEnd\" sourceRef=\"work\" targetRef=\"endWork\"/>\n"
        + "    <bpmn:endEvent id=\"endWork\"><bpmn:incoming>fWorkEnd</bpmn:incoming></bpmn:endEvent>\n"
        + "    <bpmn:serviceTask id=\"guarded\" name=\"guarded\"><bpmn:extensionElements><zeebe:taskDefinition type=\"guarded\"/></bpmn:extensionElements><bpmn:incoming>fToGuarded</bpmn:incoming><bpmn:outgoing>fGuardedEnd</bpmn:outgoing></bpmn:serviceTask>\n"
        + "    <bpmn:sequenceFlow id=\"fGuardedEnd\" sourceRef=\"guarded\" targetRef=\"endGuarded\"/>\n"
        + "    <bpmn:endEvent id=\"endGuarded\"><bpmn:incoming>fGuardedEnd</bpmn:incoming></bpmn:endEvent>\n"
        + "    <bpmn:boundaryEvent id=\"errBoundary\" attachedToRef=\"guarded\" cancelActivity=\"true\"><bpmn:outgoing>fEscape</bpmn:outgoing><bpmn:errorEventDefinition id=\"ed1\" errorRef=\"throwErr\"/></bpmn:boundaryEvent>\n"
        + "    <bpmn:sequenceFlow id=\"fEscape\" sourceRef=\"errBoundary\" targetRef=\"escapeEnd\"/>\n"
        + "    <bpmn:endEvent id=\"escapeEnd\"><bpmn:incoming>fEscape</bpmn:incoming></bpmn:endEvent>\n"
        + "  </bpmn:process>\n</bpmn:definitions>";

    private static final String MSG_BPMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"Defs\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n"
        + "  <bpmn:message id=\"msg1\" name=\"%s\" />\n"
        + "  <bpmn:process id=\"%s\" name=\"%s\" isExecutable=\"true\">\n"
        + "    <bpmn:startEvent id=\"startEvent\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>\n"
        + "    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"startEvent\" targetRef=\"userTask1\"/>\n"
        + "    <bpmn:userTask id=\"userTask1\" name=\"userTask1\"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:userTask>\n"
        + "    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"userTask1\" targetRef=\"endEvent\"/>\n"
        + "    <bpmn:endEvent id=\"endEvent\"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>\n"
        + "    <bpmn:boundaryEvent id=\"msgBoundary\" attachedToRef=\"userTask1\" cancelActivity=\"true\"><bpmn:outgoing>fB</bpmn:outgoing><bpmn:messageEventDefinition id=\"med1\" messageRef=\"msg1\"/></bpmn:boundaryEvent>\n"
        + "    <bpmn:sequenceFlow id=\"fB\" sourceRef=\"msgBoundary\" targetRef=\"boundaryEnd\"/>\n"
        + "    <bpmn:endEvent id=\"boundaryEnd\"><bpmn:incoming>fB</bpmn:incoming></bpmn:endEvent>\n"
        + "  </bpmn:process>\n</bpmn:definitions>";

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        createUser("diff5-user", "USER");
        userToken = login("diff5-user", "pass");
    }

    private void createUser(String username, String globalRole) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName("DIFF5 " + username);
        user.setRole(globalRole);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    private String login(String u, String p) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(u);
        dto.setPassword(p);
        MvcResult r = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer").content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private UUID deploy(String bpmn) throws Exception {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult r = mockMvc.perform(post("/process-definitions").header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID start(UUID pdId) throws Exception {
        String body = "{\"processDefinitionId\":\"" + pdId + "\",\"variables\":[]}";
        MvcResult r = mockMvc.perform(post("/process-instances").header("Authorization", "Bearer " + adminToken)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID serviceTask(UUID piId, String bpmnElementId, String token) throws Exception {
        MvcResult tasks = mockMvc.perform(get("/service-tasks?processInstanceId=" + piId)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        JsonNode data = mapper.readTree(tasks.getResponse().getContentAsString()).get("data");
        for (JsonNode n : data) {
            if (bpmnElementId.equals(n.get("code").asText())) {
                return UUID.fromString(n.get("id").asText());
            }
        }
        throw new AssertionError("no service task " + bpmnElementId + " in " + tasks.getResponse().getContentAsString());
    }

    private JsonNode activities(UUID piId) throws Exception {
        MvcResult r = mockMvc.perform(get("/process-instances/" + piId + "/activities")
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private static boolean hasActivity(JsonNode activities, String bpmnElementId, String status) {
        for (JsonNode a : activities) {
            if (bpmnElementId.equals(a.get("bpmnElementId").asText()) && status.equals(a.get("status").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void throwError_endToEnd_escapeBranchFiresAndCarriesVariables() throws Exception {
        String key = "diff5-throw-" + UUID.randomUUID().toString().substring(0, 8);
        UUID piId = start(deploy(String.format(THROW_BPMN, key, key)));
        UUID forkId = serviceTask(piId, "forkTask", adminToken);
        mockMvc.perform(post("/service-tasks/" + forkId + "/complete").header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[]}").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

        UUID guardedId = serviceTask(piId, "guarded", adminToken);
        String body = "{\"errorCode\":\"E-THROW\",\"variables\":[{\"name\":\"thrownVar\",\"value\":\"v\",\"type\":\"STRING\"}]}";
        MvcResult thrown = mockMvc.perform(post("/service-tasks/" + guardedId + "/throw-error")
                .header("Authorization", "Bearer " + adminToken)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        JsonNode result = mapper.readTree(thrown.getResponse().getContentAsString());
        assertThat(result.get("handled").asBoolean()).as("own boundary must catch, got " + result).isTrue();
        assertThat(result.get("incidentId").isNull()).isTrue();

        JsonNode acts = activities(piId);
        assertThat(hasActivity(acts, "escapeEnd", "COMPLETED")).as("escape branch fired: " + acts).isTrue();
        assertThat(hasActivity(acts, "guarded", "CANCELLED")).as("throwing task cancelled: " + acts).isTrue();
        // sibling untouched — its service task is still listed as active
        assertThat(serviceTask(piId, "work", adminToken)).isNotNull();

        MvcResult vars = mockMvc.perform(get("/variables?processInstanceId=" + piId)
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(vars.getResponse().getContentAsString()).get("data").toString())
            .as("thrownVar visible on the instance").contains("thrownVar");
    }

    @Test
    void throwError_unmatchedCode_200HandledFalseWithIncident() throws Exception {
        String key = "diff5-unhandled-" + UUID.randomUUID().toString().substring(0, 8);
        UUID piId = start(deploy(String.format(THROW_BPMN, key, key)));
        UUID forkId = serviceTask(piId, "forkTask", adminToken);
        mockMvc.perform(post("/service-tasks/" + forkId + "/complete").header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[]}").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

        UUID guardedId = serviceTask(piId, "guarded", adminToken);
        MvcResult thrown = mockMvc.perform(post("/service-tasks/" + guardedId + "/throw-error")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"errorCode\":\"E-NOPE\",\"variables\":[]}").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();
        JsonNode result = mapper.readTree(thrown.getResponse().getContentAsString());
        assertThat(result.get("handled").asBoolean()).isFalse();
        assertThat(result.get("incidentId").isNull()).as("incident id must be returned, got " + result).isFalse();

        MvcResult incidents = mockMvc.perform(get("/incidents").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk()).andReturn();
        assertThat(incidents.getResponse().getContentAsString()).contains(result.get("incidentId").asText());
    }

    @Test
    void throwError_staleTask_409() throws Exception {
        String key = "diff5-stale-" + UUID.randomUUID().toString().substring(0, 8);
        UUID piId = start(deploy(String.format(THROW_BPMN, key, key)));
        UUID forkId = serviceTask(piId, "forkTask", adminToken);
        mockMvc.perform(post("/service-tasks/" + forkId + "/complete").header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[]}").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

        UUID guardedId = serviceTask(piId, "guarded", adminToken);
        mockMvc.perform(post("/service-tasks/" + guardedId + "/complete").header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[]}").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

        mockMvc.perform(post("/service-tasks/" + guardedId + "/throw-error")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"errorCode\":\"E-THROW\",\"variables\":[]}").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isConflict());
    }

    @Test
    void throwError_sameBody_replays() throws Exception {
        String key = "diff5-replay-" + UUID.randomUUID().toString().substring(0, 8);
        UUID piId = start(deploy(String.format(THROW_BPMN, key, key)));
        UUID forkId = serviceTask(piId, "forkTask", adminToken);
        mockMvc.perform(post("/service-tasks/" + forkId + "/complete").header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[]}").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

        UUID guardedId = serviceTask(piId, "guarded", adminToken);
        String idemKey = UUID.randomUUID().toString();
        String body = "{\"errorCode\":\"E-THROW\",\"variables\":[]}";
        MvcResult first = mockMvc.perform(post("/service-tasks/" + guardedId + "/throw-error")
                .header("Authorization", "Bearer " + adminToken).header("Idempotency-Key", idemKey)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(post("/service-tasks/" + guardedId + "/throw-error")
                .header("Authorization", "Bearer " + adminToken).header("Idempotency-Key", idemKey)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void publishMessage_endToEnd_correlatesWaitingBoundary() throws Exception {
        String msg = "diff5-msg-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "diff5-pub-" + UUID.randomUUID().toString().substring(0, 8);
        UUID piId = start(deploy(String.format(MSG_BPMN, msg, key, key)));

        String body = "{\"messageName\":\"" + msg + "\",\"variables\":[]}";
        MvcResult published = mockMvc.perform(post("/messages/publish")
                .header("Authorization", "Bearer " + adminToken)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        JsonNode result = mapper.readTree(published.getResponse().getContentAsString());
        assertThat(result.get("correlated").asInt()).as("one waiting boundary, got " + result).isEqualTo(1);
        assertThat(result.get("started").asInt()).isZero();

        JsonNode acts = activities(piId);
        assertThat(hasActivity(acts, "boundaryEnd", "COMPLETED")).as("boundary path taken: " + acts).isTrue();
        assertThat(hasActivity(acts, "userTask1", "CANCELLED")).as("host task cancelled: " + acts).isTrue();
    }

    @Test
    void publishMessage_sameBody_replays() throws Exception {
        // WO-DIFF-5 (verifier HOLD: a nobody-listens replay proves nothing — (0,0) is
        // deterministic either way). Replay against a LIVE waiting boundary instead: the
        // first publish wakes it (correlated=1) and consumes it; the replay must return
        // the STORED response (correlated=1 again), while a re-execution without the
        // filter would find nobody left waiting (correlated=0) — so removing the
        // "/messages/publish" registration from IdempotencyFilter turns this RED.
        String msg = "diff5-replay-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "diff5-pubr-" + UUID.randomUUID().toString().substring(0, 8);
        UUID piId = start(deploy(String.format(MSG_BPMN, msg, key, key)));

        String body = "{\"messageName\":\"" + msg + "\",\"variables\":[]}";
        String idemKey = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(post("/messages/publish")
                .header("Authorization", "Bearer " + adminToken).header("Idempotency-Key", idemKey)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(first.getResponse().getContentAsString()).get("correlated").asInt())
            .as("first publish must wake the waiting boundary").isEqualTo(1);
        MvcResult second = mockMvc.perform(post("/messages/publish")
                .header("Authorization", "Bearer " + adminToken).header("Idempotency-Key", idemKey)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(mapper.readTree(second.getResponse().getContentAsString()).get("correlated").asInt())
            .as("replay must return the STORED correlated=1, not a re-execution (which would see 0)")
            .isEqualTo(1);

        // control: without the idempotency key the same publish now finds nobody waiting
        MvcResult third = mockMvc.perform(post("/messages/publish")
                .header("Authorization", "Bearer " + adminToken)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(third.getResponse().getContentAsString()).get("correlated").asInt())
            .as("control: re-execution after consumption correlates nothing").isZero();
    }

    @Test
    void publishMessage_global_nonAdmin_403() throws Exception {
        String body = "{\"messageName\":\"diff5-nobody-listens-" + UUID.randomUUID().toString().substring(0, 8)
            + "\",\"variables\":[]}";
        mockMvc.perform(post("/messages/publish").header("Authorization", "Bearer " + userToken)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        // admin passes the same gate (zero subscriptions — honest counter, still 200)
        MvcResult ok = mockMvc.perform(post("/messages/publish").header("Authorization", "Bearer " + adminToken)
                .content(body).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        JsonNode result = mapper.readTree(ok.getResponse().getContentAsString());
        assertThat(result.get("correlated").asInt()).isZero();
        assertThat(result.get("started").asInt()).isZero();
    }
}
