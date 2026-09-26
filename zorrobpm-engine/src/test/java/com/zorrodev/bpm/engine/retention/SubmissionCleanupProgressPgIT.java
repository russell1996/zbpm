package com.zorrodev.bpm.engine.retention;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * WO-REL-50 (остаток F37, строка 341 аудита): полностью неудаляемая первая страница
 * submissions-очистки блокировала все следующие страницы на КАЖДОМ запуске job
 * ({@code pageDeleted == 0} прекращал весь проход целиком).
 *
 * <p>Неудаляемость — настоящий FK: {@code fk_process_submission_previous} (self-FK
 * {@code previous_submission_id} без CASCADE — осознанный retention-safety дизайн).
 * APPROVED-родитель, на которого ссылается PENDING-ребёнок, не удаляется никогда
 * (бизнес-инвариант: PENDING-ребёнок жив и ссылается), а не «в этом прогоне не повезло».
 * Два таких родителя забивают первую страницу (batchSize=2) целиком — хвост из трёх
 * удаляемых строк обязан всё равно уйти на том же проходе.
 */
public class SubmissionCleanupProgressPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionBatchProcessor batchProcessor;
    @Autowired BpmMetrics bpmMetrics;
    @Autowired ApplicationContext applicationContext;

    private UUID submitter;

    private static Timestamp ago(long seconds) {
        return Timestamp.from(Instant.now().minusSeconds(seconds));
    }

    private void insertSubmission(UUID id, String status, Instant submittedAt, UUID previousId) {
        jdbc.update(
            "INSERT INTO process_submission (id, bpmn, process_key, submitted_by, submitted_at, status, previous_submission_id) " +
            "VALUES (?, '<definitions/>', ?, ?, ?, ?, ?)",
            id, "rel50-" + id.toString().substring(0, 8), submitter,
            Timestamp.from(submittedAt), status, previousId);
    }

    private boolean submissionExists(UUID id) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM process_submission WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    @BeforeEach
    void setUp() {
        // Residue wipe: submissions reference users + themselves — delete in FK-safe order.
        // Timer tables first: background scheduler threads share this context and must not
        // see half-wiped definitions (same lesson as RetentionClaimDeleteRacePgIT).
        jdbc.execute("DELETE FROM timer_jobs");
        jdbc.execute("DELETE FROM timer_start_jobs");
        jdbc.execute("DELETE FROM message_subscriptions");
        jdbc.execute("DELETE FROM signal_subscriptions");
        jdbc.execute("DELETE FROM process_submission");
        // Our own submitter (submitted_by is NOT NULL + FK): unique per run, no clashes
        // with other classes sharing this PG volume.
        submitter = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO ui_users (id, username, password_hash, role, active, created_at, updated_at) " +
            "VALUES (?, ?, 'HASH', 'USER', true, now(), now())",
            submitter, "rel50-submitter-" + UUID.randomUUID());
    }

    @Test
    void allBadFirstPage_tailPagesStillProcessedSameRun() {
        Instant old = Instant.now().minus(Duration.ofDays(91));
        // Two PERMANENTLY undeletable APPROVED parents: each has a live PENDING child
        // pointing at it via previous_submission_id (self-FK, no cascade — the business
        // invariant "a newer pending resubmission references its predecessor" blocks the
        // parent delete for as long as the child is PENDING, i.e. indefinitely here).
        UUID bad1 = UUID.randomUUID();
        UUID bad2 = UUID.randomUUID();
        insertSubmission(bad1, "APPROVED", old, null);
        insertSubmission(bad2, "APPROVED", old.plusSeconds(3600), null);
        insertSubmission(UUID.randomUUID(), "PENDING", Instant.now(), bad1);
        insertSubmission(UUID.randomUUID(), "PENDING", Instant.now(), bad2);
        // Tail: three deletable APPROVED rows, younger than the bad heads (later pages).
        UUID good1 = UUID.randomUUID();
        UUID good2 = UUID.randomUUID();
        UUID good3 = UUID.randomUUID();
        insertSubmission(good1, "APPROVED", old.plusSeconds(7200), null);
        insertSubmission(good2, "APPROVED", old.plusSeconds(10800), null);
        insertSubmission(good3, "APPROVED", old.plusSeconds(14400), null);

        RetentionConfig config = new RetentionConfig();
        config.setTtlDays(90);
        config.setBatchSize(2);
        RetentionJob job = new RetentionJob(config, batchProcessor, bpmMetrics);

        // The pass must terminate (no infinite loop on the all-bad head) and drain the tail.
        assertTimeoutPreemptively(Duration.ofSeconds(30), job::run);

        // Discriminator: the tail is GONE on the same run despite the all-bad first page —
        // the old code broke out with pageDeleted == 0 and never touched these rows.
        assertThat(submissionExists(good1)).as("tail row 1 must be deleted same run").isFalse();
        assertThat(submissionExists(good2)).as("tail row 2 must be deleted same run").isFalse();
        assertThat(submissionExists(good3)).as("tail row 3 must be deleted same run").isFalse();
        // The stuck heads remain (still FK-blocked — nothing deleted them, nothing may),
        // but they are VISIBLE now instead of silently starving the queue.
        assertThat(submissionExists(bad1)).as("FK-blocked head 1 must remain").isTrue();
        assertThat(submissionExists(bad2)).as("FK-blocked head 2 must remain").isTrue();
        assertThat(stuckGaugeValue()).as("stuck gauge must report exactly the 2 blocked heads").isEqualTo(2);
        // Second run: same picture, no loop, no duplicates — progress is stable, not busy.
        assertTimeoutPreemptively(Duration.ofSeconds(30), job::run);
        assertThat(submissionExists(bad1)).isTrue();
        assertThat(submissionExists(bad2)).isTrue();
        assertThat(stuckGaugeValue()).isEqualTo(2);
    }

    /**
     * Reads the stuck gauge through the test-context MeterRegistry (the same registry the
     * autowired BpmMetrics was built on): the value equals the last set() call of the run.
     */
    private long stuckGaugeValue() {
        io.micrometer.core.instrument.Gauge g = applicationContext
            .getBean(io.micrometer.core.instrument.MeterRegistry.class)
            .find("zbpm.retention.submissions.stuck").gauge();
        assertThat(g).as("stuck gauge must be registered").isNotNull();
        return (long) g.value();
    }
}
