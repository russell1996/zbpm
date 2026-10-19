package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.DeployDmnDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.SubmitProcessSubmissionDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.engine.repository.DmnDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessSubmissionRepository;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ProcessSubmissionService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WO-AUDIT-13 / F-07 — payload size caps on the two upload paths that had none:
 * {@code POST /dmn} (no cap at all: {@code DeployDmnDTO} without {@code @Size} and
 * {@code DmnServiceImpl.deploy} without a guard) and {@code POST /process-submissions}
 * (a service-level cap of 256 KiB, but no cap on the DTO itself).
 *
 * <p>V11: full Spring context, the real filter chain and the real service beans. The
 * DMN limit decision (maintainer, 2026-10-19): 5 MB, HTTP 400, code {@code DMN_TOO_LARGE}.
 * The submission limit stays 256 KiB — the constant moves to {@code zorrobpm-contract}
 * so that the DTO cap and the service guard cannot drift apart.
 *
 * <p>The limits are written as literals here on purpose: the test pins the DECIDED
 * contract value, not whatever the production constant happens to be (a test that
 * reads the constant back proves nothing — P-67 class).
 *
 * <p>POF (G-K/G-N), each mutation must turn a named test RED:
 * <ol>
 *   <li>remove {@code @Size} from {@code DeployDmnDTO.dmn}
 *       → {@code dmnOverLimit_viaHttp_…} (the spy sees the service call) and
 *         {@code dmnDto_declaresFiveMegabyteCap_…} go RED;</li>
 *   <li>remove the guard from {@code DmnServiceImpl.deploy}
 *       → {@code dmnOverLimit_serviceDirectCall_…} goes RED;</li>
 *   <li>map the DMN size violation to 413 (or to a different code) in the exception handler
 *       → {@code dmnOverLimit_viaHttp_…} goes RED;</li>
 *   <li>remove {@code @Size} from {@code SubmitProcessSubmissionDTO.bpmn}
 *       → {@code submissionOverLimit_viaHttp_…} (the spy sees the service call) and
 *         {@code submissionDto_declares256KiBCap_…} go RED;</li>
 *   <li>change the submission limit to 262143
 *       → {@code submissionAtLimit_viaHttp_…} and {@code submissionOverLimit_viaHttp_…} go RED.</li>
 * </ol>
 */
