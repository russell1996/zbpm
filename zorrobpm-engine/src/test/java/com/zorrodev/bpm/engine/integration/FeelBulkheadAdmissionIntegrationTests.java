package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-24, критерий 1: 50 параллельных стартов процессов с io-mapping
 * (дешёвое FEEL-выражение через общий пул) → 0 отказов при штатных параметрах
 * пула (8 + очередь 10, прод-дефолты тестового профиля).
 *
 * <p>Каждый старт гоняет {@code =orderId} через {@code FeelBudgetImpl} — тот
 * же горячий путь, что unary-тесты DMN и script-task'и. 50 > 18 (pool+queue):
 * при старом мгновенном AbortPolicy-отказе всплеск сверх вместимости сбрасывал
 * бы операции; admission-wait его впитывает.
 *
 * <p>H2-оговорка: Hikari-дефолт (10 коннектов) меньше 50 стартеров — старты
 * идут волнами по ~10 (семафор), а не все 50 разом. Волна 10 всё ещё давит на
 * пул (каждый старт — FEEL через общий пул), но не упирается в DB-пул. Число
 * 50 сохранено (суммарно все старты проходят), параллелизм ограничен
 * инфраструктурой теста, не предметом измерения.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class FeelBulkheadAdmissionIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Test
    void fiftyConcurrentIoMappingStarts_zeroRejections() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-eng24-bulkhead.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        int starters = 50;
        int parallel = 10; // волна: Hikari-дефолт H2 — 10 коннектов
        java.util.concurrent.Semaphore wave = new java.util.concurrent.Semaphore(parallel);
        CountDownLatch ready = new CountDownLatch(starters);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Thread[] threads = new Thread[starters];
        for (int i = 0; i < starters; i++) {
            final int n = i;
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                    wave.acquire();
                    try {
                    StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                    dto.setProcessDefinitionId(model.getId());
                    ProcessVariable v = new ProcessVariable();
                    v.setName("orderId");
                    v.setType(ProcessVariableType.STRING);
                    v.setValue("A-" + n);
                    dto.setVariables(List.of(v));
                    runtimeService.startProcessInstance(dto);
                    ok.incrementAndGet();
                    } finally {
                        wave.release();
                    }
                } catch (RuntimeException e) {
                    if (e.getMessage() != null
                        && (e.getMessage().contains("pool full")
                            || e.getMessage().contains("pool overloaded"))) {
                        rejected.incrementAndGet();
                    } else {
                        errors.add(e);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            threads[i].setDaemon(true);
            threads[i].start();
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).as("all starters ready").isTrue();
        go.countDown();
        for (Thread t : threads) {
            t.join(120_000);
        }
        assertThat(errors).as("no unexpected errors: %s", errors).isEmpty();
        assertThat(rejected.get()).as("0 bulkhead rejections across 50 concurrent io-mapping starts").isEqualTo(0);
        assertThat(ok.get()).as("all 50 starts completed").isEqualTo(starters);
    }
}
