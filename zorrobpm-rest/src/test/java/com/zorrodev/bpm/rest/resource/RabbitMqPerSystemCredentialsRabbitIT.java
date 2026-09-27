package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.Tag;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-INT-9 (критерии 2/3/5/6/7): per-system RabbitMQ-креды против РЕАЛЬНОГО
 * брокера. Доказательство — настоящее AMQP-подключение новыми кредами
 * ({@code basicGet} на своей/запрещённой очереди), не чтение permissions
 * через Management API (то было бы проверкой ответа, а не права).
 *
 * <p>Форма:
 * <ul>
 *   <li>criterion2: provisioned + член процесса A → basicGet на
 *       {@code zorrobpm.jobs.&lt;jobA&gt;} разрешён;</li>
 *   <li>criterion3: удаление из A → тот же коннект получает 403 на
 *       {@code zorrobpm.jobs.&lt;jobA&gt;} (POF-мутант «sync пушит старый
 *       набор» ловится ровно здесь);</li>
 *   <li>criterion5: ротация → старый пароль мёртв, новый работает, скоуп тот же;</li>
 *   <li>criterion6: общий {@code zorrodev} жив и нетронут (regression);</li>
 *   <li>дополнительно: скоуп не шире членства (чужая очередь 403) + union
 *       двух процессов + DLQ-доступ (V10-c: {@code zorrobpm.jobs.dlx} живой).</li>
 * </ul>
 *
 * <p>Прогон (P-23: env, не -D):
 * <pre>
 * RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT=5679 RABBITMQ_USER=zorrodev \
 * RABBITMQ_PASSWORD=zorrodev RABBITMQ_MGMT_BASE_URL=http://127.0.0.1:15679 \
 * mvn ... -Dgroups=rabbit -Dzbpm.excludedGroups=
 * </pre>
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqPerSystemCredentialsRabbitIT {

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        // AMQP-коннект приложения — тоже на тестовый брокер (declare очередей
        // идёт под admin-кредами app, как в проде через JobQueueDeclarer),
        // management — для провижининга под test.
        // P-23: env, не -D — не подменяем чужие -D, читаем то же окружение,
        // что видит вилка (env наследуются надёжно, -D — не всегда).
        registry.add("spring.rabbitmq.host", RabbitMqPerSystemCredentialsRabbitIT::amqpHostProp);
        registry.add("spring.rabbitmq.port", RabbitMqPerSystemCredentialsRabbitIT::amqpPortProp);
        registry.add("spring.rabbitmq.username", () -> cfg("RABBITMQ_USER", "zorrodev"));
        registry.add("spring.rabbitmq.password", () -> cfg("RABBITMQ_PASSWORD", "zorrodev"));
        registry.add("zorrobpm.rabbitmq.management.base-url",
            () -> cfg("RABBITMQ_MGMT_BASE_URL", "http://localhost:15672"));
    }

    private static String amqpHostProp() {
        return cfg("RABBITMQ_HOST", "localhost");
    }

    private static int amqpPortProp() {
        return Integer.parseInt(cfg("RABBITMQ_PORT", "5672"));
    }

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return v != null ? v : dflt;
    }

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String amqpHost() {
        return cfg("RABBITMQ_HOST", "localhost");
    }

    private int amqpPort() {
        return Integer.parseInt(cfg("RABBITMQ_PORT", "5672"));
    }

    /** Fresh AMQP-коннект указанными кредами — каждое доказательство на новом. */
    private Connection connect(String login, String password) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(amqpHost());
        factory.setPort(amqpPort());
        factory.setUsername(login);
        factory.setPassword(password);
        factory.setConnectionTimeout(10_000);
        return factory.newConnection("int9-proof");
    }

    /** basicGet на очереди: true = доступ есть, false = 403 ACCESS_REFUSED. */
    private boolean canTouch(Connection conn, String queue) throws Exception {
        // passive-declare (queueDeclarePassive) НЕ годится как read-проба:
        // живой прогон показал, что брокер отвечает на него без проверки
        // vhost-прав (client видит очередь, которой по permissions нет).
        // Настоящая read-проба — basicGet: consume-требует read-прав и
        // падает 403 ACCESS_REFUSED, если их нет. 403 ЗАКРЫВАЕТ канал
        // (брокер шлёт channel.close) — try-with-resources на закрытом канале
        // кидает AlreadyClosedException из close(), поэтому канал закрываем
        // через abort() (без handshake), а 403 ловим и мапим в false.
        Channel ch = conn.createChannel();
        try {
            ch.basicGet(queue, true);
            return true;
        } catch (java.io.IOException e) {
            if (isAccessRefused(e)) return false;
            throw e;
        } catch (com.rabbitmq.client.AlreadyClosedException e) {
            // channel.close уже прилетел раньше, чем basicGet вернул управление
            // (тот же 403 — текст несёт reply-code=403/ACCESS_REFUSED).
            if (isAccessRefused(e)) return false;
            throw e;
        } finally {
            try {
                ch.abort();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isAccessRefused(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof com.rabbitmq.client.ShutdownSignalException sse
                && sse.getMessage() != null
                && sse.getMessage().contains("ACCESS_REFUSED")) {
                return true;
            }
            if (c.getMessage() != null && c.getMessage().contains("ACCESS_REFUSED")) {
                return true;
            }
        }
        return false;
    }

    // ==================== Критерии 2+3: grant + revoke реальным AMQP ====================

    @Test
    void criterion2_grantedQueue_reachableOverAmqp() throws Exception {
        Fixture f = setupTwoProcesses();
        try (Connection conn = connect(f.login, f.password)) {
            assertThat(canTouch(conn, "zorrobpm.jobs." + f.jobA))
                .as("member process queue must be reachable with the system credentials").isTrue();
        }
    }

    @Test
    void criterion3_revokedQueue_refusedOverAmqp() throws Exception {
        Fixture f = setupTwoProcesses();
        // Удаление из A (B остаётся — синк обязан сузить, не отозвать всё).
        mockMvc.perform(delete("/processes/" + f.procA + "/members/" + f.userId)
                        .header("Authorization", "Bearer " + f.superAdminToken))
                .andExpect(status().isOk());

        try (Connection conn = connect(f.login, f.password)) {
            assertThat(canTouch(conn, "zorrobpm.jobs." + f.jobA))
                .as("revoked process queue must be ACCESS_REFUSED on a fresh connection").isFalse();
            assertThat(canTouch(conn, "zorrobpm.jobs." + f.jobB))
                .as("remaining process queue must stay reachable").isTrue();
        }
    }

    @Test
    void scopeIsNotWiderThanMembership_foreignQueueRefused() throws Exception {
        Fixture f = setupTwoProcesses();
        String foreignJob = "int9foreign" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String foreignQueue = "zorrobpm.jobs." + foreignJob;
        // Чужая очередь существует (объявлена admin-коннектом), но юзер
        // не член её процесса — прав быть не должно.
        try (Connection admin = connect(cfg("RABBITMQ_USER", "zorrodev"), cfg("RABBITMQ_PASSWORD", "zorrodev"));
             Channel ch = admin.createChannel()) {
            ch.queueDeclare(foreignQueue, true, false, false, null);
        }
        try (Connection conn = connect(f.login, f.password)) {
            assertThat(canTouch(conn, foreignQueue))
                .as("a queue outside membership must be ACCESS_REFUSED").isFalse();
        } finally {
            try (Connection admin = connect(cfg("RABBITMQ_USER", "zorrodev"), cfg("RABBITMQ_PASSWORD", "zorrodev"));
                 Channel ch = admin.createChannel()) {
                ch.queueDelete(foreignQueue);
            }
        }
    }

    @Test
    void dlq_ofMemberQueue_isReachable() throws Exception {
        Fixture f = setupTwoProcesses();
        // V10-c: read покрывает и DLQ (consume/reject-циклы воркера).
        // Очередь + DLQ объявлены app-коннектом при деплое (JobQueueDeclarer).
        try (Connection conn = connect(f.login, f.password)) {
            assertThat(canTouch(conn, "zorrobpm.jobs." + f.jobA + ".dlq"))
                .as("DLQ of the member queue must be reachable").isTrue();
        }
    }

    // ==================== Критерий 5: ротация ====================

    @Test
    void criterion5_rotation_oldPasswordDead_newPasswordSameScope() throws Exception {
        Fixture f = setupTwoProcesses();

        MvcResult rotated = mockMvc.perform(post("/admin/users/" + f.userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + f.superAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        String newPassword = mapper.readTree(rotated.getResponse().getContentAsString())
            .get("password").asText();
        assertThat(newPassword).isNotEqualTo(f.password);

        // Старый пароль мёртв — коннект не устанавливается вообще.
        boolean oldRejected;
        try (Connection ignored = connect(f.login, f.password)) {
            oldRejected = false;
        } catch (Exception e) {
            oldRejected = true;
        }
        assertThat(oldRejected).as("old password must be rejected after rotation").isTrue();

        // Новый — работает, скоуп тот же.
        try (Connection conn = connect(f.login, newPassword)) {
            assertThat(canTouch(conn, "zorrobpm.jobs." + f.jobA)).isTrue();
            assertThat(canTouch(conn, "zorrobpm.jobs." + f.jobB)).isTrue();
        }
    }

    // ==================== Критерий 6: общий zorrodev нетронут ====================

    @Test
    void criterion6_sharedAccount_stillWorks() throws Exception {
        // Regression: общий admin-аккаунт жив — declare + publish + consume.
        String probe = "int9shared" + UUID.randomUUID().toString().substring(0, 8);
        String queue = "zorrobpm.jobs." + probe;
        try (Connection conn = connect(cfg("RABBITMQ_USER", "zorrodev"), cfg("RABBITMQ_PASSWORD", "zorrodev"));
             Channel ch = conn.createChannel()) {
            ch.queueDeclare(queue, true, false, false, null);
            byte[] body = ("shared-ok-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
            ch.basicPublish("", queue, null, body);
            GetResponse got = ch.basicGet(queue, true);
            assertThat(got).as("shared account round-trip must work").isNotNull();
            assertThat(got.getBody()).isEqualTo(body);
            ch.queueDelete(queue);
        }
    }

    // ==================== Union: обе очереди двух процессов ====================

    @Test
    void union_twoProcesses_bothQueuesReachable() throws Exception {
        Fixture f = setupTwoProcesses();
        try (Connection conn = connect(f.login, f.password)) {
            assertThat(canTouch(conn, "zorrobpm.jobs." + f.jobA)).isTrue();
            assertThat(canTouch(conn, "zorrobpm.jobs." + f.jobB)).isTrue();
        }
    }

    // ==================== Fixture ====================

    private record Fixture(String login, String password, UUID userId,
                           String procA, String procB, String jobA, String jobB,
                           String superAdminToken) {
    }

    private Fixture setupTwoProcesses() throws Exception {
        String superAdminToken = loginAndGetToken("admin", "admin");
        String uniq = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String login = ("int9rbt" + uniq).toLowerCase();
        UUID userId = createSystemUser(login);
        String jobA = ("int9ra" + uniq).toLowerCase();
        String jobB = ("int9rb" + uniq).toLowerCase();
        String procA = deployJobProcess("int9-rbt-a", jobA, superAdminToken);
        String procB = deployJobProcess("int9-rbt-b", jobB, superAdminToken);

        // Очереди + DLQ объявляет app (admin-креды), как JobQueueDeclarer при
        // деплое — воркер свои declare не делает (V10-c).
        ensureJobQueue(jobA);
        ensureJobQueue(jobB);

        // Provision → членство в обоих → права пушатся хуками.
        // Тело ответа логируем при неуспехе: 503 здесь = текст причины
        // (брокер/mgmt недоступен), а не молчаливый статус.
        MvcResult provisioned = mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andReturn();
        assertThat(provisioned.getResponse().getStatus())
            .as("provision failed: " + provisioned.getResponse().getContentAsString())
            .isEqualTo(200);
        String password = mapper.readTree(provisioned.getResponse().getContentAsString())
            .get("password").asText();
        addMember(procA, userId, "DESIGNER", superAdminToken);
        addMember(procB, userId, "DESIGNER", superAdminToken);
        return new Fixture(login, password, userId, procA, procB, jobA, jobB, superAdminToken);
    }

    private void ensureJobQueue(String jobType) throws Exception {
        try (Connection admin = connect(cfg("RABBITMQ_USER", "zorrodev"), cfg("RABBITMQ_PASSWORD", "zorrodev"));
             Channel ch = admin.createChannel()) {
            String queue = "zorrobpm.jobs." + jobType;
            String dlq = queue + ".dlq";
            ch.exchangeDeclare("zorrobpm.jobs.dlx", "direct", true);
            ch.queueDeclare(dlq, true, false, false, null);
            ch.queueBind(dlq, "zorrobpm.jobs.dlx", dlq);
            java.util.Map<String, Object> args = new java.util.HashMap<>();
            args.put("x-dead-letter-exchange", "zorrobpm.jobs.dlx");
            args.put("x-dead-letter-routing-key", dlq);
            ch.queueDeclare(queue, true, false, false, args);
        }
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

    private UUID createSystemUser(String login) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(login);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(login);
        user.setRole("USER");
        user.setActive(true);
        user.setUserType("SYSTEM");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private String deployJobProcess(String keyPrefix, String jobType, String token) throws Exception {
        String key = (keyPrefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toLowerCase();
        String bpmn = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("src/test/files/sec43-process.bpmn")));
        bpmn = bpmn.replace("id=\"sec43-process\"", "id=\"" + key + "\"")
            .replace("name=\"SEC43 Process\"", "name=\"" + key + "\"")
            .replace("bpmnElement=\"sec43-process\"", "bpmnElement=\"" + key + "\"")
            .replace("type=\"serviceTask1\"", "type=\"" + jobType + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        return key;
    }

    private void addMember(String processKey, UUID userId, String role, String token) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + token)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
