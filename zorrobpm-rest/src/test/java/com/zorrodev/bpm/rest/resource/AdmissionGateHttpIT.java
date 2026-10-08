package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.AdmissionLease;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ENG-35, критерий 2: гейтованный HTTP-вход при sustained-перегрузке пула
 * сбрасывает нагрузку ТЕМ ЖЕ 503 — но ожидание admission-окна происходит ДО
 * открытия транзакции (соединение Hikari не удерживается).
 *
 * <p>Конструкция гонки (V6, факты вместо sleep): контекст маленький
 * (пул 1 + очередь 1 → мест в гейте 2, окно 2с — свой контекст через
 * properties, соседним IT не мешает). Насыщение ОБОИХ слоёв строго по
 * фактам: (а) сырой executor забит в обход гейта (воркер на латче +
 * очередь size==1 — модель трафика, обошедшего гейт; в проде такого нет,
 * все прод-сабмиты идут через submitToPool); (б) оба пермита гейта держат
 * фоновые лизы (факт availablePermits()==0). Затем HTTP-старт процесса со
 * script-task'ом.
 *
 * <p>GREEN (гейт): ожидание 2с — в {@code admitOutsideTx} контроллера, до
 * ЛЮБОЙ транзакции → 503 + Retry-After "2", дельта
 * {@code zbpm.script.admission.in_tx} == 0 (ни одного ожидания внутри).
 * Время ответа ≥ окну доказывает, что ожидание реально произошло на
 * HTTP-пути (а не «быстрый отказ без ожидания»).
 *
 * <p>RED-мутация (в ПРОД-коде): {@code gated(() -> ...)} → прямой вызов
 * делегата в {@code RuntimeResource} (обход гейта = «ожидание вернулось
 * внутрь»): вход открывает транзакцию, упирается в забитый пул, ждёт то же
 * окно ВНУТРИ (in-tx дельта == 1), затем тот же 503. Тест КРАСНЫЙ на
 * счётчике (ожидался 0, получен 1): статус в обеих версиях 503 — статус
 * здесь НЕ дискриминирует, дискриминирует только счётчик (честно
 * зафиксировано; P-67: ассерт на конкретное значение счётчика).
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "zorrobpm.engine.script-pool-size=1",
        "zorrobpm.engine.script-queue-capacity=1",
        "zorrobpm.engine.script-queue-wait-seconds=2",
        "zorrobpm.engine.script-timeout-seconds=10"
    })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class AdmissionGateHttpIT {

    private static final long WINDOW_SECONDS = 2;
    private static final long WINDOW_MS = WINDOW_SECONDS * 1000;

    @Autowired private MockMvc mockMvc;
    @Autowired private ScriptService scriptService;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
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

    private String login(String username, String password) throws Exception {
        LoginDTO loginDto = new LoginDTO();
        loginDto.setUsername(username);
        loginDto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(loginDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(
            result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private static ProcessVariable longVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(ProcessVariableType.LONG);
        return v;
    }

    private java.util.concurrent.ThreadPoolExecutor pool() throws Exception {
        java.lang.reflect.Field f = scriptService.getClass().getDeclaredField("executor");
        f.setAccessible(true);
        return (java.util.concurrent.ThreadPoolExecutor) f.get(scriptService);
    }

    private int gatePermits() throws Exception {
        java.lang.reflect.Field f = scriptService.getClass().getDeclaredField("admissionGate");
        f.setAccessible(true);
        return ((java.util.concurrent.Semaphore) f.get(scriptService)).availablePermits();
    }

    private double inTxCount() {
        io.micrometer.core.instrument.Counter c =
            meterRegistry.find("zbpm.script.admission.in_tx").counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void saturatedPool_gatedStart_sheds503WithoutInTxWait() throws Exception {
        double inTxBefore = inTxCount();
        java.util.concurrent.ThreadPoolExecutor raw = pool();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);

        // Слой (а): сырой executor забит в обход гейта — воркер на латче...
        raw.execute(() -> {
            workerBusy.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(workerBusy.await(30, TimeUnit.SECONDS))
            .as("воркер пула занят").isTrue();
        // ...и очередь забита (факт — размер, не сон).
        raw.execute(() -> {
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
            .until(() -> raw.getQueue().size() == 1);

        // Слой (б): оба пермита гейта держат фоновые лизы с РАЗНЫХ потоков
        // (факт — 0 свободно). Разные потоки — обязательно: второй
        // admitOutsideTx на том же потоке — реентрантный noop без acquire
        // (владелец уже держит слот), как в проде у вложенных входов.
        CountDownLatch leasesHeld = new CountDownLatch(2);
        CountDownLatch leasesRelease = new CountDownLatch(1);
        List<Thread> leaseHolders = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try (AdmissionLease held = scriptService.admitOutsideTx()) {
                    leasesHeld.countDown();
                    leasesRelease.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.setDaemon(true);
            t.start();
            leaseHolders.add(t);
        }
        try {
            assertThat(leasesHeld.await(30, TimeUnit.SECONDS))
                .as("обе фоновые лизы взяты").isTrue();
            assertThat(gatePermits()).as("все пермиты гейта разобраны").isZero();

            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(processDefinitionId);
            dto.setVariables(List.of(longVar("a", "1"), longVar("b", "2")));

            long startNs = System.nanoTime();
            mockMvc.perform(post("/process-instances")
                            .header("Authorization", "Bearer " + adminToken)
                            .content(mapper.writeValueAsString(dto))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After",
                        String.valueOf(WINDOW_SECONDS)));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

            assertThat(waitedMs)
                .as("ожидание реально произошло на HTTP-пути (полное окно, до сброса)")
                .isGreaterThanOrEqualTo(WINDOW_MS);
            assertThat(waitedMs)
                .as("ожидание ограничено окном, а не бесконечно")
                .isLessThan(WINDOW_MS + 15_000L);
            assertThat(inTxCount() - inTxBefore)
                .as("ни одного admission-ожидания внутри транзакции: гейт ждал "
                    + "до её открытия (мутация «обход гейта» даёт здесь 1.0)")
                .isZero();
        } finally {
            leasesRelease.countDown();
            for (Thread t : leaseHolders) {
                t.join(15_000);
            }
            release.countDown();
        }
    }
}
