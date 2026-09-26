package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.VariableHistoryRepository;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WO-ENG-16 (WB-003): append-only история переменных — реальный прод-путь
 * (DBService → VariableDbOperationsImpl/ProcessInstanceDbOperationsImpl →
 * VariableHistoryWriter), реальные строки, не моки.
 *
 * <p>Наследуется PG-вариантом ({@code VariableHistoryPgIT}) без изменений —
 * та же гарантия на прод-СУБД + доказательство миграции 110: удали changeset,
 * и PG-тест упадёт на отсутствующей таблице (G-N/G9).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
class VariableHistoryIntegrationTests {

    @Autowired private DBService dbService;
    @Autowired private VariableHistoryRepository historyRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;

    private UUID newProcessInstance(List<ProcessVariable> initial) {
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey("vh-probe-" + UUID.randomUUID());
        pd.setName("Variable History Probe");
        pd.setVersion(1);
        pd.setSha256(UUID.randomUUID().toString());
        pd.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pd);
        return dbService.createProcessInstance(null, pd.getId(), initial);
    }

    private static ProcessVariable pv(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(ProcessVariableType.STRING);
        return v;
    }

    /**
     * WO-OPS-14: гранулярность порядка — соседние записи обязаны различаться
     * тиком настенных часов ({@code changed_at} ставится из
     * {@code Instant.now()} прод-кодом). Ждём смену миллисекунды условием,
     * а не фиксированным {@code Thread.sleep(10)}: на быстрой машине тик
     * приходит раньше, на загруженном CI — ждём сколько нужно (до 5с).
     */
    private static void awaitNextMillis() {
        long before = Instant.now().toEpochMilli();
        await().atMost(java.time.Duration.ofSeconds(5))
            .until(() -> Instant.now().toEpochMilli() != before);
    }

    @Test
    void history_containsAllNValuesInOrder_currentUnchanged() throws Exception {
        UUID pi = newProcessInstance(List.of(pv("counter", "0")));
        // Паузы между записями — не тайминг-ассерт, а гранулярность порядка:
        // changed_at имеет миллисекундное разрешение, соседние записи обязаны
        // различаться тиком, иначе порядок hielt бы только на случайном id.
        for (int i = 1; i <= 5; i++) {
            awaitNextMillis();
            dbService.setVariables(pi, List.of(pv("counter", String.valueOf(i))));
        }

        List<VariableHistoryEntry> history = dbService.getVariableHistory(pi, "counter");
        assertThat(history).extracting(VariableHistoryEntry::textValue)
            .containsExactly("0", "1", "2", "3", "4", "5");
        assertThat(history).extracting(VariableHistoryEntry::source)
            .containsExactly("INIT", "UPDATE", "UPDATE", "UPDATE", "UPDATE", "UPDATE");
        assertThat(history).extracting(VariableHistoryEntry::changedAt)
            .isSorted();

        // Текущее состояние не меняет поведение: последнее значение на месте.
        assertThat(dbService.getVariables(pi)).extracting(ProcessVariable::getValue)
            .containsExactly("5");
    }

    @Test
    void scopedHistory_keepsScopeSeparation() throws Exception {
        UUID pi = newProcessInstance(List.of(pv("k", "root0")));
        UUID activity = UUID.randomUUID();
        awaitNextMillis();
        dbService.setVariables(pi, activity, List.of(pv("k", "local1")));

        List<VariableHistoryEntry> all = dbService.getVariableHistory(pi);
        assertThat(all).hasSize(2);
        VariableHistoryEntry rootEntry = all.stream()
            .filter(e -> e.scopeId() == null).findFirst().orElseThrow();
        VariableHistoryEntry scopedEntry = all.stream()
            .filter(e -> activity.equals(e.scopeId())).findFirst().orElseThrow();
        assertThat(rootEntry.textValue()).isEqualTo("root0");
        assertThat(scopedEntry.textValue()).isEqualTo("local1");

        // Одноимённая scoped-переменная не смешивается с root-историей.
        assertThat(dbService.getVariableHistory(pi, "k")).hasSize(2);
    }

    @Test
    void appendJsonElement_recordsResultingArray() throws Exception {
        UUID pi = newProcessInstance(List.of());
        dbService.appendJsonElement(pi, "items", "\"a\"");
        awaitNextMillis();
        dbService.appendJsonElement(pi, "items", "\"b\"");

        List<VariableHistoryEntry> history = dbService.getVariableHistory(pi, "items");
        assertThat(history).extracting(VariableHistoryEntry::source)
            .containsExactly("APPEND", "APPEND");
        // История фиксирует РЕЗУЛЬТ слияния, не дельту. Сравнение — по JSON-массиву,
        // не строкой: PG jsonb нормализует пробелы ('["a", "b"]'), H2-хирургия — нет
        // ('["a","b"]'); оба — тот же массив из двух элементов (урок PG-прогона).
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(history.get(0).textValue()).toString()).isEqualTo("[\"a\"]");
        assertThat(mapper.readTree(history.get(1).textValue()).toString()).isEqualTo("[\"a\",\"b\"]");
        assertThat(mapper.readTree(dbService.getVariables(pi).get(0).getValue()).toString())
            .isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void deleteScope_doesNotWriteHistory() throws Exception {
        UUID pi = newProcessInstance(List.of(pv("k", "v")));
        UUID activity = UUID.randomUUID();
        dbService.setVariables(pi, activity, List.of(pv("tmp", "x")));
        long before = historyRepository.count();
        dbService.deleteVariables(pi, activity);
        // Снос скоупа — lifecycle-мусор, не бизнес-изменение: строк не добавляется,
        // старые строки сноса не касается (append-only).
        assertThat(historyRepository.count()).isEqualTo(before);
    }

    @Test
    void writeOverhead_boundedByGenerousBudget() throws Exception {
        UUID pi = newProcessInstance(List.of());
        int writes = 50;
        long t0 = System.nanoTime();
        for (int i = 0; i < writes; i++) {
            dbService.setVariables(pi, List.of(pv("probe", "v" + i)));
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("WO-ENG-16 overhead probe: " + writes + " setVariables with history took "
            + elapsedMs + " ms (" + String.format("%.2f", (double) elapsedMs / writes) + " ms/write)");
        // Не микро-бенчмарк, а guard от патологий: синхронный INSERT в той же
        // транзакции не может стоить дорого; бюджет с запасом на порядок.
        assertThat(elapsedMs).as("50 записей с историей укладываются в 15с").isLessThan(15_000);
        assertThat(dbService.getVariableHistory(pi, "probe")).hasSize(writes);
    }
}
