package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
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
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REL-21: {@code Idempotency-Key} replay on create-mutations (H2, full chain).
 * Same key + same body → saved bytes back, single effect. Same key + other body → 422.
 * No header → behavior unchanged (duplicates happen as on master).
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class IdempotencyFilterTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;

    private static final String DMN_DISCOUNT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="Definitions_discount" name="discount" namespace="http://camunda.org/schema/1.0/dmn">
          <decision id="discount" name="Discount">
            <decisionTable id="DecisionTable_1" hitPolicy="FIRST">
              <input id="Input_1" label="Category">
                <inputExpression id="InputExpression_1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_1" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_gold">
                <inputEntry id="In_gold"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_gold"><text>20</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    private static final String BPMN_SIMPLE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_e2e" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="KEY" name="KEY" isExecutable="true">
            <bpmn:startEvent id="startEvent" name="startEvent">
              <bpmn:outgoing>flow1</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:sequenceFlow id="flow1" name="flow1" sourceRef="startEvent" targetRef="endEvent" />
            <bpmn:endEvent id="endEvent" name="endEvent">
              <bpmn:incoming>flow1</bpmn:incoming>
            </bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
    }

    private String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerBody(String tag) {
        // Password must satisfy complexity (proven value from RegistrationEndpointIntegrationTest).
        return "{\"username\":\"" + tag + "\",\"password\":\"MyStr0ng!P@ssw0rd\","
            + "\"fullName\":\"" + tag + "\",\"email\":\"" + tag + "@test.com\"}";
    }

    private long countUsers(String username) {
        return userRepository.findAll().stream()
            .filter(u -> username.equals(u.getUsername()))
            .count();
    }

    // ==================== POST /auth/register (public) ====================

    @Test
    void register_sameKeySameBody_replaysSingleEffect() throws Exception {
        String key = UUID.randomUUID().toString();
        String tag = uniq("idem");
        String body = registerBody(tag);

        MvcResult first = mockMvc.perform(post("/auth/register")
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        MvcResult second = mockMvc.perform(post("/auth/register")
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(second.getResponse().getContentAsString())
            .as("replay is byte-identical")
            .isEqualTo(first.getResponse().getContentAsString());
        assertThat(countUsers(tag)).as("single user row, no duplicate effect").isEqualTo(1);
    }

    @Test
    void register_sameKeyOtherBody_422AndFirstStaysReplayable() throws Exception {
        String key = UUID.randomUUID().toString();
        String tagA = uniq("idemA");
        String tagB = uniq("idemB");

        mockMvc.perform(post("/auth/register")
                .header("Idempotency-Key", key)
                .content(registerBody(tagA))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
        mockMvc.perform(post("/auth/register")
                .header("Idempotency-Key", key)
                .content(registerBody(tagB))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is(422));
        // First body still replays (422 did not poison the key).
        MvcResult replay = mockMvc.perform(post("/auth/register")
                .header("Idempotency-Key", key)
                .content(registerBody(tagA))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(countUsers(tagA)).isEqualTo(1);
        assertThat(countUsers(tagB)).as("mismatched body never executed").isZero();
        assertThat(replay.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void register_noHeader_passthroughUnchanged() throws Exception {
        String tag = uniq("nohdr");
        String body = registerBody(tag);

        MvcResult first = mockMvc.perform(post("/auth/register")
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        MvcResult second = mockMvc.perform(post("/auth/register")
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();

        // Master behavior without the header: the duplicate is rejected by REG-domain
        // validation (422 here) — this WO's concern is only that the 422 comes from
        // REG logic, NOT from this filter (our code would be IDEMPOTENCY_KEY_*).
        assertThat(second.getResponse().getStatus())
            .as("duplicate without header rejected by domain logic")
            .isEqualTo(422);
        assertThat(second.getResponse().getContentAsString())
            .as("422 is the REG-domain one, not the filter's")
            .doesNotContain("IDEMPOTENCY_KEY");
    }

    // ==================== POST /process-instances ====================

    @Test
    void processInstances_sameKeySameBody_singleInstance() throws Exception {
        String procKey = uniq("idemproc");
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        MvcResult second = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode firstJson = mapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = mapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.get("id").asText())
            .as("same instance id — no duplicate instance")
            .isEqualTo(firstJson.get("id").asText());
        assertThat(second.getResponse().getContentAsString())
            .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void processInstances_noHeader_executesTwice() throws Exception {
        String procKey = uniq("nohdrproc");
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";

        MvcResult first = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        MvcResult second = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode firstJson = mapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = mapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.get("id").asText())
            .as("without the header every call executes (two instances)")
            .isNotEqualTo(firstJson.get("id").asText());
    }

    // ==================== POST /deployments ====================

    @Test
    void deployments_sameKeySameBody_singleDeployment() throws Exception {
        String key = uniq("idemdpl");
        String decision = uniq("idemdec");
        String bpmn = BPMN_SIMPLE.replace("KEY", key);
        String dmn = DMN_DISCOUNT
            .replace("Definitions_discount", "Definitions_" + decision)
            .replace("id=\"discount\"", "id=\"" + decision + "\"");
        String body = "{\"resources\":["
            + "{\"type\":\"DMN\",\"content\":" + mapper.writeValueAsString(dmn) + "},"
            + "{\"type\":\"BPMN\",\"content\":" + mapper.writeValueAsString(bpmn) + "}]}";
        String idemKey = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", idemKey)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        MvcResult second = mockMvc.perform(post("/deployments")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", idemKey)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode firstJson = mapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = mapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.get("id").asText())
            .as("same deployment id — batch executed once")
            .isEqualTo(firstJson.get("id").asText());
        assertThat(second.getResponse().getContentAsString())
            .isEqualTo(first.getResponse().getContentAsString());
    }

    // ==================== POST /dmn ====================

    @Test
    void dmn_sameKeySameBody_singleVersion() throws Exception {
        String decision = uniq("idemdmn");
        String dmn = DMN_DISCOUNT
            .replace("Definitions_discount", "Definitions_" + decision)
            .replace("id=\"discount\"", "id=\"" + decision + "\"");
        String body = "{\"dmn\":" + mapper.writeValueAsString(dmn) + "}";
        String idemKey = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", idemKey)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        MvcResult second = mockMvc.perform(post("/dmn")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", idemKey)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        // A re-execution would create version 2 — identical bytes prove version 1 served twice.
        assertThat(second.getResponse().getContentAsString())
            .isEqualTo(first.getResponse().getContentAsString());
        assertThat(mapper.readTree(first.getResponse().getContentAsString()).get(0).get("version").asInt())
            .isEqualTo(1);
    }

    // ==================== POST /forms ====================

    @Test
    void forms_sameKeySameBody_singleVersion() throws Exception {
        String formKey = uniq("idemform");
        String body = "{\"key\":\"" + formKey + "\",\"kind\":\"FORM_JS\",\"schema\":\"{\\\"components\\\":[]}\"}";
        String idemKey = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", idemKey)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        MvcResult second = mockMvc.perform(post("/forms")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", idemKey)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode firstJson = mapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = mapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.get("version").asInt())
            .as("same version — form deployed once")
            .isEqualTo(firstJson.get("version").asInt());
        assertThat(second.getResponse().getContentAsString())
            .isEqualTo(first.getResponse().getContentAsString());
    }

    // ==================== WO-REL-21 раунд 2: скоуп replay на credential ====================

    @Test
    void credentialScope_crossUserSameKeyBody_isolated() throws Exception {
        // WO-REL-21 раунд 2: второй юзер с тем же key+body НЕ получает ответ первого —
        // его запрос реально исполняется как новый (другой инстанс).
        // POF: без credential_hash в сравнении второй видит чужой replay (тот же id).
        String procKey = uniq("credproc");
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();

        MvcResult adminCall = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        String adminId = mapper.readTree(adminCall.getResponse().getContentAsString()).get("id").asText();

        String userToken = memberToken(procKey);
        MvcResult userCall = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        String userId = mapper.readTree(userCall.getResponse().getContentAsString()).get("id").asText();

        assertThat(userId)
            .as("чужой credential — не replay: создан свой инстанс")
            .isNotEqualTo(adminId);
    }

    @Test
    void credentialScope_rotatedToken_replaysSameActor() throws Exception {
        // WO-REL-32 F05: тот же actor с ротированным токеном — replay, ОДИН эффект
        // (раньше было два эффекта из-за credential_hash в PK).
        // JWT меняется только по exp/сек — слип гарантирует разные байты токена.
        String procKey = uniq("rotproc");
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        // JWT меняется только по exp/сек: ждём смену секунды настенных часов —
        // реальное условие вместо фиксированных 1100мс (WO-OPS-14).
        long beforeSecond = Instant.now().getEpochSecond();
        await().atMost(java.time.Duration.ofSeconds(5))
            .until(() -> Instant.now().getEpochSecond() != beforeSecond);
        String rotatedToken = loginAndGetToken("admin", "admin");
        assertThat(rotatedToken)
            .as("предпосылка: токены реально разные (exp/сек)")
            .isNotEqualTo(adminToken);

        MvcResult second = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + rotatedToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        String firstId = mapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String secondId = mapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assertThat(secondId)
            .as("WO-REL-32 F05: ротированный токен того же actor — replay, один инстанс")
            .isEqualTo(firstId);
    }

    // ==================== WO-REL-21 раунд 2 F1: cookie-скоуп ====================

    @Test
    void credentialScope_cookieUsers_isolated() throws Exception {
        // WO-REL-21 раунд 2 F1: два разных cookie-юзера, тот же key+body, БЕЗ Bearer
        // вообще — второй НЕ получает replay первого (раньше оба падали в sha256("")).
        String procKey = uniq("cookieproc");
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();

        String cookieA = memberCookie(procKey);
        MvcResult callA = mockMvc.perform(post("/process-instances")
                .cookie(new jakarta.servlet.http.Cookie("zbpm_token", cookieA))
                .header("Idempotency-Key", key)
                .header("Origin", "http://localhost:5173")
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        String idA = mapper.readTree(callA.getResponse().getContentAsString()).get("id").asText();

        String cookieB = memberCookie(procKey);
        MvcResult callB = mockMvc.perform(post("/process-instances")
                .cookie(new jakarta.servlet.http.Cookie("zbpm_token", cookieB))
                .header("Idempotency-Key", key)
                .header("Origin", "http://localhost:5173")
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        String idB = mapper.readTree(callB.getResponse().getContentAsString()).get("id").asText();

        assertThat(idB)
            .as("второй cookie-юзер исполнен заново, не replay первого")
            .isNotEqualTo(idA);
    }

    // ==================== helpers (proven shapes) ====================

    /**
     * Второй юзер (USER) с OWNER-мемберством на процесс — второй валидный токен
     * другого юзера для credential-тестов (паттерн AuditLogIntegrationTest).
     */
    private String memberToken(String processKey) throws Exception {
        String username = uniq("idemmember");
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        UUID userId = userRepository.save(user).getId();
        mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"role\":\"OWNER\"}"))
            .andExpect(status().isOk());
        return loginAndGetToken(username, "pass");
    }

    /**
     * То же, но возвращает `zbpm_token`-cookie вместо Bearer-токена (паттерн
     * CookieAuthIntegrationTest: cookie из Set-Cookie ответа логина).
     */
    private String memberCookie(String processKey) throws Exception {
        String username = uniq("idemcookie");
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        UUID userId = userRepository.save(user).getId();
        mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"role\":\"OWNER\"}"))
            .andExpect(status().isOk());
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword("pass");
        MvcResult login = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        String setCookie = login.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).as("login sets zbpm_token cookie").contains("zbpm_token=");
        return setCookie.split("zbpm_token=")[1].split(";")[0];
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

    private void deployProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        bpmn = bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                   .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                   .replace("process id=\"process1\"", "process id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }
}
