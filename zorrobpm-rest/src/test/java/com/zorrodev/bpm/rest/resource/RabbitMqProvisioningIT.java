package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-INT-9 (критерии 1/4/5 + guards + no-op + shape 2/3 на H2):
 * per-system RabbitMQ-креды через ПОЛНУЮ цепочку (MockMvc → реальный
 * фильтр → ресурс → реальный сервис → stub Management API, V11).
 * Management API — in-JVM stub на случайном порту (тот же контракт:
 * {@code PUT /api/users/{login}} + {@code PUT /api/permissions/{vhost}/{login}}),
 * поэтому тест гоняет настоящий HTTP-путь сервиса (G-N), а не замоканный бин.
 *
 * <p>Настоящие AMQP-доказательства (реальное подключение новыми кредами:
 * может/не может тронуть очередь) — в
 * {@code RabbitMqPerSystemCredentialsRabbitIT} (@Tag("rabbit"), живой брокер):
 * H2 не умеет в broker-permissions, там это было бы стендом, а не проверкой.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqProvisioningIT {

    /** Креды stub-админа брокера — сервис ходит с ними (Basic auth проверяется). */
    private static final String MGMT_USER = "mgmt-admin";
    private static final String MGMT_PASS = "mgmt-pass-int9";

    private static HttpServer mgmtStub;
    private static String mgmtBaseUrl;
    private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();

    record RecordedRequest(String method, String path, String body, String authHeader) {
    }

    @BeforeAll
    static void startMgmtStub() throws IOException {
        ensureMgmtStub();
    }

    @AfterAll
    static void stopMgmtStub() {
        if (mgmtStub != null) mgmtStub.stop(0);
    }

    @DynamicPropertySource
    static void mgmtProperties(DynamicPropertyRegistry registry) throws IOException {
        // Supplier вызывается при сборке контекста — ДО @BeforeAll: stub
        // поднимается лениво здесь же (тот же грабель, что порядок
        // prepareTestInstance vs BeforeAll в SpringExtension).
        ensureMgmtStub();
        registry.add("zorrobpm.rabbitmq.management.base-url", () -> mgmtBaseUrl);
        registry.add("spring.rabbitmq.username", () -> MGMT_USER);
        registry.add("spring.rabbitmq.password", () -> MGMT_PASS);
    }

    private static synchronized void ensureMgmtStub() throws IOException {
        if (mgmtStub != null) return;
        mgmtStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mgmtStub.createContext("/", RabbitMqProvisioningIT::handleMgmt);
        mgmtStub.start();
        mgmtBaseUrl = "http://127.0.0.1:" + mgmtStub.getAddress().getPort();
    }

    private static void handleMgmt(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
            (MGMT_USER + ":" + MGMT_PASS).getBytes(StandardCharsets.UTF_8));
        byte[] response;
        int status;
        if (!expected.equals(auth)) {
            status = 401;
            response = "auth required".getBytes(StandardCharsets.UTF_8);
        } else if (!"PUT".equals(exchange.getRequestMethod())) {
            status = 405;
            response = "method not allowed".getBytes(StandardCharsets.UTF_8);
        } else {
            REQUESTS.add(new RecordedRequest(
                exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), body, auth));
            status = 201;
            response = "{}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String superAdminToken;

    @BeforeAll
    void login() throws Exception {
        superAdminToken = loginAndGetToken("admin", "admin");
    }

    // ==================== Критерий 1: provision SYSTEM-юзера ====================

    @Test
    void criterion1_provisionSystemUser_createsBrokerAccount_returnsOneTimePassword() throws Exception {
        String login = "int9-sys-" + UUID.randomUUID().toString().substring(0, 8);
        UUID userId = createUser(login, "SYSTEM");
        int before = REQUESTS.size();

        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        String password = body.get("password").asText();
        assertThat(password).as("one-time password must be returned").hasSize(40);

        // Ответ — ТОЛЬКО пароль (никаких лишних полей/секретов рядом).
        assertThat(body.size()).as("response carries only the password").isEqualTo(1);

        // Stub видел PUT /api/users/{login} с password_hash (НЕ plaintext) и пустыми тегами.
        List<RecordedRequest> fresh = REQUESTS.subList(before, REQUESTS.size());
        RecordedRequest putUser = fresh.stream()
            .filter(r -> r.path().equals("/api/users/" + login))
            .findFirst().orElseThrow(() -> new AssertionError("no PUT /api/users/" + login));
        JsonNode putBody = mapper.readTree(putUser.body());
        assertThat(putBody.get("password_hash").asText())
            .as("broker receives a hash, never the plaintext password")
            .isNotBlank()
            .isNotEqualTo(password);
        assertThat(putBody.get("tags").asText())
            .as("no management/administrator tags").isEmpty();
        assertThat(putUser.authHeader()).as("service authenticates as the broker admin").isNotBlank();

        // DB-флаг выставлен — источник истины для no-op правила.
        assertThat(userRepository.findById(userId).orElseThrow().isRabbitmqProvisioned())
            .as("rabbitmqProvisioned flag must be set").isTrue();

        // Показ один раз: повторная генерация — ДРУГОЙ пароль (старый неизвестен никому).
        MvcResult second = mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(second.getResponse().getContentAsString()).get("password").asText())
            .as("rotation must yield a fresh password").isNotEqualTo(password);
    }

    // ==================== Guards: HUMAN / чужой / не-SUPER_ADMIN ====================

    @Test
    void humanUser_rejected400() throws Exception {
        UUID userId = createUser("int9-human-" + UUID.randomUUID().toString().substring(0, 8), "HUMAN");
        mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.findById(userId).orElseThrow().isRabbitmqProvisioned()).isFalse();
    }

    @Test
    void unknownUser_rejected404() throws Exception {
        mockMvc.perform(post("/admin/users/" + UUID.randomUUID() + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void reservedBrokerAdminLogin_rejected409() throws Exception {
        // Verifier HOLD #1 (red-team): SYSTEM-юзер с логином брокер-админа —
        // provision обязан отказать, а не снести administrator-аккаунт.
        // Этот класс подменяет brokerAdminUser через @DynamicPropertySource
        // на MGMT_USER — guard сравнивает именно с ним (юнит фиксирует дефолт
        // `zorrodev`, здесь — wiring против живого значения).
        String adminLogin = MGMT_USER;
        UUID userId = createUser(adminLogin + "-" + UUID.randomUUID().toString().substring(0, 4), "SYSTEM");
        // Переименовываем в точный reserved-логин напрямую через репозиторий
        // (createUser гарантирует уникальность суффиксом выше).
        var entity = userRepository.findById(userId).orElseThrow();
        entity.setUsername(adminLogin);
        try {
            userRepository.save(entity);
        } catch (Exception e) {
            // Если такой логин уже занят seeded-данными — тест неприменим,
            // пропускаем честно (не фейк-POF: guard доказан юнитом выше).
            org.junit.jupiter.api.Assumptions.abort("reserved login already taken in seed data");
            return;
        }
        // Seed мог содержать этот логин под другим id — тогда save создал бы
        // дубль вместо переименования; сверяем, что переименовались мы.
        var renamed = userRepository.findById(userId).orElseThrow();
        org.junit.jupiter.api.Assumptions.assumeTrue(adminLogin.equals(renamed.getUsername()),
            "reserved login already taken in seed data");
        int before = REQUESTS.size();
        mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isConflict());
        assertThat(REQUESTS).as("reserved login must not reach the broker").hasSize(before);
        assertThat(userRepository.findById(userId).orElseThrow().isRabbitmqProvisioned()).isFalse();
    }

    @Test
    void nonSuperAdmin_forbidden403() throws Exception {
        UUID sysId = createUser("int9-sys403-" + UUID.randomUUID().toString().substring(0, 8), "SYSTEM");
        UUID plainId = createUser("int9-plain-" + UUID.randomUUID().toString().substring(0, 8), "USER");
        String plainToken = loginAndGetToken(
            userRepository.findById(plainId).orElseThrow().getUsername(), "pass");
        // G-H red-team: обычный (не admin) вызывающий НЕ может сгенерировать
        // пароль чужому юзеру — deny-by-default, тот же requireSuperAdmin.
        mockMvc.perform(post("/admin/users/" + sysId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
        assertThat(userRepository.findById(sysId).orElseThrow().isRabbitmqProvisioned()).isFalse();
    }

    // ==================== Критерий 4: no-op без пароля ====================

    @Test
    void criterion4_neverProvisioned_membershipIsBrokerNoop() throws Exception {
        String login = "int9-noop-" + UUID.randomUUID().toString().substring(0, 8);
        UUID userId = createUser(login, "SYSTEM");
        String processKey = deployJobProcess("int9-noop-proc", "int9noopjob");
        int before = REQUESTS.size();

        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"OWNER\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        assertThat(REQUESTS).as("no broker calls for a never-provisioned user").hasSize(before);
        assertThat(userRepository.findById(userId).orElseThrow().isRabbitmqProvisioned()).isFalse();
    }

    // ==================== Хуки: addMember пушит, removeMember сужает ====================

    @Test
    void addMember_provisionedUser_pushesFullPermissions() throws Exception {
        String login = "int9-hook-" + UUID.randomUUID().toString().substring(0, 8);
        UUID userId = createUser(login, "SYSTEM");
        String job = "int9hookjob" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String processKey = deployJobProcess("int9-hook-proc", job);

        provision(userId);
        int before = REQUESTS.size();

        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"OWNER\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        RecordedRequest permPut = lastPermissionsPut(before);
        // Vhost "/" URL-кодируется в %2F — сверяем по декодированному пути.
        assertThat(URLDecoder.decode(permPut.path(), StandardCharsets.UTF_8))
            .isEqualTo("/api/permissions///" + login);
        JsonNode perms = mapper.readTree(permPut.body());
        assertThat(perms.get("read").asText())
            .as("read must cover the member process queue")
            .contains("(" + job + ")");
        assertThat(perms.get("write").asText())
            .as("write must cover the member process queue + completions")
            .contains("(" + job + ")")
            .contains("complete-service-task");
        assertThat(perms.get("configure").asText())
            .as("configure must cover the member process queue + DLQ")
            .contains("(" + job + ")");
    }

    @Test
    void removeMember_provisionedUser_narrowsPermissionsToRemaining() throws Exception {
        String login = "int9-narrow-" + UUID.randomUUID().toString().substring(0, 8);
        UUID userId = createUser(login, "SYSTEM");
        String jobA = "int9jobA" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String jobB = "int9jobB" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String procA = deployJobProcess("int9-narrow-proc-a", jobA);
        String procB = deployJobProcess("int9-narrow-proc-b", jobB);

        provision(userId);
        // Роль DESIGNER: OWNER-триггерил бы last-OWNER guard на removeMember
        // (деплоящий superAdmin в этом пути не член процесса); broker-права
        // от роли не зависят — синк-путь тот же.
        addMemberAs(procA, userId, "DESIGNER");
        addMemberAs(procB, userId, "DESIGNER");
        int before = REQUESTS.size();

        mockMvc.perform(delete("/processes/" + procA + "/members/" + userId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        RecordedRequest permPut = lastPermissionsPut(before);
        JsonNode perms = mapper.readTree(permPut.body());
        assertThat(perms.get("read").asText())
            .as("removed process queue must be gone from read")
            .doesNotContain(jobA)
            .contains("(" + jobB + ")");
        assertThat(perms.get("write").asText())
            .doesNotContain(jobA)
            .contains("(" + jobB + ")");
    }

    // ==================== Критерий 5: ротация не трогает permissions ====================

    @Test
    void criterion5_rotation_keepsPermissions() throws Exception {
        String login = "int9-rot-" + UUID.randomUUID().toString().substring(0, 8);
        UUID userId = createUser(login, "SYSTEM");
        String job = "int9rotjob" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String processKey = deployJobProcess("int9-rot-proc", job);
        provision(userId);
        addMember(processKey, userId);

        int before = REQUESTS.size();
        MvcResult rotated = mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(rotated.getResponse().getContentAsString()).get("password").asText())
            .isNotBlank();

        // Ротация = только PUT /api/users с новым hash; permissions НЕ пушатся.
        List<RecordedRequest> fresh = REQUESTS.subList(before, REQUESTS.size());
        assertThat(fresh).as("rotation touches only the user entry").hasSize(1);
        assertThat(fresh.get(0).path()).isEqualTo("/api/users/" + login);
        assertThat(mapper.readTree(fresh.get(0).body()).has("password_hash")).isTrue();
    }

    // ==================== Helpers ====================

    private RecordedRequest lastPermissionsPut(int before) {
        List<RecordedRequest> fresh = REQUESTS.subList(before, REQUESTS.size());
        ConcurrentLinkedQueue<RecordedRequest> found = new ConcurrentLinkedQueue<>();
        for (RecordedRequest r : fresh) {
            if (r.path().startsWith("/api/permissions/")) found.add(r);
        }
        assertThat(found).as("expected a PUT /api/permissions after membership change").isNotEmpty();
        RecordedRequest last = null;
        for (RecordedRequest r : found) last = r;
        return last;
    }

    private void provision(UUID userId) throws Exception {
        mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
    }

    private void addMember(String processKey, UUID userId) throws Exception {
        addMemberAs(processKey, userId, "OWNER");
    }

    private void addMemberAs(String processKey, UUID userId, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
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

    private UUID createUser(String username, String userType) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setUserType(userType);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private String deployJobProcess(String keyPrefix, String jobType) throws Exception {
        String key = (keyPrefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toLowerCase();
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/sec43-process.bpmn")));
        bpmn = bpmn.replace("id=\"sec43-process\"", "id=\"" + key + "\"")
            .replace("name=\"SEC43 Process\"", "name=\"" + key + "\"")
            .replace("bpmnElement=\"sec43-process\"", "bpmnElement=\"" + key + "\"")
            .replace("type=\"serviceTask1\"", "type=\"" + jobType + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        return key;
    }
}
