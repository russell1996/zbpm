package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ENG-24, критерии 2+3: перегрузка FEEL-пула → HTTP 503 + Retry-After
 * (не 422), метрика {@code zbpm.script.rejected} инкрементится.
 *
 * <p>Full-context IT (V11): реальная цепочка фильтров + прод-конфиг, два
 * принципала не нужны (перегрузка — не authz), но путь — боевой:
 * POST /process-instances → runtime → script-task → общий пул.
 *
 * <p>Детерминированное насыщение БЕЗ изменения прод-конфига: стартуем процесс,
 * чей script-task висит на latch (медленный FEEL через занятый пул тестового
 * контекста доказать сложно — вместо этого пул занимается напрямую через
 * инжектированный {@code ScriptService}: оба воркера пинятся latch-задачами,
 * очередь забивается, и следующий HTTP-старт упирается в admission-wait).
 *
 * <p>Упрощение: занять пул тестового Spring-контекста из самого теста через
 * {@code ScriptService.runWithBudget} — тот же бин и тот же пул, что у
 * HTTP-старта (синглтон контекста). admission-wait прод-дефолта 5с делает тест
 * медленным, но детерминированным: holders держат дольше wait → сброс
 * гарантирован, без гоночных flood-потоков.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class ScriptOverloadMappingIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private com.zorrodev.bpm.engine.service.ScriptService scriptService;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        String bpmn = Files.readString(
            Paths.get("src/test/files/eng24-script-task.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        processDefinitionId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void saturatedPool_startProcess_returns503WithRetryAfter() throws Exception {
        double rejectedBefore = meterRegistry.find("zbpm.script.rejected").counter() == null
            ? 0.0 : meterRegistry.find("zbpm.script.rejected").counter().count();

        // Пул тестового контекста — тот же синглтон, что у HTTP-старта.
        // Занимаем напрямую через его executor (как ScriptServicePoolSaturationTest):
        // runWithBudget для holders не годится — timeout вызывающего (10с)
        // cancel'ит latch-задачу раньше, чем HTTP-старт упрётся в сброс, и пул
        // успевает освободиться (поймано живым прогоном: 201 вместо 503).
        java.util.concurrent.ThreadPoolExecutor pool = readPool();
        // Занимаем ВСЕХ воркеров (8) latch-задачами — строго по barrier;
        // затем забиваем ВСЮ очередь (10) filler'ами (факт — размер, не сон).
        java.util.concurrent.CountDownLatch workersBusy =
            new java.util.concurrent.CountDownLatch(8);
        java.util.concurrent.CountDownLatch release =
            new java.util.concurrent.CountDownLatch(1);
        List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    pool.execute(() -> {
                        workersBusy.countDown();
                        try {
                            release.await(60, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } catch (RuntimeException e) {
                    // teardown — не asserted-путь
                }
            });
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
        List<Thread> fillers = new java.util.ArrayList<>();
        try {
            assertThat(workersBusy.await(30, java.util.concurrent.TimeUnit.SECONDS))
                .as("pool (8) occupied").isTrue();
            for (int i = 0; i < 10; i++) {
                Thread t = new Thread(() -> {
                    try {
                        pool.execute(() -> {
                            try {
                                release.await(60, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    } catch (RuntimeException e) {
                        // teardown — не asserted-путь
                    }
                });
                t.setDaemon(true);
                t.start();
                fillers.add(t);
            }
            // ФАКТ полного насыщения: очередь 10/10 (filler'ы встали, тела не
            // бегут — воркеры на латче).
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> pool.getQueue().size() == 10);

            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(processDefinitionId);
            dto.setVariables(List.of(longVar("a", "1"), longVar("b", "2")));

            // Критерий 2: 503 + Retry-After, НЕ 422. P-67: ассерт на
            // КОНКРЕТНЫЙ статус + КОНКРЕТНЫЙ заголовок со значением прод-кода
            // (max(1,5)=5), а не «ошибка есть».
            mockMvc.perform(post("/process-instances")
                            .header("Authorization", "Bearer " + adminToken)
                            .content(mapper.writeValueAsString(dto))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"));

            // Критерий 3: тот же сброс виден в метрике (дельта, реестр общий).
            assertThat(meterRegistry.find("zbpm.script.rejected").counter().count())
                .as("zbpm.script.rejected incremented by the shed start")
                .isGreaterThan(rejectedBefore);
        } finally {
            release.countDown();
            for (Thread t : workers) {
                t.join(15_000);
            }
            for (Thread t : fillers) {
                t.join(15_000);
            }
        }
    }

    private java.util.concurrent.ThreadPoolExecutor readPool() throws Exception {
        // Тот же синглтон ScriptServiceImpl, что обслуживает HTTP-старт.
        assertThat(scriptService)
            .as("test-context ScriptService is the prod impl")
            .isInstanceOf(com.zorrodev.bpm.engine.service.impl.ScriptServiceImpl.class);
        java.lang.reflect.Field f =
            com.zorrodev.bpm.engine.service.impl.ScriptServiceImpl.class.getDeclaredField("executor");
        f.setAccessible(true);
        return (java.util.concurrent.ThreadPoolExecutor) f.get(scriptService);
    }

    @Test
    void idlePool_startProcess_still201() throws Exception {
        // Регрессия: без перегрузки тот же процесс стартует штатно (201) —
        // 503-хендлер не перехватывает нормальный путь.
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(List.of(longVar("a", "1"), longVar("b", "2")));
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    private static ProcessVariable longVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.LONG);
        v.setValue(value);
        return v;
    }

    private String login(String username, String password) throws Exception {
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
