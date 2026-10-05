package com.zorrodev.bpm.engine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * WO-QW-13: shared isolation rules for the {@code @Tag("pg")} suite.
 *
 * <p>The whole PG suite shares ONE PostgreSQL container and every cached Spring context of it has
 * {@code @EnableScheduling} on (see {@code BPMConfiguration}). Two classes of cross-talk follow, and
 * both of them broke {@code test:pg} on unchanged code (pipelines 175956 / 175964):
 *
 * <ol>
 *   <li><b>A live background writer.</b> {@code TimerScheduler} polls every
 *       {@code zorrobpm.engine.timer-poll-interval-ms} (default 5 s) in EVERY context that does not
 *       park it, and it claims/fires/re-arms whatever the previous test class left behind. A test that
 *       counts "rows in {@code timer_jobs} that are due" therefore also counts rows another context is
 *       writing at that instant — {@code TimerBatchProcessorPgIT.notDueTimer_notInFindDue} failed with
 *       {@code Expecting empty but was: [<uuid>]} on a row it never created. Classes that assert on
 *       scheduler-owned tables park the poller with {@link #parkBackgroundTimerPollers}.</li>
 *   <li><b>Whole-table cleanups.</b> {@code DELETE FROM <whole table>} in a {@code @BeforeEach}
 *       deletes other classes' fixtures (so their assertions change) and races the background writer
 *       (a row created after the DELETE is still there when the test reads). Isolated tests clean by
 *       THEIR OWN key/id and read back only THEIR OWN rows.</li>
 * </ol>
 *
 * <p>Rule of thumb enforced here: <b>assert only about rows this test created</b> — scope the WHERE
 * clause by your own ids — and <b>delete children before parents</b>, scoped to your own key. Nothing
 * in this file depends on the table being empty, so a leftover from any neighbouring class (or from
 * the live poller) cannot change the verdict.
 */
public final class PgItIsolation {

    /**
     * Parks the background timer poller for the whole Spring context of the calling test class.
     *
     * <p>Same recipe as {@code TimerBatchIsolationPgIT} / {@code RepeatingTimerSemanticsPgIT}: the test
     * drives whatever it wants to drive explicitly, and no second thread writes the timer tables
     * underneath it. Test-only configuration — production keeps the 5 s default.
     */
    public static void parkBackgroundTimerPollers(DynamicPropertyRegistry registry) {
        registry.add("zorrobpm.engine.timer-poll-interval-ms", () -> "3600000");
    }

    // ------------------------------------------------------------------
    // cleanup: children before parents, always scoped to the caller's own key
    // ------------------------------------------------------------------

    /**
     * Removes everything ONE deployed definition owns, children first.
     *
     * <p>Order and column names are taken from the live schema ({@code \d} + {@code pg_constraint}),
     * not guessed: {@code incidents} has no {@code process_instance_id} (it hangs off
     * {@code activity_id}), {@code tokens} has none either (via {@code activities.token}), and
     * {@code process_submission} references {@code process_definitions} directly.
     *
     * <p>Order matters and is not cosmetic: {@code fk_process_instances__process_definition_id} makes
     * {@code process_instances} a child of {@code process_definitions}, so an instance left over from
     * an earlier run (the background poller starts one 5 minutes after a timer-start deployment) turns
     * the final {@code DELETE FROM process_definitions} into
     * {@code DataIntegrityViolationException: violates foreign key constraint
     * "fk_process_instances__process_definition_id"} — reproduced live, see the WO-QW-13 report.
     */
    public static void deleteDeployment(JdbcTemplate jdbc, String processKey) {
        String instancesOfKey = "SELECT id FROM process_instances WHERE process_definition_id IN "
            + "(SELECT id FROM process_definitions WHERE code = ?)";
        String activitiesOfKey = "SELECT id FROM activities WHERE process_instance_id IN ("
            + instancesOfKey + ")";

        // 1. runtime children of this definition's instances (no ON DELETE CASCADE in the schema)
        for (String table : new String[] {
            "timer_jobs", "message_subscriptions", "signal_subscriptions", "parallel_gateways",
            "user_tasks", "service_tasks", "variables", "events" }) {
            jdbc.update("DELETE FROM " + table + " WHERE process_instance_id IN (" + instancesOfKey + ")",
                processKey);
        }
        // incidents hang off activities; tokens hang off activities too (activities.token → tokens.id)
        jdbc.update("DELETE FROM incidents WHERE activity_id IN (" + activitiesOfKey + ")", processKey);
        jdbc.update("DELETE FROM tokens WHERE id IN (SELECT token FROM activities WHERE process_instance_id IN ("
            + instancesOfKey + "))", processKey);
        jdbc.update("DELETE FROM activities WHERE process_instance_id IN (" + instancesOfKey + ")", processKey);

        // 2. the instances themselves
        jdbc.update("DELETE FROM process_instances WHERE process_definition_id IN (" + instancesOfKey + ")",
            processKey);

        // 3. definition-scoped artifacts (keyed by the definition, none references an instance)
        jdbc.update("DELETE FROM message_start_subscriptions WHERE process_key = ?", processKey);
        jdbc.update("DELETE FROM timer_start_jobs WHERE process_key = ?", processKey);
        jdbc.update("DELETE FROM signal_start_subscriptions WHERE process_key = ?", processKey);
        jdbc.update("DELETE FROM element_artifact_binding WHERE process_definition_id IN "
            + "(SELECT id FROM process_definitions WHERE code = ?)", processKey);
        jdbc.update("DELETE FROM bpmn WHERE id IN (SELECT id FROM process_definitions WHERE code = ?)", processKey);
        // process_submission chains onto itself (fk_process_submission_previous) and onto
        // process_definitions — one statement for the whole chain of this key, children included.
        jdbc.update("DELETE FROM process_submission WHERE process_key = ? "
            + "OR approved_definition_id IN (SELECT id FROM process_definitions WHERE code = ?)",
            processKey, processKey);

        // 4. the version row itself — no child may point at it by now
        jdbc.update("DELETE FROM process_definitions WHERE code = ?", processKey);
    }

    // ------------------------------------------------------------------
    // timer rows: write own rows, read back only own rows
    // ------------------------------------------------------------------

    /** Inserts a catch-timer row for {@code activityId} with the given {@code dueAt}. */
    public static UUID insertCatchTimerJob(JdbcTemplate jdbc, UUID activityId, Instant dueAt) {
        return insertCatchTimerJob(jdbc, UUID.randomUUID(), activityId, dueAt);
    }

    /** Same, with a caller-chosen id (tests that must know the id before the insert, e.g. for cleanup). */
    public static UUID insertCatchTimerJob(JdbcTemplate jdbc, UUID id, UUID activityId, Instant dueAt) {
        jdbc.update(
            "INSERT INTO timer_jobs (id, activity_id, due_at, fired, created_at) VALUES (?, ?, ?, false, ?)",
            id, activityId, Timestamp.from(dueAt), Timestamp.from(Instant.now()));
        return id;
    }

    /**
     * The isolated form of the claim query: due rows <b>among the ids this test owns</b>.
     *
     * <p>Same predicate the scheduler uses ({@code fired = false AND due_at <= now()}), narrowed by
     * {@code id IN (...)}. A row any other test class (or the live poller) owns simply is not a
     * candidate, so the verdict cannot change under us — the whole point of WO-QW-13.
     */
    public static List<UUID> dueTimerIdsAmong(JdbcTemplate jdbc, Collection<UUID> ownIds) {
        if (ownIds.isEmpty()) {
            return List.of();
        }
        String placeholders = ownIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE id IN (" + placeholders + ") AND fired = false AND due_at <= now()",
            UUID.class, ownIds.toArray());
    }

    /** Deletes exactly the given timer rows — the self-cleanup counterpart of {@link #insertCatchTimerJob}. */
    public static void deleteTimerJobs(JdbcTemplate jdbc, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(", "));
        jdbc.update("DELETE FROM timer_jobs WHERE id IN (" + placeholders + ")", ids.toArray());
    }

    /** Every due unfired row in the table — for tests that deliberately assert on scheduler-wide state. */
    public static List<UUID> allDueTimerIds(JdbcTemplate jdbc) {
        return jdbc.queryForList(
            "SELECT id FROM timer_jobs WHERE fired = false AND due_at <= now()", UUID.class);
    }

    private PgItIsolation() {
    }
}