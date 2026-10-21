package com.zorrodev.bpm.engine.leak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker.Baseline;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker.CheckResult;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker.Violation;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.opentest4j.AssertionFailedError;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * WO-OPS-26 п.5: тесты самого инварианта утечек.
 * WO-OPS-32: режим по умолчанию — enforce; добавлены проверки атрибуции
 * (свой инстанс теста / чужой инстанс общей БД) и принуждения на боевом пути.
 *
 * <p>Стратегия POF: checker сначала стаб ({@code return List.of()}) — все
 * «детектирующие» тесты КРАСНЫЕ; затем настоящая реализация — ЗЕЛЁНЫЕ.
 * Все тесты {@code @Transactional}: данные откатываются, чужие тесты не
 * пачкаются. Проверки идут прямым вызовом checker'а (та же TX) + парой
 * orderProbe, доказывающей, что afterEach расширения видит НЕЗАКОММИЧЕННЫЕ
 * данные (иначе проверка всегда зелёная и бесполезна).
 *
 * <p>Класс allowlisted (см. {@code leak-invariant-allowlist.txt}): сами эти
 * тесты НАМЕРЕННО крафтят живую активность в завершённом инстансе — иначе
 * детектор нечем было бы проверять. Это явная именованная запись исключения,
 * а не молчаливый allowlist.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
@Transactional
public class LeakInvariantListenerTests {

    @Autowired
    private com.zorrodev.bpm.engine.service.ProcessDefinitionService processDefinitionService;

    @Autowired
    private com.zorrodev.bpm.engine.service.RuntimeService runtimeService;

