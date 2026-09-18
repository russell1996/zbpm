package com.zorrodev.bpm.engine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * WO-SCALE-2: cluster-safe rate-limit bucket backed by PostgreSQL.
 * Replaces per-instance Caffeine caches in {@code RateLimitFilter},
 * {@code PasswordResetRateLimiter}, and {@code MailActionRateLimiter}.
 *
 * <p>All rate-limit state lives in {@code rate_limit_bucket} (one table,
 * namespace-distinguished keys). Under N replicas every replica shares
 * the same counters — the effective limit stays at the configured value
 * instead of being multiplied by N.
 *
 * <p>Atomicity: every consume-decision is exactly ONE conditional
 * {@code UPDATE} executed against the row lock ({@code window_start}/{@code tokens}
 * are only ever changed by that single statement, guarded by
 * {@code WHERE bucket_key = ? AND (window_start <= ? OR tokens > 0)}), so two
 * concurrent consumers can never both pass at the capacity boundary — the
 * loser sees {@code updated == 0}. First touch of a key is an
 * {@code INSERT}; on a concurrent-insert conflict the loser retries the
 * guarded {@code UPDATE} once and converges. No read-then-write of the
 * counter itself: the decision comes from the statement's affected-row
 * count, never from a preceding {@code SELECT}.
 *
 * <p>Why not the single-statement {@code INSERT ... ON CONFLICT ... DO UPDATE ...
 * RETURNING} the WO prescribes: H2 2.4.240 (our unit-test DB) has NO
 * {@code ON CONFLICT} grammar at all — proven by a direct JDBC probe
 * (plain, {@code RETURNING}, and {@code MODE=PostgreSQL} variants all fail
 * with syntax error). A second probe showed the WO's {@code key} column
 * name is a reserved word in H2 (Liquibase creates it as quoted uppercase
 * {@code "KEY"} there, lowercase {@code key} in PostgreSQL), so the same
 * SQL cannot address it on both DBs — the column is named
 * {@code bucket_key} instead (changeset 103 comment records this).
 * The guarded-UPDATE form above is byte-identical SQL on both databases,
 * and the no-extra-pass property it exists for is proven by
 * {@code RateLimitClusterPgIT} on real PostgreSQL (criteria 1 and 3),
 * not by reasoning.
 *
 * <p>Window boundaries are computed in Java ({@link Instant}) and bound as
 * {@link Timestamp} — portable on both databases (the runbook rule: PG
 * cannot infer a type for a bound {@code Instant}, and neither DB accepts
 * an interval passed as a string parameter).
 */
@Slf4j
@Component
public class PgRateLimiter {

    private static final String GUARDED_CONSUME =
        "UPDATE rate_limit_bucket SET "
        + "tokens = CASE WHEN window_start <= ? THEN ? - 1 "
        + "WHEN tokens > 0 THEN tokens - 1 ELSE tokens END, "
        + "window_start = CASE WHEN window_start <= ? THEN ? ELSE window_start END, "
        + "updated_at = ? "
        + "WHERE bucket_key = ? AND (window_start <= ? OR tokens > 0)";

    private static final String INSERT_FIRST_TOUCH =
        "INSERT INTO rate_limit_bucket (bucket_key, window_start, tokens, updated_at) "
        + "VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public PgRateLimiter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Attempt to consume one token from the bucket identified by {@code key}.
     *
     * @param key           namespaced rate-limit key (e.g. {@code "login:ip:1.2.3.4"} or {@code "reset:email:user@x"})
     * @param capacity      max tokens in the window
     * @param windowSeconds window duration in seconds
     * @return 0 if allowed, else seconds to wait before the next attempt
     */
    public long tryConsume(String key, int capacity, int windowSeconds) {
        if (capacity <= 0) {
            return windowSeconds;
        }
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        Timestamp cutoff = Timestamp.from(now.minusSeconds(windowSeconds));
        try {
            int updated = guardedConsume(key, capacity, nowTs, cutoff);
            if (updated == 0) {
                // Either the key is new (no row) or the window is exhausted.
                // Try the first-touch insert; a concurrent inserter wins the
                // race here, in which case retry the guarded update once.
                try {
                    jdbcTemplate.update(INSERT_FIRST_TOUCH, key, nowTs, capacity - 1, nowTs);
                    return 0L;
                } catch (DuplicateKeyException firstTouchRace) {
                    updated = guardedConsume(key, capacity, nowTs, cutoff);
                }
            }
            return updated == 1 ? 0L : windowSeconds;
        } catch (Exception e) {
            log.error("PgRateLimiter failed for key {}: {}", key, e.getMessage());
            // Fail-closed on DB error: reject to be safe (matches WO-A-03 contract).
            return windowSeconds;
        }
    }

    private int guardedConsume(String key, int capacity, Timestamp nowTs, Timestamp cutoff) {
        return jdbcTemplate.update(GUARDED_CONSUME,
            cutoff, capacity, cutoff, nowTs, nowTs, key, cutoff);
    }

    /**
     * Roll back one consumed token — used when the per-account check rejects
     * after the per-IP check passed (the IP token should be returned).
     */
    public void rollback(String key, int capacity) {
        try {
            jdbcTemplate.update(
                "UPDATE rate_limit_bucket SET tokens = LEAST(tokens + 1, ?), updated_at = ? "
                + "WHERE bucket_key = ?",
                capacity, Timestamp.from(Instant.now()), key);
        } catch (Exception e) {
            log.warn("PgRateLimiter rollback failed for key {}: {}", key, e.getMessage());
        }
    }

    /** For tests — removes all bucket rows. */
    public void reset() {
        jdbcTemplate.update("DELETE FROM rate_limit_bucket");
    }

    /** Deletes bucket rows not touched for over a day. Returns the deleted count. */
    int deleteExpired() {
        return jdbcTemplate.update(
            "DELETE FROM rate_limit_bucket WHERE updated_at < CURRENT_TIMESTAMP - INTERVAL '1' DAY");
    }
}