@ActiveProfiles("test")
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Audit13PayloadSizeLimitIT {

    /** maintainer decision (2026-10-19): the DMN cap is 5 MB, the same as the BPMN deploy cap. */
    private static final int DMN_LIMIT = 5_000_000;
    /** The submission cap stays 256 KiB (WO-ACL-3); only the constant moves to the contract. */
    private static final int SUBMISSION_LIMIT = 262_144;

    private static final String DMN_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="Definitions_@@ID@@" name="@@ID@@" namespace="http://camunda.org/schema/1.0/dmn">
          <!--@@PAD@@-->
          <decision id="@@ID@@" name="Decision">
            <decisionTable id="DecisionTable_1" hitPolicy="FIRST">
              <input id="Input_1" label="Category">
                <inputExpression id="InputExpression_1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_1" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_1">
                <inputEntry id="In_1"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_1"><text>20</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    private static final String BPMN_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_@@KEY@@" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="@@KEY@@" name="Test Process" isExecutable="true">
            <!--@@PAD@@-->
            <bpmn:startEvent id="startEvent">
              <bpmn:outgoing>flow1</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:endEvent id="endEvent">
              <bpmn:incoming>flow1</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flow1" sourceRef="startEvent" targetRef="endEvent" />
          </bpmn:process>
        </bpmn:definitions>
        """;

    @Autowired private MockMvc mockMvc;
    @Autowired private Validator validator;
    @Autowired private ProcessSubmissionRepository submissionRepository;
    @Autowired private DmnDefinitionRepository dmnRepository;

    /** Spies: they prove WHERE the oversized payload was rejected (binding layer vs service). */
    @MockitoSpyBean private DmnService dmnService;
    @MockitoSpyBean private ProcessSubmissionService submissionService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<UUID> createdSubmissionIds = new ArrayList<>();

    /**
     * The decision ids this class deploys (the "exactly at the limit" cases really do deploy
     * the DMN). They are cleaned up after every test — otherwise each run leaves two rows
     * behind in {@code dmn_definitions}.
     */
    private static final List<String> CREATED_DMN_DECISION_IDS =
        List.of("a13dmnAt", "a13dmnSvcAt");

    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    @AfterEach
    void cleanup() {
        if (!createdSubmissionIds.isEmpty()) {
            submissionRepository.deleteAllById(createdSubmissionIds);
            createdSubmissionIds.clear();
        }
        // findAllMeta() is a projection WITHOUT the @Lob `dmn` column — loading the blob
        // outside a transaction fails on PostgreSQL ("Large Objects may not be used in
        // auto-commit mode"), so the rows are addressed by id and deleted without the XML.
        List<UUID> dmnRowIds = dmnRepository.findAllMeta().stream()
            .filter(meta -> CREATED_DMN_DECISION_IDS.contains(meta.getDecisionId()))
            .map(DmnDefinitionRepository.DmnDecisionMeta::getId)
            .toList();
        if (!dmnRowIds.isEmpty()) {
            dmnRepository.deleteAllById(dmnRowIds);
        }
    }

    // ==================== DMN: the path that had NO cap at all ====================

    @Test
    void dmnOverLimit_viaHttp_rejected400DmnTooLarge_beforeTheService() throws Exception {
        String oversized = dmnOfLength(DMN_LIMIT + 1, "a13dmnOver");

        MvcResult result = mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"dmn\":" + mapper.writeValueAsString(oversized) + "}")
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();

        assertEquals(400, result.getResponse().getStatus(),
            "oversized DMN must be 400: " + body(result));
        JsonNode error = readError(result);
        assertEquals("DMN_TOO_LARGE", error.get("code").asText(), body(result));
        assertEquals(DMN_LIMIT, error.get("params").get("maxLength").asInt(), body(result));
        assertEquals(DMN_LIMIT + 1, error.get("params").get("actualLength").asInt(), body(result));
        assertTrue(error.hasNonNull("message") && error.get("message").asText().length() > 0,
            "message must be preserved: " + body(result));

        // rejected at the DTO/binding layer — the DMN service never saw the payload
        verify(dmnService, never()).deploy(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void dmnAtLimit_viaHttp_deploys() throws Exception {
        String atLimit = dmnOfLength(DMN_LIMIT, "a13dmnAt");

        MvcResult result = mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"dmn\":" + mapper.writeValueAsString(atLimit) + "}")
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();

        assertEquals(201, result.getResponse().getStatus(),
            "a DMN of exactly the limit must still deploy: " + body(result));
        JsonNode deployed = readTree(result);
        assertTrue(deployed.isArray() && deployed.size() == 1, body(result));
        assertEquals("a13dmnAt", deployed.get(0).get("id").asText(), body(result));
    }

    @Test
    void dmnOverLimit_serviceDirectCall_rejected400DmnTooLarge() {
        // Direct service caller (batch deploy, internal code) — the DTO cap does not apply there.
        String oversized = dmnOfLength(DMN_LIMIT + 1, "a13dmnSvc");

        assertThatThrownBy(() -> dmnService.deploy(oversized, null))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> {
                ApiException api = (ApiException) e;
                assertEquals(HttpStatus.BAD_REQUEST, api.getStatus());
                assertEquals("DMN_TOO_LARGE", api.getCode());
                assertEquals(DMN_LIMIT, api.getParams().get("maxLength"));
                assertEquals(DMN_LIMIT + 1, api.getParams().get("actualLength"));
            });
    }

    @Test
    void dmnAtLimit_serviceDirectCall_deploys() {
        String atLimit = dmnOfLength(DMN_LIMIT, "a13dmnSvcAt");

        // no exception — the guard is `> limit`, not `>= limit`
        dmnService.deploy(atLimit, null);
    }

    @Test
    void dmnDto_declaresFiveMegabyteCap_onTheDmnField() {
        DeployDmnDTO over = new DeployDmnDTO();
        over.setDmn("x".repeat(DMN_LIMIT + 1));

        Set<ConstraintViolation<DeployDmnDTO>> violations = validator.validate(over);
        assertThat(violations).hasSize(1);
        ConstraintViolation<DeployDmnDTO> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("dmn");
        assertThat(((Size) violation.getConstraintDescriptor().getAnnotation()).max())
            .isEqualTo(DMN_LIMIT);
    }

    @Test
    void dmnDto_acceptsExactlyFiveMegabytes() {
        DeployDmnDTO atLimit = new DeployDmnDTO();
        atLimit.setDmn("x".repeat(DMN_LIMIT));

        assertThat(validator.validate(atLimit)).isEmpty();
    }

    // ==================== Submission: cap exists, but only in the service ====================

    @Test
    void submissionOverLimit_viaHttp_rejected400BpmnTooLarge_beforeTheService() throws Exception {
        String oversized = bpmnOfLength(SUBMISSION_LIMIT + 1, "a13subOver");

        MvcResult result = submit(oversized);

        assertEquals(400, result.getResponse().getStatus(),
            "oversized submission must be 400: " + body(result));
        JsonNode error = readError(result);
        assertEquals("BPMN_TOO_LARGE", error.get("code").asText(), body(result));
        assertEquals(SUBMISSION_LIMIT, error.get("params").get("maxLength").asInt(), body(result));
        assertEquals(SUBMISSION_LIMIT + 1, error.get("params").get("actualLength").asInt(), body(result));

        // rejected at the DTO/binding layer — the submission service never saw the payload
        verify(submissionService, never()).submit(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void submissionAtLimit_viaHttp_accepted() throws Exception {
        String atLimit = bpmnOfLength(SUBMISSION_LIMIT, "a13subAt");

        MvcResult result = submit(atLimit);

        assertEquals(201, result.getResponse().getStatus(),
            "a submission of exactly the limit must be accepted: " + body(result));
        JsonNode created = readTree(result);
        assertTrue(created.hasNonNull("id"), body(result));
        createdSubmissionIds.add(UUID.fromString(created.get("id").asText()));
    }

    @Test
    void submissionDto_declares256KiBCap_onTheBpmnField() {
        SubmitProcessSubmissionDTO over = new SubmitProcessSubmissionDTO();
        over.setBpmn("x".repeat(SUBMISSION_LIMIT + 1));

        Set<ConstraintViolation<SubmitProcessSubmissionDTO>> violations = validator.validate(over);
        assertThat(violations).hasSize(1);
        ConstraintViolation<SubmitProcessSubmissionDTO> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("bpmn");
        assertThat(((Size) violation.getConstraintDescriptor().getAnnotation()).max())
            .isEqualTo(SUBMISSION_LIMIT);
    }

    @Test
    void submissionDto_acceptsExactly256KiB() {
        SubmitProcessSubmissionDTO atLimit = new SubmitProcessSubmissionDTO();
        atLimit.setBpmn("x".repeat(SUBMISSION_LIMIT));

        assertThat(validator.validate(atLimit)).isEmpty();
    }

    // ==================== helpers ====================

    /** A VALID DMN document of exactly {@code length} characters (padded with an XML comment). */
    private static String dmnOfLength(int length, String decisionId) {
        return padToLength(DMN_TEMPLATE, "@@ID@@", decisionId, length);
    }

    /** A VALID BPMN document of exactly {@code length} characters (padded with an XML comment). */
    private static String bpmnOfLength(int length, String key) {
        return padToLength(BPMN_TEMPLATE, "@@KEY@@", key, length);
    }

    private static String padToLength(String template, String idPlaceholder, String id, int length) {
        String base = template.replace(idPlaceholder, id).replace("@@PAD@@", "");
        int padding = length - base.length();
        if (padding < 0) {
            throw new IllegalArgumentException("template is longer than the target length: "
                + base.length() + " > " + length);
        }
        String padded = base.replace("<!---->", "<!--" + "x".repeat(padding) + "-->");
        if (padded.length() != length) {
            throw new IllegalStateException("padding produced " + padded.length() + " chars, wanted " + length);
        }
        return padded;
    }

    private MvcResult submit(String bpmn) throws Exception {
        SubmitProcessSubmissionDTO dto = new SubmitProcessSubmissionDTO();
        dto.setBpmn(bpmn);
        return mockMvc.perform(post("/process-submissions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
            .andReturn();
    }

    private JsonNode readError(MvcResult result) throws Exception {
        return readTree(result);
    }

    private JsonNode readTree(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private String login(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
