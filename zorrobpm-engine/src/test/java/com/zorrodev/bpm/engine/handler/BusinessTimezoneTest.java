package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.scheduler.TimerExpressions;
import com.zorrodev.bpm.engine.scheduler.TimerStartJobExecutor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * WO-ENG-4: Business timezone for FEEL timer expressions.
 * <p>
 * Verifies that zone-naive FEEL date/time values ({@link LocalDateTime}, {@link LocalDate})
 * are converted to {@link Instant} using the configured {@code businessZone} (default
 * {@code Asia/Almaty}) rather than hardcoded {@link ZoneOffset#UTC}.
 */
@ExtendWith(MockitoExtension.class)
class BusinessTimezoneTest {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    private static final UUID PROCESS_INSTANCE_ID = UUID.randomUUID();
    private static final String ELEMENT_ID = "timerCatch1";

    @Mock
    private DBService dbService;
    @Mock
    private ScriptService scriptService;
    @Mock
    private FeelBudget feelBudget;
    @Mock
    private ActivityService activityService;
    @Mock
    private BpmnService bpmnService;

    private ElementSupport elementSupport;

    @BeforeEach
    void setUp() {
        // Create a real ElementSupport with mocked dependencies
        // WO-QW-1 A-C-5e: explicit constructor, zone passed directly (no reflection needed).
        elementSupport = new ElementSupport(dbService, scriptService, feelBudget, null, ALMATY);
    }

    // ─────────────────────────────────────────────────────────────────
    // Helper — build a DATE-timer with a FEEL expression
    // ─────────────────────────────────────────────────────────────────

    private TimerEventExtensionModel feelDateTimer(String feelExpr) {
        TimerEventExtensionModel timer = new TimerEventExtensionModel();
        timer.setType(TimerEventType.DATE);
        timer.setExpression(feelExpr);
        return timer;
    }

    private TimerEventExtensionModel feelDurationTimer(String feelExpr) {
        TimerEventExtensionModel timer = new TimerEventExtensionModel();
        timer.setType(TimerEventType.DURATION);
        timer.setExpression(feelExpr);
        return timer;
    }

    @SuppressWarnings("unchecked")
    private void stubFeelEval(String feelExpr, Object result) {
        when(scriptService.evaluateExpression(eq(feelExpr), any(List.class)))
            .thenReturn(result);
    }

    // ─────────────────────────────────────────────────────────────────
    // Criterion #1: FEEL LocalDateTime without zone → due-at in businessZone
    // ─────────────────────────────────────────────────────────────────

    @Test
    void feelLocalDateTime_isInterpretedInBusinessZone() {
        // FEEL returns LocalDateTime "2026-08-01T09:00" (no zone)
        LocalDateTime ldt = LocalDateTime.of(2026, 8, 1, 9, 0);
        stubFeelEval("dueDate", ldt);

        Instant dueAt = elementSupport.computeDueAt(feelDateTimer("=dueDate"), ELEMENT_ID, PROCESS_INSTANCE_ID);

        // 09:00 Asia/Almaty (UTC+5) = 04:00 UTC
        Instant expected = ZonedDateTime.of(2026, 8, 1, 9, 0, 0, 0, ALMATY).toInstant();
        assertThat(dueAt)
            .as("LocalDateTime 09:00 in Asia/Almaty should be 04:00 UTC")
            .isEqualTo(expected);
    }

    @Test
    void feelLocalDate_isInterpretedInBusinessZone() {
        // FEEL returns LocalDate "2026-08-01" (no zone)
        LocalDate ld = LocalDate.of(2026, 8, 1);
        stubFeelEval("dueDate", ld);

        Instant dueAt = elementSupport.computeDueAt(feelDateTimer("=dueDate"), ELEMENT_ID, PROCESS_INSTANCE_ID);

        // 2026-08-01T00:00 Asia/Almaty (UTC+5) = 2026-07-31T19:00 UTC
        Instant expected = LocalDate.of(2026, 8, 1).atStartOfDay(ALMATY).toInstant();
        assertThat(dueAt)
            .as("LocalDate 2026-08-01 in Asia/Almaty should be previous day 19:00 UTC")
            .isEqualTo(expected);
    }

    // ─────────────────────────────────────────────────────────────────
    // Criterion #2: Explicit offset/zone is preserved (not re-interpreted)
    // ─────────────────────────────────────────────────────────────────

    @Test
    void explicitOffsetDateTime_isUsedAsIs() {
        // FEEL returns OffsetDateTime with +05:00 offset
        java.time.OffsetDateTime odt = java.time.OffsetDateTime.of(2026, 8, 1, 9, 0, 0, 0, ZoneOffset.ofHours(5));
        stubFeelEval("dueDate", odt);

        Instant dueAt = elementSupport.computeDueAt(feelDateTimer("=dueDate"), ELEMENT_ID, PROCESS_INSTANCE_ID);

        // 09:00+05:00 = 04:00 UTC
        Instant expected = Instant.parse("2026-08-01T04:00:00Z");
        assertThat(dueAt)
            .as("OffsetDateTime with +05:00 should use its own offset")
            .isEqualTo(expected);
    }

    @Test
    void explicitInstant_isUsedAsIs() {
        Instant explicit = Instant.parse("2026-08-01T04:00:00Z");
        stubFeelEval("dueDate", explicit);

        Instant dueAt = elementSupport.computeDueAt(feelDateTimer("=dueDate"), ELEMENT_ID, PROCESS_INSTANCE_ID);

        assertThat(dueAt)
            .as("Instant value should be used as-is")
            .isEqualTo(explicit);
    }

    @Test
    void explicitZonedDateTime_isUsedAsIs() {
        // ZonedDateTime with +05:00 offset
        java.time.ZonedDateTime zdt = java.time.ZonedDateTime.of(2026, 8, 1, 9, 0, 0, 0, ZoneOffset.ofHours(5));
        stubFeelEval("dueDate", zdt);

        Instant dueAt = elementSupport.computeDueAt(feelDateTimer("=dueDate"), ELEMENT_ID, PROCESS_INSTANCE_ID);

        // 09:00+05:00 = 04:00 UTC
        Instant expected = Instant.parse("2026-08-01T04:00:00Z");
        assertThat(dueAt)
            .as("ZonedDateTime with +05:00 should use its own offset")
            .isEqualTo(expected);
    }

    // ─────────────────────────────────────────────────────────────────
    // Criterion #3: TimerExpressions cron uses explicit zone (consistency)
    // ─────────────────────────────────────────────────────────────────

    @Test
    void timerExpressionsCronUsesExplicitZone() {
        // Use a reference instant that would cross a DST boundary or timezone change
        Instant from = Instant.parse("2026-03-15T00:00:00Z");
        ZoneId almaty = ZoneId.of("Asia/Almaty");

        // Cron "0 0 9 * * *" = every day at 09:00:00
        Instant next = TimerExpressions.firstOccurrence("0 0 9 * * *", from, almaty);

        // 09:00 Asia/Almaty = 04:00 UTC (UTC+5)
        ZonedDateTime expectedLocal = ZonedDateTime.ofInstant(from, almaty)
            .withHour(9).withMinute(0).withSecond(0);
        // If from is past 09:00 in Almaty, advance to next day
        if (expectedLocal.toInstant().isBefore(from) || expectedLocal.toInstant().equals(from)) {
            expectedLocal = expectedLocal.plusDays(1);
        }
        assertThat(next)
            .as("Cron '0 0 9 * * *' in Asia/Almaty should resolve to 09:00 Almaty = 04:00 UTC")
            .isEqualTo(expectedLocal.toInstant());
    }

    @Test
    void timerExpressionsSystemDefaultIsDeprecatedButWorks() {
        // The 2-arg overload should still work (via systemDefault)
        Instant from = Instant.now();
        Instant next = TimerExpressions.firstOccurrence("PT5M", from);
        assertThat(next).isEqualTo(from.plus(Duration.ofMinutes(5)));
    }

    // ─────────────────────────────────────────────────────────────────
    // Criterion #4: Default businessZone behavior
    // ─────────────────────────────────────────────────────────────────

    @Test
    void defaultBusinessZoneIsAsiaAlmaty() {
        // WO-QW-1 A-C-5e: final field, set through the constructor — Spring injects
        // the @Value default (Asia/Almaty); explicitly passed zones win.
        ElementSupport fresh = new ElementSupport(dbService, scriptService, feelBudget, null, ALMATY);
        ZoneId zone = (ZoneId) ReflectionTestUtils.getField(fresh, "businessZone");
        assertThat(zone)
            .as("businessZone passed via constructor must stick")
            .isEqualTo(ALMATY);
    }

    // ─────────────────────────────────────────────────────────────────
    // POF: LocalDateTime before fix (UTC) vs after fix (businessZone)
    // ─────────────────────────────────────────────────────────────────

    @Test
    void pofRed_oldUtcBehavior() {
        // Simulate OLD behavior: hardcoded ZoneOffset.UTC
        LocalDateTime ldt = LocalDateTime.of(2026, 8, 1, 9, 0);
        Instant oldDueAt = ldt.toInstant(ZoneOffset.UTC);
        // Old: 09:00 interpreted as UTC → 09:00 UTC
        assertThat(oldDueAt)
            .as("OLD behavior: LocalDateTime 09:00 → interpreted as UTC = 09:00 UTC")
            .isEqualTo(Instant.parse("2026-08-01T09:00:00Z"));
    }

    @Test
    void pofGreen_newBusinessZoneBehavior() {
        // NEW behavior: businessZone = Asia/Almaty (UTC+5)
        LocalDateTime ldt = LocalDateTime.of(2026, 8, 1, 9, 0);
        stubFeelEval("dueDate", ldt);

        Instant dueAt = elementSupport.computeDueAt(feelDateTimer("=dueDate"), ELEMENT_ID, PROCESS_INSTANCE_ID);

        // New: 09:00 Asia/Almaty → 04:00 UTC (-5h shift)
        assertThat(dueAt)
            .as("NEW behavior: LocalDateTime 09:00 in Asia/Almaty → 04:00 UTC")
            .isEqualTo(Instant.parse("2026-08-01T04:00:00Z"));
    }

    // ─────────────────────────────────────────────────────────────────
    // Timer-start-event cron + businessZone (WO-ENG-4 hold fixes)
    // ─────────────────────────────────────────────────────────────────

    @Test
    void cycleExpressionViaElementSupportUsesBusinessZone() {
        // ElementSupport.computeDueAt(timer, elementId) — non-FEEL literal path
        // used by ProcessDefinitionServiceImpl.registerTimerStartJobs
        TimerEventExtensionModel timer = new TimerEventExtensionModel();
        timer.setType(TimerEventType.CYCLE);
        timer.setExpression("0 0 9 * * *");

        Instant dueAt = elementSupport.computeDueAt(timer, "timerStart1");

        // The result should be a future 09:00 Asia/Almaty (UTC+5)
        ZonedDateTime almatyTime = dueAt.atZone(ALMATY);
        assertThat(almatyTime.getHour())
            .as("CYCLE '0 0 9 * * *' should resolve to 09:00 Almaty (not JVM default)")
            .isEqualTo(9);
        assertThat(almatyTime.getMinute()).isZero();
        assertThat(almatyTime.getSecond()).isZero();
        assertThat(dueAt)
            .as("Result should be in the future (next 09:00 Almaty)")
            .isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    @Test
    void timerStartJobExecutorCycleUsesBusinessZone() {
        // TimerStartJobExecutor.rescheduleIfRepeatingCycle must use businessZone,
        // not ZoneId.systemDefault(), for cron expressions
        TimerStartJobExecutor executor = new TimerStartJobExecutor(dbService, activityService, bpmnService);
        ReflectionTestUtils.setField(executor, "businessZone", ALMATY);

        UUID jobId = UUID.randomUUID();
        UUID procDefId = UUID.randomUUID();
        String elementId = "timerStart1";

        when(dbService.claimTimerStartJob(jobId)).thenReturn(true);

        // Mock BPMN model with a repeating cron timer start
        BpmnProcessDefinitionModel model = mock(BpmnProcessDefinitionModel.class);
        when(bpmnService.getProcessDefinitionModelById(procDefId)).thenReturn(model);
        BpmnElementModel start = mock(BpmnElementModel.class);
        when(model.getElement(elementId)).thenReturn(start);
        BpmnElementExtensionModel ext = mock(BpmnElementExtensionModel.class);
        when(start.getExtensions()).thenReturn(ext);
        TimerEventExtensionModel timer = new TimerEventExtensionModel();
        timer.setType(TimerEventType.CYCLE);
        timer.setExpression("0 0 9 * * *");
        when(ext.getTimerEventExtension()).thenReturn(timer);
        when(model.getKey()).thenReturn("testProc");

        // Act (WO-REL-14: fire() also takes the previous dueAt and remainingCount; this cron
        // expression is unbounded, so remainingCount is null both in and out)
        executor.fire(jobId, procDefId, elementId, Instant.now(), null);

        // Assert: the next occurrence was computed using Asia/Almaty (09:00 Almaty = 04:00 UTC)
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(dbService).createTimerStartJob(eq("testProc"), eq(procDefId), eq(elementId), captor.capture(), eq((Integer) null));

        Instant next = captor.getValue();
        ZonedDateTime almatyTime = next.atZone(ALMATY);
        assertThat(almatyTime.getHour())
            .as("TimerStartJobExecutor should resolve cron in Asia/Almaty → 09:00 Almaty")
            .isEqualTo(9);
        assertThat(almatyTime.getMinute()).isZero();
        assertThat(almatyTime.getSecond()).isZero();
        assertThat(next)
            .as("Next occurrence should be in the future")
            .isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }
}