    @Autowired
    private com.zorrodev.bpm.engine.service.QueryService queryService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID startCompletedStartEnd() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test1.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("test1.bpmn завершается сразу на старте").isNotNull();
        return pi;
    }

    private UUID tokenOf(UUID pi) {
        return jdbc.queryForObject(
            "SELECT token FROM activities WHERE process_instance_id = ? LIMIT 1",
            UUID.class, pi);
    }

    private void insertLiveActivity(UUID pi, UUID token) {
        jdbc.update("INSERT INTO activities (id, process_instance_id, bpmn_element_id,"
            + " created_at, completed_at, type, status, token) VALUES (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), pi, "orphanTask", Instant.now(), null, "USER_TASK", "CREATED",
            token);
    }

    @Test
    @Order(1)
    void cleanCompletion_noViolations() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        CheckResult result = LeakInvariantChecker.check(jdbc, before);
        assertThat(result.own()).as("чистое завершение start→end: мусора нет, pi=" + pi).isEmpty();
    }

    @Test
    @Order(2)
    void liveActivityInCompletedInstance_isReported() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        insertLiveActivity(pi, tokenOf(pi));
        CheckResult result = LeakInvariantChecker.check(jdbc, before);
        assertThat(result.own()).as("осиротевшая CREATED-activity в завершённом инстансе")
            .hasSize(1);
        assertThat(result.own().get(0).type()).isEqualTo("activity");
        assertThat(result.own().get(0).elementId()).isEqualTo("orphanTask");
        assertThat(result.own().get(0).instanceId()).isEqualTo(pi);
    }

    @Test
    @Order(3)
    void unfinishedInstance_isNotChecked() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        String bpmn = Files.readString(Paths.get("src/test/files/test-usertask-query.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("user task ждёт — инстанс НЕ завершён").isNull();
        CheckResult result = LeakInvariantChecker.check(jdbc, before);
        assertThat(result.own())
            .as("незавершённый инстанс с живой activity — не нарушение").isEmpty();
    }

    @Test
    @Order(4)
    void unfiredTimerJob_isReported() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        UUID activityId = jdbc.queryForObject(
            "SELECT id FROM activities WHERE process_instance_id = ? LIMIT 1", UUID.class, pi);
        // Осиротевшее расписание = джоб на ЖИВОЙ activity: инстанс завершён,
        // а хост расписания ещё CREATED (класс boundary, ENG-3).
        jdbc.update("UPDATE activities SET status = 'CREATED', completed_at = NULL"
            + " WHERE id = ?", activityId);
        jdbc.update("INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at,"
            + " process_instance_id) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), activityId, Instant.now().plusSeconds(3600), false, Instant.now(),
            pi);
        CheckResult result = LeakInvariantChecker.check(jdbc, before);
        assertThat(result.own()).extracting(Violation::type).contains("timer");
    }

    @Test
    @Order(5)
    void unconsumedSubscription_isReported() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        UUID activityId = jdbc.queryForObject(
            "SELECT id FROM activities WHERE process_instance_id = ? LIMIT 1", UUID.class, pi);
        jdbc.update("INSERT INTO message_subscriptions (id, process_instance_id, activity_id,"
            + " message_name, consumed, created_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), pi, activityId, "probeMsg", false, Instant.now());
        CheckResult result = LeakInvariantChecker.check(jdbc, before);
        assertThat(result.own()).extracting(Violation::type).contains("subscription");
    }

    @Test
    @Order(6)
    void leftoverArrivalRow_isReported() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        jdbc.update("INSERT INTO parallel_gateways (id, process_instance_id,"
            + " gateway_element_id, entered_flow_id, created_at) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), pi, "joinX", "flowA", Instant.now());
        CheckResult result = LeakInvariantChecker.check(jdbc, before);
        assertThat(result.own()).extracting(Violation::type).contains("arrival");
    }

    @Test
    @Order(7)
    void danglingTokenCounter_isNotReported_whileTheCheckStaysDisabled() throws Exception {
        // Token-блок инварианта ОТКЛЮЧЁН как недоказанный (см. checker (б):
        // сигнал pending_branches>0 не подтверждён ни одним живым примером,
        // только cross-test артефактами shared-H2). Тест фиксирует текущее
        // поведение честно: checker НЕ репортит token-нарушения.
        // (WO-OPS-26 называл этот тест `danglingTokenCounter_isReported` — имя
        // говорило обратное; переименовано в WO-OPS-32.)
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        jdbc.update("UPDATE tokens SET pending_branches = 2 WHERE id = ?", tokenOf(pi));
        CheckResult result = LeakInvariantChecker.check(jdbc, before);
        assertThat(result.own()).extracting(Violation::type).doesNotContain("token");
    }

    @Test
    @Order(8)
    void openUserTask_isReported() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        UUID pdId = jdbc.queryForObject(
            "SELECT process_definition_id FROM process_instances WHERE id = ?", UUID.class, pi);
        // Orphan-пара activity+task: живая activity + открытая task-строка.
        UUID activityId = jdbc.queryForObject(
            "SELECT id FROM activities WHERE process_instance_id = ? LIMIT 1", UUID.class, pi);
        jdbc.update("UPDATE activities SET status = 'CREATED', completed_at = NULL"
            + " WHERE id = ?", activityId);
        jdbc.update("INSERT INTO user_tasks (id, bpmn_element_id, process_instance_id,"
            + " process_definition_id, created_at, completed_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), "hangingTask", pi, pdId, Instant.now(), null);
        CheckResult result = LeakInvariantChecker.check(jdbc, before);
        assertThat(result.own()).extracting(Violation::type).contains("task");
    }

    @Test
    @Order(9)
    void staleScopeVariable_isReported_butMiBatchIsIgnored() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        // Инвариант scope-var действует только для отменённых инстансов
        // (у нормально завершённых scope-история — не мусор): помечаем отмену.
        jdbc.update("UPDATE process_instances SET cancelled = true WHERE id = ?", pi);
        UUID scope = UUID.randomUUID();
        jdbc.update("INSERT INTO variables (id, process_instance_id, name, text_value, type,"
            + " scope_id) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), pi, "staleLoopVar", "1", "LONG", scope);
        jdbc.update("INSERT INTO variables (id, process_instance_id, name, text_value, type,"
            + " scope_id) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), pi, "_mi_batch_miTask", "[]", "JSON", scope);
        CheckResult result = LeakInvariantChecker.check(jdbc, before);
        assertThat(result.own()).extracting(Violation::type).contains("scope-var");
        assertThat(result.own()).extracting(Violation::elementId).doesNotContain("_mi_batch_miTask");
    }

    /**
     * WO-OPS-32: дефолтный режим — enforce, и принуждение происходит НА
     * БОЕВОМ пути (метод {@link LeakInvariantTestListener#enforce}), а не
     * «имитацией» внутри теста (G-N): тест зовёт тот же метод, который
     * роняет сборку.
     */
    @Test
    @Order(10)
    void enforceMode_isTheDefault_andFailsOnOwnViolation() throws Exception {
        assertThat(LeakInvariantTestListener.DEFAULT_MODE).isEqualTo("enforce");

        String saved = System.getProperty(LeakInvariantTestListener.MODE_PROPERTY);
        try {
            // Дефолт (свойство не задано) — enforce. Проверка не зависит от
            // того, как запущен прогон: свойство мог задать argLine.
            System.clearProperty(LeakInvariantTestListener.MODE_PROPERTY);
            assertThat(LeakInvariantTestListener.mode())
                .as("дефолтный режим проверки — enforce (WO-OPS-32)")
                .isEqualTo("enforce");

            // Боевой путь принуждения: enforce() роняет тест на не-allowlisted
            // нарушении СВОЕГО инстанса.
            List<Violation> hard = List.of(new Violation(UUID.randomUUID(), "activity",
                "orphanTask", "live activity, status=CREATED"));
            assertThatThrownBy(() -> LeakInvariantTestListener.enforce(
                LeakInvariantListenerTests.class.getName() + "#enforceMode_isTheDefault", hard))
                .as("enforce роняет тест на не-allowlisted нарушении СВОЕГО инстанса")
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("leak invariant")
                .hasMessageContaining("orphanTask");

            assertThatCode(() -> LeakInvariantTestListener.enforce(
                LeakInvariantListenerTests.class.getName() + "#enforceMode_isTheDefault", List.of()))
                .as("без нарушений enforce не роняет тест")
                .doesNotThrowAnyException();

            // Режим report — тот же путь НЕ роняет (иначе «собрать отчёт» было
            // бы невозможно).
            System.setProperty(LeakInvariantTestListener.MODE_PROPERTY, "report");
            assertThatCode(() -> LeakInvariantTestListener.enforce(
                LeakInvariantListenerTests.class.getName() + "#enforceMode_isTheDefault", hard))
                .as("в режиме report нарушение только пишется в отчёт")
                .doesNotThrowAnyException();
        } finally {
            if (saved == null) {
                System.clearProperty(LeakInvariantTestListener.MODE_PROPERTY);
            } else {
                System.setProperty(LeakInvariantTestListener.MODE_PROPERTY, saved);
            }
        }
    }

    /**
     * WO-OPS-32: инстанс, который ДО теста уже существовал и завершился в
     * окне теста, — ЧУЖОЙ (общая БД без изоляции). Он попадает в foreign
     * (только отчёт) и НИКОГДА не роняет сборку.
     */
    @Test
    @Order(11)
    void instanceThatExistedBeforeTheTest_isForeign_andNeverEnforced() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        // Остаток живого мусора — иначе нарушения нечего приписывать.
        insertLiveActivity(pi, tokenOf(pi));

        // Свой инстанс: создан после baseline → own.
        CheckResult own = LeakInvariantChecker.check(jdbc, before);
        assertThat(own.own()).extracting(Violation::instanceId).contains(pi);

        // Тот же инстанс, но baseline говорит «он существовал до теста и не был
        // завершён» → foreign (артефакт общей БД), а не свой.
        Set<UUID> asIfExistedBefore = new LinkedHashSet<>(before.instanceIds());
        asIfExistedBefore.add(pi);
        Baseline asIfForeign = new Baseline(asIfExistedBefore, before.completedInstanceIds());
        CheckResult foreign = LeakInvariantChecker.check(jdbc, asIfForeign);
        assertThat(foreign.own())
            .as("инстанс, существовавший до теста, — не свой: в own не попадает").isEmpty();
        assertThat(foreign.foreign()).extracting(Violation::instanceId).contains(pi);

        // Чужое нарушение не принуждается: enforce() получает только «свои».
        assertThatCode(() -> LeakInvariantTestListener.enforce(
            LeakInvariantListenerTests.class.getName() + "#instanceThatExistedBeforeTheTest",
            List.of()))
            .doesNotThrowAnyException();
    }

    /**
     * WO-OPS-32 (после независимой проверки): arrival-строки движок чистит
     * В ФАЗЕ КОМИТА ({@code clearInstanceBookkeepingAtCommit} → beforeCommit).
     * В @Transactional-тесте коммита не будет (откат) → в прод-состоянии таких
     * строк не было бы → это НЕ утечка, а снимок до коммита. Такие строки идут
     * только в отчёт (категория «commit-phase») и никогда не роняют сборку.
     * Без транзакции (фаза коммита прошла) — оставшаяся arrival-строка уже
     * дефект движка и принуждается.
     */
    @Test
    @Order(13)
    void arrivalRows_areCommitPhaseInTransactionalTest_andHardOtherwise() throws Exception {
        Baseline before = LeakInvariantChecker.snapshot(jdbc);
        UUID pi = startCompletedStartEnd();
        jdbc.update("INSERT INTO parallel_gateways (id, process_instance_id,"
            + " gateway_element_id, entered_flow_id, created_at) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), pi, "joinX", "flowA", Instant.now());

        // Чужой (не allowlisted) test-id: иначеallowlist этого класса забрал бы
        // нарушение в «allowed» и политика не проверялась бы.
        String testId = "com.zorrodev.bpm.engine.integration.SomeOtherTest#arrivalRows";

        // Определение транзакционности — тоже под проверкой: мутация «всегда
        // не-транзакционный» возвращает arrival-строки в принуждаемые.
        assertThat(LeakInvariantTestListener.isTransactional(
            LeakInvariantListenerTests.class.getDeclaredMethod(
                "arrivalRows_areCommitPhaseInTransactionalTest_andHardOtherwise"),
            LeakInvariantListenerTests.class))
            .as("этот класс помечен @Transactional — фазы коммита не будет").isTrue();
        assertThat(LeakInvariantTestListener.isTransactional(
            LeakInvariantBaselineAttributionIntegrationTests.class.getDeclaredMethod(
                "createInstanceBeforeTheTest"),
            LeakInvariantBaselineAttributionIntegrationTests.class))
            .as("этот класс НЕ транзакционный — фаза коммита проходит").isFalse();
        LeakInvariantTestListener.Evaluation transactional =
            LeakInvariantTestListener.evaluate(jdbc, before, testId, true);
        assertThat(transactional.hard()).extracting(Violation::type).doesNotContain("arrival");
        assertThat(transactional.commitPhase()).extracting(Violation::type).contains("arrival");

        LeakInvariantTestListener.Evaluation committed =
            LeakInvariantTestListener.evaluate(jdbc, before, testId, false);
        assertThat(committed.hard()).extracting(Violation::type).contains("arrival");
    }

    @Test
    @Order(12)
    void allowlist_matchingAndReason() {
        List<LeakInvariantTestListener.AllowEntry> list =
            LeakInvariantTestListener.loadAllowlist();
        assertThat(list).as("allowlist читается (может быть пустым)").isNotNull();
        // Синтетическая запись: класс+метод этого теста с причиной.
        List<LeakInvariantTestListener.AllowEntry> synthetic = List.of(
            new LeakInvariantTestListener.AllowEntry(
                "LeakInvariantListenerTests#allowlist_matchingAndReason", "probe"));
        assertThat(synthetic).hasSize(1);
        assertThat(synthetic.get(0).reason()).isEqualTo("probe");
        // Настоящая запись: этот класс крафтит утечку намеренно — запись
        // обязана быть, с непустой причиной и с WO-id (иначе сборка в enforce
        // падает на собственных тестах детектора).
        assertThat(list).as("allowlist содержит именованное исключение для класса детектора")
            .anySatisfy(entry -> {
                assertThat(entry.pattern())
                    .isEqualTo(LeakInvariantListenerTests.class.getSimpleName());
                assertThat(entry.reason()).isNotBlank();
                assertThat(entry.reason()).contains("WO-");
            });
        assertThat(LeakInvariantTestListener.isAllowlistedPattern(list,
            LeakInvariantListenerTests.class.getName() + "#anyMethod"))
            .as("исключение действует на весь класс").isTrue();
    }

    /**
     * Часть 1 orderProbe: оставляет НЕЗАКОММИЧЕННУЮ живую activity в
     * завершённом инстансе и выходит. Listener проверки в
     * {@code afterTestExecution} (ДО отката @Transactional) обязан её увидеть
     * и записать в LAST_VIOLATIONS; проверка после отката не увидела бы
     * ничего (слепая проверка).
     */
    @Test
    @Order(20)
    void orderProbe_part1_craftUncommittedLeak() throws Exception {
        UUID pi = startCompletedStartEnd();
        insertLiveActivity(pi, tokenOf(pi));
    }

    /**
     * Часть 2 orderProbe: listener проверки обязан был увидеть утечку части 1
     * в фазе afterTestExecution (ДО отката @Transactional). Ключ LAST_VIOLATIONS
     * — Spring-ключ «класс#метод». Мутация «сломать порядок» (проверка
     * после отката — например, перенос проверки в afterTestMethod) роняет
     * этот тест: утечка части 1 уже откачена и не видна.
     */
    @Test
    @Order(21)
    void orderProbe_part2_extensionSawItBeforeRollback() {
        String part1 = LeakInvariantListenerTests.class.getName()
            + "#orderProbe_part1_craftUncommittedLeak";
        List<Violation> seen =
            LeakInvariantTestListener.LAST_VIOLATIONS.getOrDefault(part1, List.of());
        assertThat(seen).as("listener проверки видит незакоммиченные данные"
            + " (фаза afterTestExecution — ДО отката @Transactional)")
            .extracting(Violation::type).contains("activity");
    }

    @Test
    @Order(22)
    void listenerIsWired_checksPerformed() {
        assertThat(LeakInvariantTestListener.CHECKS_PERFORMED.get())
            .as("проверка реально выполнялась (мутация «выключить проверку» роняет)")
            .isGreaterThan(0);
    }
}
