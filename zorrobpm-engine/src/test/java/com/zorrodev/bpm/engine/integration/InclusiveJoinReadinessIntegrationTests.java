package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-35 раунд 3 (HOLD-fix), ШАГ 1: единое правило готовности inclusive-join для ВСЕХ
 * топологий — статический счётчик {@code expected} убран (решение CTO на M1+M2), поэтому
 * «ждать остальные ветви» обязано выводиться из состояния инстанса.
 *
 * <p>Диаграмма этого класса — pass-through фанаут: обе ветви идут в join БЕЗ wait-state
 * между сплитом и join'ом. Это единственная топология, где «кто ещё может доставить» не
 * выводится из активных activity: у ещё не запущенной ветви строки activity нет вообще.
 * Ровно здесь снятие счётчика обязано НЕ превратиться в срабатывание на первом приходе
 * (тогда вторая ветвь сработает вторым проходом — тот самый BLOCKER-1 red-team, где
 * {@code taskNotify} создавался дважды).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class InclusiveJoinReadinessIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ActivityService activityService;

    private UUID start(String file) throws Exception {
        return startWithVars(file, List.of());
    }

    private UUID startWithVars(String file, List<ProcessVariable> variables) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(variables);
        IdDTO started = runtimeService.startProcessInstance(dto);
        return started.getId();
    }

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    private ProcessVariable var(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private void complete(UUID pi, String elementId) {
        complete(pi, elementId, List.of());
    }

    private void complete(UUID pi, String elementId, List<ProcessVariable> withVars) {
        activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .ifPresentOrElse(
                a -> runtimeService.completeUserTask(a.getId(), withVars),
                () -> { throw new AssertionError("no CREATED " + elementId + " in " + pi); });
    }

    /**
     * Open incidents of this instance. An incident row links to its ACTIVITY, not to the
     * instance, so the join goes through the instance's own activity ids.
     */
    private long incidents(UUID pi) {
        var activityIds = activities(pi).stream().map(ActivityEntity::getId).toList();
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
    }

    private long countOf(UUID pi, String elementId, ActivityStatus status) {
        return activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == status)
            .count();
    }

    @Transactional
    @Test
    void passthroughFanOut_inclusiveJoinPassesThroughExactlyOnce() throws Exception {
        UUID pi = start("test-c835-passthrough-fanout.bpmn");

        // The join fired exactly ONCE for the two-branch wave: the downstream user task
        // exists exactly once. Two arrivals -> two firings would create it twice.
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the join must pass through EXACTLY ONCE for the whole instance")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("downstream of the join must be entered exactly once (no double side effect)")
            .isEqualTo(1L);

        // Both branches really did arrive — the join is not "cheating" by firing early on
        // a single arrival with the other branch discarded.
        assertThat(activities(pi))
            .filteredOn(a -> a.getBpmnElementId().equals("fA") || a.getBpmnElementId().equals("fB"))
            .filteredOn(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .as("both split branches traversed their flow")
            .hasSize(2);
    }

    @Transactional
    @Test
    void passthroughFanOut_joinWaitsForBothBranches_arrivalsAreBothRecorded() throws Exception {
        UUID pi = start("test-c835-passthrough-fanout.bpmn");

        // The instance stays RUNNING with exactly one live wait state — the downstream task.
        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt()).isNull();
        assertThat(activities(pi))
            .filteredOn(a -> a.getStatus() == ActivityStatus.CREATED)
            .filteredOn(a -> a.getBpmnElementId().equals("taskAfter"))
            .hasSize(1);
    }

    // ── BLOCKER-1 (B1): второй приход в join приходит из ВЗВЕДЁННОЙ границы ─────────────
    @Transactional
    @Test
    void armedBoundaryTimer_joinWaitsForItAndThenPassesThroughExactlyOnce() throws Exception {
        UUID pi = start("test-c835-boundary-incl-join.bpmn");

        // taskMain's branch arrives; the non-interrupting boundary on taskWait is ARMED and its
        // outgoing (gB → taskHold → join) reaches the join. There is no activity row for that
        // boundary, so a rule built on active activities alone fires right here.
        complete(pi, "taskMain");

        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("an armed boundary can still deliver a branch — the join must wait, not fire")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskNotify", ActivityStatus.CREATED))
            .as("nothing downstream of the join may run before the boundary delivers")
            .isEqualTo(0L);

        // Now the LAST possible deliverer dies on the OTHER branch: the armed timer can no longer
        // reach the join (its host taskWait completes and the boundary is gone) — the re-check on
        // this deactivation is what lets the parked join fire, once.
        complete(pi, "taskWait");

        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the join must pass through EXACTLY ONCE — not twice, as on the pre-fix rule")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskNotify", ActivityStatus.CREATED))
            .as("taskNotify created twice is the exact red-team observation (double side effect)")
            .isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);
    }

    // ── BLOCKER-3 (red-team раунда 3): граница БЕЗ персистентной armed-записи ─────────────────
    @Transactional
    @Test
    void conditionalBoundaryOnALiveHost_theJoinPassesThroughExactlyOnce() throws Exception {
        // Диаграмма red-team дословно (WO-C8-35-independent-review-r3.md §BLOCKER-3):
        // pfork -> {taskA -> gC -> join ; taskHold + непрерывающий condB (abort="true") -> fBnd
        // -> join ; taskFlag}. CONDITIONAL-граница НЕ имеет строки armed-записи ни в одной
        // таблице (arm только timer), поэтому во вселенной «ещё может доставить» её не было,
        // а canReach шёл по sequence-потокам хоста, минуя привязку по attachedToRef.
        // Наблюдение red-team: join COMPLETED=1/taskAfter CREATED=1 уже после taskA, затем после
        // abort=true — join COMPLETED=2, taskAfter CREATED=2. Тихая двойная бизнес-побочка.
        UUID pi = start("test-c835-condbnd-incl-join.bpmn");

        complete(pi, "taskA");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("taskHold is LIVE and its conditional boundary reaches the join — the join must wait")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("nothing downstream of the join may run on arrival #1 of 2")
            .isEqualTo(0L);

        // abort=true -> condB fires on the still-live host, its branch arrives (2nd arrival).
        // The host is still alive, so its boundary can fire again — the join must STILL park:
        // firing here is exactly what made the red-team see taskAfter twice.
        complete(pi, "taskFlag", List.of(var("abort", "true")));
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("taskHold is still alive — its boundary can still deliver a branch, the join waits")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("taskAfter created before the host dies is the double side effect")
            .isEqualTo(0L);

        // The LAST possible deliverer dies: taskHold completes -> the boundary is disarmed ->
        // nobody can reach the join any more -> the re-check on this deactivation fires it ONCE.
        complete(pi, "taskHold");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the join must pass through EXACTLY ONCE — not twice (red-team BLOCKER-3)")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("taskAfter CREATED twice is the exact red-team observation")
            .isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);
    }

    // ── BLOCKER-6 (red-team раунда 4): граница на хосте, который ЕЩЁ НЕ СТАРТОВАЛ ────────
    @Transactional
    @Test
    void boundaryOnAHostThatHasNotStartedYet_theJoinWaitsAndThenPassesThroughExactlyOnce()
            throws Exception {
        // Диаграмма red-team дословно (WO-C8-35-independent-review-r4.md §BLOCKER-6):
        // pfork -> { taskA -> gC -> join ; taskSvc -> taskX -> endX },
        // tmrX (таймерная граница на taskX, ещё НЕ взведённая) -> fB -> join.
        // Это обычная «ветка-таймаут + основная ветка», а не экзотика.
        UUID pi = start("test-c835-notstarted-host-bnd-incl-join.bpmn");

        assertThat(countOf(pi, "taskX", ActivityStatus.CREATED))
            .as("premise: taskX has NOT started — no activity row, no armed boundary of its own")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskSvc", ActivityStatus.CREATED))
            .as("premise: the execution that WILL start taskX is live")
            .isEqualTo(1L);

        complete(pi, "taskA");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("taskSvc is live and will start taskX, whose tmrX can deliver a branch — "
                + "the join must wait (red-team: fired here, then fired AGAIN after the timer)")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskNotify", ActivityStatus.CREATED))
            .as("taskNotify before the boundary delivers is the double business side effect")
            .isEqualTo(0L);

        // taskSvc completes → taskX really starts → tmrX is armed for real.
        complete(pi, "taskSvc");
        UUID taskXId = activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals("taskX"))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("taskX must be created by completing taskSvc"))
            .getId();
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("taskX is live and its armed timer can still deliver — the join must keep waiting")
            .isEqualTo(0L);

        // The boundary fires and delivers the second arrival (log: "fB => from
        // BOUNDARY_TIMER_EVENT/tmrX to INCLUSIVE_GATEWAY/join").
        activityService.fireBoundaryTimer(taskXId, "tmrX");

        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("nothing else can deliver: the only outstanding arrival is already in the join. "
                + "Честно: принудительный fireBoundaryTimer НЕ claim'ит job (claim делает только "
                + "TimerBatchProcessor на боевом пути), поэтому у tmrX нет fired=true и правило "
                + "«исчерпана» по нему не срабатывает — join добьёт последний уход хоста. Состояние "
                + "«отстрелял» после НАСТОЯЩЕГО claim проверяется PG-IT на реальном PostgreSQL "
                + "(ArmedBoundaryTimerJoinPgIT) и юнит-тестами слоя данных.")
            .isEqualTo(0L);

        complete(pi, "taskX");

        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the boundary delivered the second branch — the join must pass through EXACTLY ONCE")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskNotify", ActivityStatus.CREATED))
            .as("taskNotify CREATED twice is the exact red-team observation (double side effect)")
            .isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);
    }

    // ── BLOCKER-2 (B2): ложное условие выше по потоку, последний доставщик умер ────────────
    @Transactional
    @Test
    void falseConditionAboveTheJoin_joinFiresWhenTheLastPossibleDelivererDies() throws Exception {
        UUID pi = startWithVars("test-c835-cond-upstream-incl-join.bpmn", List.of(var("valid", "false")));

        // gD arrives; taskValidate is still live and CAN reach the join through xor2's fOk branch.
        complete(pi, "taskPrepare");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("taskValidate is live and can still reach the join — the join must WAIT")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("nothing downstream of the join may run while fOk is still possible")
            .isEqualTo(0L);

        // taskValidate dies, valid=false → xor2 took fNg → taskReject. The join's only possible
        // arrival (gD) already came and fOk is ruled out: nobody can deliver a branch any more,
        // so the re-check on this deactivation MUST wake the parked join (BLOCKER-2: pre-fix it
        // stayed parked forever and the instance hung RUNNING with no incident).
        complete(pi, "taskValidate");

        assertThat(countOf(pi, "taskReject", ActivityStatus.CREATED))
            .as("xor2 must have taken the false branch")
            .isEqualTo(1L);
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the parked join must fire once the last possible deliverer is dead, NOT wait forever")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);

        // and the tail of the whole instance is reachable — the parked token really woke up
        complete(pi, "taskReject");
        complete(pi, "taskAfter");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
    }

    @Transactional
    @Test
    void trueConditionAboveTheJoin_joinWaitsForTheSecondBranchAndFiresOnce() throws Exception {
        // The mirror of the case above: with valid=true the fOk branch really does arrive, so the
        // join must wait for it and fire EXACTLY ONCE. This is the pair that makes the re-check
        // ordering load-bearing — re-checking BEFORE the dying element's own continuation fires
        // the join on gD alone, and fOk then fires it a second time.
        UUID pi = startWithVars("test-c835-cond-upstream-incl-join.bpmn", List.of(var("valid", "true")));

        complete(pi, "taskPrepare");
        complete(pi, "taskValidate");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("both arrivals are in — one firing")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);
    }

    // ── MAJOR-1 (M1): частичная конвергенция ─────────────────────────────────────────────
    @Transactional
    @Test
    void partialMerge_j1WaitsForItsOwnBranchesAndIgnoresTheBranchThatCannotReachIt() throws Exception {
        UUID pi = start("test-c835-partial-merge.bpmn");

        // The old counter wrote expected = activated.size() = 3 onto j1, which can only ever see
        // TWO arrivals (fA2, fB2) — the third branch (fC → taskC) bypasses j1 into j2 — so j1
        // waited forever and the instance hung.
        complete(pi, "taskA");
        assertThat(countOf(pi, "j1", ActivityStatus.COMPLETED))
            .as("taskB is j1's OWN branch and still live — the join must wait")
            .isEqualTo(0L);

        complete(pi, "taskB");
        assertThat(countOf(pi, "j1", ActivityStatus.COMPLETED))
            .as("both of j1's branches arrived and taskC cannot reach j1 — no counter to hang on")
            .isEqualTo(1L);

        // j1's outgoing fed j2, which is still waiting for taskC's branch
        assertThat(countOf(pi, "j2", ActivityStatus.COMPLETED))
            .as("j2 must still wait — taskC can reach it")
            .isEqualTo(0L);

        complete(pi, "taskC");
        assertThat(countOf(pi, "j2", ActivityStatus.COMPLETED))
            .as("both of j2's sources arrived (j1's outgoing and taskC's branch)")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);

        complete(pi, "taskAfter");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the instance must actually complete, not hang with a parked token")
            .isNotNull();
        assertThat(incidents(pi)).isEqualTo(0L);
    }

    // ── MAJOR-2 (M2): два сплита на один join ──────────────────────────────────────────────
    @Transactional
    @Test
    void twoSplitsOnOneJoin_noUniqueKeyIncidentAndTheJoinFiresExactlyOnce() throws Exception {
        UUID pi = start("test-c835-two-splits-one-join.bpmn");

        // Both splits ran during the fan-out: pre-fix, isplit2's recordInclusiveExpected was a
        // blind INSERT on the same (instance, 'join', 'join') key → DataIntegrityViolation
        // incident parked on isplit2 and this completion would have failed.
        assertThat(incidents(pi))
            .as("UK_PARALLEL_GATEWAYS__ARRIVAL violation parked as an incident on isplit2")
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskA", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(countOf(pi, "taskD", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED)).isEqualTo(0L);

        complete(pi, "taskA");
        complete(pi, "taskB");
        complete(pi, "taskC");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("3 of 4 arrivals — taskD is still live and reaches the join")
            .isEqualTo(0L);

        complete(pi, "taskD");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("all four branches arrived — the join fires ONCE, not twice (last-writer-wins counter)")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);

        complete(pi, "taskAfter");
        assertThat(countOf(pi, "taskAfter", ActivityStatus.COMPLETED))
            .as("taskAfter ran once and completed — no parked token left behind it")
            .isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);
        // НЕ проверяем здесь completedAt: у этой диаграммы НЕВЕРНО сходится счётчик
        // pendingBranches (вложенный фанаут pfork -> isplit -> 4 задачи; ветвь, припаркованная
        // на join, не декрементит его). Это поведение учёта pendingBranches, а не inclusive-join:
        // тот же расклад даёт обычный PARALLEL_GATEWAY join. Отдельная находка — в отчёте
        // (V7), здесь не чинится.
    }

    // ── находка @verifier раунда 3 (№1): деактивация через ДОСТАВКУ catch-события ──────────
    @Transactional
    @Test
    void signalCatchTakingItsFalseBranch_wakesTheParkedJoin() throws Exception {
        // Точная репродукция verifier'а: у последнего возможного доставщика join'а нет activity
        // ЗАДАЧИ — это signal catch, ушедший в условие с ложной ветвью. Хвост signal(...) —
        // тоже деактивация; до перепроверки он уходил в proceedToOutgoing, и join висел
        // вечно (RUNNING, токен припаркован, инцидента нет).
        UUID pi = start("test-c835-signal-catch-incl-join.bpmn");

        complete(pi, "taskA");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("sigCatch is live and can still reach the join through xorSig's true branch")
            .isEqualTo(0L);

        activityService.broadcastSignal("sig1", List.of());

        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the catch died on its false branch — the parked join must wake up")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(incidents(pi)).isEqualTo(0L);
    }
}
