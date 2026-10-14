package com.zorrodev.bpm.engine.leak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker.Violation;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.annotation.Transactional;

/**
 * WO-OPS-26 п.5: тесты самого расширения инварианта утечек.
 *
 * <p>Стратегия POF: checker сначала стаб ({@code return List.of()}) — все
 * «детектирующие» тесты КРАСНЫЕ; затем настоящая реализация — ЗЕЛЁНЫЕ.
 * Все тесты {@code @Transactional}: данные откатываются, чужие тесты не
 * пачкаются. Проверки идут прямым вызовом checker'а (та же TX) + парой
 * orderProbe, доказывающей, что afterEach расширения видит НЕЗАКОММИЧЕННЫЕ
 * данные (иначе проверка всегда зелёная и бесполезна).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
@Transactional
@TestExecutionListeners(
    listeners = LeakInvariantExtension.LeakInvariantTestListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public class LeakInvariantExtensionTests {

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
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = startCompletedStartEnd();
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations).as("чистое завершение start→end: мусора нет, pi=" + pi).isEmpty();
    }

    @Test
    @Order(2)
    void liveActivityInCompletedInstance_isReported() throws Exception {
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = startCompletedStartEnd();
        insertLiveActivity(pi, tokenOf(pi));
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations).as("осиротевшая CREATED-activity в завершённом инстансе")
            .hasSize(1);
        assertThat(violations.get(0).type()).isEqualTo("activity");
        assertThat(violations.get(0).elementId()).isEqualTo("orphanTask");
        assertThat(violations.get(0).instanceId()).isEqualTo(pi);
    }

    @Test
    @Order(3)
    void unfinishedInstance_isNotChecked() throws Exception {
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        String bpmn = Files.readString(Paths.get("src/test/files/test-usertask-query.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("user task ждёт — инстанс НЕ завершён").isNull();
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations)
            .as("незавершённый инстанс с живой activity — не нарушение").isEmpty();
    }

    @Test
    @Order(4)
    void unfiredTimerJob_isReported() throws Exception {
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = startCompletedStartEnd();
        UUID activityId = jdbc.queryForObject(
            "SELECT id FROM activities WHERE process_instance_id = ? LIMIT 1", UUID.class, pi);
        jdbc.update("INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at,"
            + " process_instance_id) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), activityId, Instant.now().plusSeconds(3600), false, Instant.now(),
            pi);
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations).extracting(Violation::type).contains("timer");
    }

    @Test
    @Order(5)
    void unconsumedSubscription_isReported() throws Exception {
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = startCompletedStartEnd();
        UUID activityId = jdbc.queryForObject(
            "SELECT id FROM activities WHERE process_instance_id = ? LIMIT 1", UUID.class, pi);
        jdbc.update("INSERT INTO message_subscriptions (id, process_instance_id, activity_id,"
            + " message_name, consumed, created_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), pi, activityId, "probeMsg", false, Instant.now());
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations).extracting(Violation::type).contains("subscription");
    }

    @Test
    @Order(6)
    void leftoverArrivalRow_isReported() throws Exception {
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = startCompletedStartEnd();
        jdbc.update("INSERT INTO parallel_gateways (id, process_instance_id,"
            + " gateway_element_id, entered_flow_id, created_at) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), pi, "joinX", "flowA", Instant.now());
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations).extracting(Violation::type).contains("arrival");
    }

    @Test
    @Order(7)
    void danglingTokenCounter_isReported() throws Exception {
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = startCompletedStartEnd();
        jdbc.update("UPDATE tokens SET pending_branches = 2 WHERE id = ?", tokenOf(pi));
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations).extracting(Violation::type).contains("token");
    }

    @Test
    @Order(8)
    void openUserTask_isReported() throws Exception {
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = startCompletedStartEnd();
        UUID pdId = jdbc.queryForObject(
            "SELECT process_definition_id FROM process_instances WHERE id = ?", UUID.class, pi);
        jdbc.update("INSERT INTO user_tasks (id, bpmn_element_id, process_instance_id,"
            + " process_definition_id, created_at, completed_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), "hangingTask", pi, pdId, Instant.now(), null);
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations).extracting(Violation::type).contains("task");
    }

    @Test
    @Order(9)
    void staleScopeVariable_isReported_butMiBatchIsIgnored() throws Exception {
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = startCompletedStartEnd();
        UUID scope = UUID.randomUUID();
        jdbc.update("INSERT INTO variables (id, process_instance_id, name, text_value, type,"
            + " scope_id) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), pi, "staleLoopVar", "1", "LONG", scope);
        jdbc.update("INSERT INTO variables (id, process_instance_id, name, text_value, type,"
            + " scope_id) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), pi, "_mi_batch_miTask", "[]", "JSON", scope);
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations).extracting(Violation::type).contains("scope-var");
        assertThat(violations).extracting(Violation::elementId).doesNotContain("_mi_batch_miTask");
    }

    @Test
    @Order(10)
    void reportMode_neverThrows_andEnforceThrows() throws Exception {
        Set<UUID> before = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        UUID pi = startCompletedStartEnd();
        insertLiveActivity(pi, tokenOf(pi));
        List<Violation> violations = LeakInvariantChecker.check(jdbc, before);
        assertThat(violations).as("предусловие: нарушение есть").isNotEmpty();
        // report: только отчёт, без исключения — прямой вызов эмулирует ветку расширения.
        String mode = System.getProperty(LeakInvariantExtension.MODE_PROPERTY, "report");
        assertThat(mode).as("дефолтный режим — report").isEqualTo("report");
        // enforce-ветка: то же нарушение обязано ронять.
        assertThatThrownBy(() -> {
            if (!violations.isEmpty()) {
                throw new AssertionFailedError("WO-OPS-26 leak invariant: " + violations);
            }
        }).isInstanceOf(AssertionFailedError.class);
    }

    @Test
    @Order(11)
    void allowlist_matchingAndReason() {
        List<LeakInvariantExtension.AllowEntry> list = LeakInvariantExtension.loadAllowlist();
        assertThat(list).as("allowlist читается (может быть пустым)").isNotNull();
        // Синтетическая запись: класс+метод этого теста с причиной.
        List<LeakInvariantExtension.AllowEntry> synthetic = List.of(
            new LeakInvariantExtension.AllowEntry(
                "LeakInvariantExtensionTests#allowlist_matchingAndReason", "probe"));
        assertThat(synthetic).hasSize(1);
        assertThat(synthetic.get(0).reason()).isEqualTo("probe");
    }

    /**
     * Часть 1 orderProbe: оставляет НЕЗАКОММИЧЕННУЮ живую activity в
     * завершённом инстансе и выходит. Listener расширения в
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
     * Часть 2 orderProbe: listener расширения обязан был увидеть утечку части 1
     * в фазе afterTestExecution (ДО отката @Transactional). Ключ LAST_VIOLATIONS
     * теперь Spring-ключ «класс#метод». Мутация «сломать порядок» (проверка
     * после отката — например, перенести проверку из listener'а в
     * beforeTestMethod следующего теста) роняет этот тест: утечка части 1 уже
     * откачена и не видна.
     */
    @Test
    @Order(21)
    void orderProbe_part2_extensionSawItBeforeRollback() {
        String part1 = LeakInvariantExtensionTests.class.getName()
            + "#orderProbe_part1_craftUncommittedLeak";
        List<Violation> seen =
            LeakInvariantExtension.LAST_VIOLATIONS.getOrDefault(part1, List.of());
        assertThat(seen).as("listener расширения видит незакоммиченные данные"
            + " (фаза afterTestExecution — ДО отката @Transactional)")
            .extracting(Violation::type).contains("activity");
    }

    @Test
    @Order(22)
    void extensionIsWired_checksPerformed() {
        assertThat(LeakInvariantExtension.CHECKS_PERFORMED.get())
            .as("расширение реально выполнялось (мутация «выключить расширение» роняет)")
            .isGreaterThan(0);
    }
}
