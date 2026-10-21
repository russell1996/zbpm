package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-AUDIT-17 (аудит F-21): политика DMN evaluate — «чтение определения».
 *
 * <p>Решение maintainer 2026-10-21 ({@code docs/internal},
 * раздел F-21): evaluate — ЧИСТАЯ ФУНКЦИЯ над переданными переменными; побочных
 * эффектов нет, данных инстансов evaluate не читает и не пишет. Видимость
 * определений регулирует ADR-8 §1 ({@code visibleDefinitionIds}): любой
 * аутентифицированный пользователь видит ВСЕ определения. Для
 * {@code ServicePrincipal} видимость остаётся grant-scoped (тот же резолвер, что
 * {@code readableRuntimePdIds}, — для сервисного ключа обе ветки ADR-8 дают одно
 * и то же множество).
 *
 * <p>Этот тест ЗАКРЕПЛЯЕТ выбранную политику (поведение не менялось и не меняется):
 * <ul>
 *   <li>обычный аутентифицированный пользователь, НЕ участник процесса, → 200 + результат
 *       (ADR-8 §1: «видит все определения»);</li>
 *   <li>{@code ServicePrincipal} без гранта на процесс-владелец решения → 404
 *       (существование решения скрыто);</li>
 *   <li>оценка не зависит от данных живых инстансов и не меняет их.</li>
 * </ul>
 *
 * <p>POF (G-K, G-N): мутация прод-кода {@code DmnResource.resolveAllowedPdIds} —
 * подмена видимости {@code visibleDefinitionIds} → {@code readableRuntimePdIds}
 * (та самая «runtime-семантика», которую maintainer отверг) — роняет тесты с обычным
 * пользователем: 404 вместо 200. Мутация снимает гейт авторизации — роняет тест
 * с {@code ServicePrincipal} без гранта. Мутация «evaluate игнорирует переданные
 * переменные» — роняет тест на результат.
 *
 * <p>Уникальные id процесса и решения на каждый прогон: DMN-строки коммитятся
 * глобально (P-8, как в {@code DmnStartParityTest}), а «последняя версия» решения
 * ищется по {@code decisionId} — фиксированный id увидел бы чужой деплой.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class DmnEvaluatePolicyIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String DECISION_ID = "audit17-discount-" + SUFFIX;
    private static final String PROC_KEY = "audit17-proc-" + SUFFIX;
    private static final String OTHER_PROC_KEY = "audit17-other-" + SUFFIX;

    private String adminToken;
    private String plainUserToken;      // UserPrincipal, НЕ участник процесса
    private String serviceKeyWithGrant; // ServicePrincipal, грант на PROC_KEY
    private String serviceKeyOtherProc; // ServicePrincipal, грант на ДРУГОЙ процесс
    private UUID pdId;

    /** DMN: единственное правило — «gold» → 20. Всё остальное — без совпадения (null). */
    private static final String DMN_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="Definitions_audit17" name="audit17" namespace="http://zorro.io/dmn/audit17">
          <decision id="__DECISION__" name="Discount">
            <decisionTable id="DecisionTable_audit17" hitPolicy="FIRST">
              <input id="Input_audit17" label="Category">
                <inputExpression id="InputExpression_audit17" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_audit17" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_gold">
                <inputEntry id="In_gold"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_gold"><text>20</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    /** BPMN: start → userTask → end. Инстанс остаётся живым (есть открытая задача). */
    private static final String BPMN_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Defs_audit17" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="__KEY__" name="__KEY__" isExecutable="true">
            <bpmn:startEvent id="startEvent" name="startEvent">
              <bpmn:outgoing>flow1</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="userTask1" name="User Task">
              <bpmn:incoming>flow1</bpmn:incoming>
              <bpmn:outgoing>flow2</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="endEvent" name="endEvent">
              <bpmn:incoming>flow2</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flow1" name="flow1" sourceRef="startEvent" targetRef="userTask1" />
            <bpmn:sequenceFlow id="flow2" name="flow2" sourceRef="userTask1" targetRef="endEvent" />
          </bpmn:process>
        </bpmn:definitions>
        """;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");

        pdId = deployProcess(PROC_KEY);
        deployProcess(OTHER_PROC_KEY);

        // Решение привязано к определению PROC_KEY — «владелец» решения для авторизации.
        deployDmn(DMN_TEMPLATE.replace("__DECISION__", DECISION_ID), pdId);

        // 1) обычный пользователь, НЕ участник ни одного из процессов
        String plainUserName = "audit17-plain-" + UUID.randomUUID();
        createUser(plainUserName, "USER");
        plainUserToken = loginAndGetToken(plainUserName, "pass");

        // 2) сервисный ключ с грантом на процесс-владелец решения
        UUID svcUserId = createUser("audit17-svc-" + UUID.randomUUID(), "USER");
        addMember(svcUserId, PROC_KEY, "OWNER");
        serviceKeyWithGrant = createApiKeyForUser(svcUserId);
        setGrants(svcUserId, PROC_KEY, "START");

        // 3) сервисный ключ с грантом на ДРУГОЙ процесс — решения не касается
        UUID otherUserId = createUser("audit17-other-" + UUID.randomUUID(), "USER");
        addMember(otherUserId, OTHER_PROC_KEY, "OWNER");
        serviceKeyOtherProc = createApiKeyForUser(otherUserId);
        setGrants(otherUserId, OTHER_PROC_KEY, "START");
    }

    // ==================== ADR-8 §1: определения видит любой аутентифицированный ====================

    @Test
    void evaluate_asRegularUserNotMemberOfProcess_returnsDecisionResult() throws Exception {
        MvcResult result = mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .header("Authorization", "Bearer " + plainUserToken)
                .content("{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("result").asInt())
            .as("ADR-8 §1: пользователь, не участник процесса, всё равно читает ОПРЕДЕЛЕНИЕ и оценивает его")
            .isEqualTo(20);
    }

    @Test
    void evaluate_asRegularUserNotMemberOfProcess_resultIsSameAsSuperAdmin() throws Exception {
        String body = "{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}";
        MvcResult asUser = mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .header("Authorization", "Bearer " + plainUserToken)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();
        MvcResult asAdmin = mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .header("Authorization", "Bearer " + adminToken)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();
        assertThat(asUser.getResponse().getContentAsString())
            .as("оценка определения не зависит от того, кто её читает")
            .isEqualTo(asAdmin.getResponse().getContentAsString());
    }

    @Test
    void evaluate_asSuperAdmin_returnsDecisionResult() throws Exception {
        MvcResult result = mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("result").asInt())
            .isEqualTo(20);
    }

    @Test
    void evaluate_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .content("{\"variables\":[]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    // ==================== ServicePrincipal: видимость остаётся grant-scoped ====================

    @Test
    void evaluate_asServicePrincipalWithGrantOnOwningProcess_returnsDecisionResult() throws Exception {
        MvcResult result = mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .header("Authorization", "Bearer " + serviceKeyWithGrant)
                .content("{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("result").asInt())
            .as("у ключа есть грант на процесс-владелец решения")
            .isEqualTo(20);
    }

    @Test
    void evaluate_asServicePrincipalWithoutGrantOnOwningProcess_returns404() throws Exception {
        mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .header("Authorization", "Bearer " + serviceKeyOtherProc)
                .content("{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound());
    }

    // ==================== evaluate — чистая функция: данных инстансов не читает и не пишет ====================

    @Test
    void evaluate_usesOnlyPassedVariables_liveInstanceDataIsNotVisible() throws Exception {
        // Живой инстанс с category=gold, у которого открытая задача (инстанс не завершён).
        UUID piId = startInstanceWithCategory("gold");

        // Оценка БЕЗ переменных: значение инстанса (gold) подставляться не должно —
        // правило «gold» не совпадает с отсутствующей переменной, результат — «нет совпадения».
        MvcResult empty = mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(mapper.readTree(empty.getResponse().getContentAsString()).get("result").asText())
            .as("evaluate не подставляет переменные живого инстанса %s", piId)
            .isEmpty();

        // То же решение, но category передана В ЗАПРОСЕ — результат есть.
        MvcResult withGold = mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(mapper.readTree(withGold.getResponse().getContentAsString()).get("result").asInt())
            .as("результат определяется ТОЛЬКО переменными запроса")
            .isEqualTo(20);
    }

    @Test
    void evaluate_doesNotModifyInstanceData() throws Exception {
        UUID piId = startInstanceWithCategory("gold");

        String variablesBefore = instanceVariables(piId);
        String instanceBefore = instanceSnapshot(piId);
        assertThat(variablesBefore).contains("gold");

        mockMvc.perform(post("/dmn/" + DECISION_ID + "/evaluate")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"variables\":[{\"name\":\"category\",\"value\":\"gold\",\"type\":\"STRING\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        assertThat(instanceVariables(piId))
            .as("evaluate не пишет данные инстанса")
            .isEqualTo(variablesBefore);
        assertThat(instanceSnapshot(piId))
            .as("evaluate не меняет состояние инстанса")
            .isEqualTo(instanceBefore);
        // Инстанс остался живым и в нём та же единственная открытая задача — оценка
        // решения не «прокрутила» процесс (G-P: нет побочных эффектов у evaluate).
        assertThat(instanceCompleted(piId)).as("инстанс не завершён").isFalse();
        assertThat(openTaskCount(piId)).as("открытая задача инстанса жива").isEqualTo(1);
    }

    // ==================== Политика опубликована в OpenAPI-описании эндпоинта ====================

    @Test
    void evaluate_openApiDescription_statesThePolicy() throws Exception {
        MvcResult apiDocs = mockMvc.perform(get("/v3/api-docs")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode path = mapper.readTree(apiDocs.getResponse().getContentAsString())
            .get("paths").get("/dmn/{decisionId}/evaluate");
        assertThat(path).as("эндпоинт evaluate есть в OpenAPI").isNotNull();
        String description = path.get("post").get("description").asText();
        assertThat(description)
            .as("описание фиксирует политику evaluate = чтение определения")
            .contains("ADR-8")
            .contains("visibleDefinitionIds");
    }

    // ==================== Helpers (только публичный HTTP API) ====================

    private UUID startInstanceWithCategory(String category) throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of(variable("category", category, "STRING")));
        MvcResult started = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());
    }

    /** Сырые переменные инстанса (как отдаёт API) — для сравнения «до/после». */
    private String instanceVariables(UUID piId) throws Exception {
        MvcResult vars = mockMvc.perform(get("/variables?processInstanceId=" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(vars.getResponse().getContentAsString()).get("data").toString();
    }

    /** Состояние инстанса целиком (в модели нет изменяемого «обновлён в» — только startedAt/completedAt). */
    private String instanceSnapshot(UUID piId) throws Exception {
        MvcResult pi = mockMvc.perform(get("/process-instances/" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(pi.getResponse().getContentAsString()).toString();
    }

    /** Завершён ли инстанс (completedAt заполнен). */
    private boolean instanceCompleted(UUID piId) throws Exception {
        MvcResult pi = mockMvc.perform(get("/process-instances/" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(pi.getResponse().getContentAsString()).hasNonNull("completedAt");
    }

    /** Сколько открытых задач у инстанса. */
    private int openTaskCount(UUID piId) throws Exception {
        MvcResult tasks = mockMvc.perform(get("/user-tasks?processInstanceId=" + piId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(tasks.getResponse().getContentAsString()).get("data").size();
    }

    private static ProcessVariable variable(String name, String value, String type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(com.zorrodev.bpm.contract.model.ProcessVariableType.valueOf(type));
        return v;
    }

    private UUID deployProcess(String key) throws Exception {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(BPMN_TEMPLATE.replace("__KEY__", key));
        MvcResult result = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void deployDmn(String dmn, UUID owningPdId) throws Exception {
        mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"dmn\":" + mapper.writeValueAsString(dmn)
                    + ",\"processDefinitionId\":\"" + owningPdId + "\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }

    private UUID createUser(String username, String role) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private void addMember(UUID userId, String processKey, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    private String createApiKeyForUser(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/api-key")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isCreated())
            .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void setGrants(UUID userId, String processKey, String permissions) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"permissions\":\"" + permissions + "\"}]}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
