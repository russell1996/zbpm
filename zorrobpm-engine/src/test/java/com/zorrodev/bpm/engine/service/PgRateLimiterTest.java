package com.zorrodev.bpm.engine.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SCALE-2: unit semantics of {@link PgRateLimiter} on H2 (plain JUnit, no
 * Spring). The SQL is dialect-portable by construction (proven identical on
 * both DBs), so H2 checks the state machine deterministically — including
 * window expiry via backdated rows instead of real sleeps (no timing
 * flakes). Concurrency and cross-instance visibility are proven on real
 * PostgreSQL by {@code RateLimitClusterPgIT}.
 */
class PgRateLimiterTest {

    private JdbcTemplate jdbc;
    private PgRateLimiter limiter;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pgrl-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE rate_limit_bucket (bucket_key VARCHAR(255) PRIMARY KEY, "
            + "window_start TIMESTAMP WITH TIME ZONE NOT NULL, "
            + "tokens INT NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        limiter = new PgRateLimiter(jdbc);
    }

    private Integer tokens(String key) {
        return jdbc.queryForObject(
            "SELECT tokens FROM rate_limit_bucket WHERE bucket_key = ?", Integer.class, key);
    }

    @Test
    void consumeUpToCapacity_thenLimited() {
        String key = "k-" + UUID.randomUUID();
        assertThat(limiter.tryConsume(key, 3, 3600)).isEqualTo(0L);
        assertThat(limiter.tryConsume(key, 3, 3600)).isEqualTo(0L);
        assertThat(limiter.tryConsume(key, 3, 3600)).isEqualTo(0L);
        // 4th exceeds capacity 3 -> limited, returns the window as wait time
        assertThat(limiter.tryConsume(key, 3, 3600)).isEqualTo(3600L);
        assertThat(tokens(key)).isEqualTo(0);
    }

    @Test
    void firstTouch_consumesOneTokenImmediately() {
        String key = "k-" + UUID.randomUUID();
        assertThat(limiter.tryConsume(key, 5, 3600)).isEqualTo(0L);
        assertThat(tokens(key)).isEqualTo(4);
    }

    @Test
    void expiredWindow_resetsBucket() {
        String key = "k-" + UUID.randomUUID();
        assertThat(limiter.tryConsume(key, 2, 3600)).isEqualTo(0L);
        assertThat(limiter.tryConsume(key, 2, 3600)).isEqualTo(0L);
        assertThat(limiter.tryConsume(key, 2, 3600)).isEqualTo(3600L);
        // Backdate the window past expiry instead of sleeping (deterministic).
        jdbc.update("UPDATE rate_limit_bucket SET window_start = ? WHERE bucket_key = ?",
            Timestamp.from(Instant.now().minusSeconds(7200)), key);
        assertThat(limiter.tryConsume(key, 2, 3600)).isEqualTo(0L);
        assertThat(tokens(key)).isEqualTo(1);
    }

    @Test
    void rollback_returnsToken_cappedAtCapacity() {
        String key = "k-" + UUID.randomUUID();
        assertThat(limiter.tryConsume(key, 2, 3600)).isEqualTo(0L);
        limiter.rollback(key, 2);
        assertThat(tokens(key)).isEqualTo(2);
        // Rolling back a full bucket must not overfill it.
        limiter.rollback(key, 2);
        assertThat(tokens(key)).isEqualTo(2);
    }

    @Test
    void zeroCapacity_neverAllowed() {
        String key = "k-" + UUID.randomUUID();
        assertThat(limiter.tryConsume(key, 0, 3600)).isEqualTo(3600L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_bucket", Integer.class))
            .isEqualTo(0);
    }

    @Test
    void namespacedBeans_sharingOneLimiter_keepSeparateQuotas() {
        // WO-REG-3 invariant under WO-SCALE-2, end to end through real SQL:
        // the forgot-password bean and the registration bean share one
        // PgRateLimiter/table but must not share bucket rows.
        PgRateLimiter shared = new PgRateLimiter(jdbc);
        PasswordResetRateLimiter reset = new PasswordResetRateLimiter(shared);
        reset.setEmailCapacity(1);
        reset.setEmailWindowSeconds(3600);
        PasswordResetRateLimiter register = new PasswordResetRateLimiter(shared);
        register.setKeyPrefix("register:");
        register.setEmailCapacity(1);
        register.setEmailWindowSeconds(3600);

        String victim = "victim-" + UUID.randomUUID() + "@x.y";
        assertThat(reset.tryAcquireForEmail(victim)).isTrue();
        assertThat(reset.tryAcquireForEmail(victim)).isFalse();
        // Registration budget for the same address is untouched.
        assertThat(register.tryAcquireForEmail(victim)).isTrue();
        assertThat(register.tryAcquireForEmail(victim)).isFalse();
        // And the reverse: registration storm does not eat reset budget.
        String other = "other-" + UUID.randomUUID() + "@x.y";
        assertThat(register.tryAcquireForEmail(other)).isTrue();
        assertThat(register.tryAcquireForEmail(other)).isFalse();
        assertThat(reset.tryAcquireForEmail(other)).isTrue();
    }

    @Test
    void deleteExpired_removesOnlyStaleRows() {
        String stale = "stale-" + UUID.randomUUID();
        String fresh = "fresh-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO rate_limit_bucket (bucket_key, window_start, tokens, updated_at) VALUES (?, ?, ?, ?)",
            stale, Timestamp.from(now.minusSeconds(3 * 86400)), 0, Timestamp.from(now.minusSeconds(2 * 86400)));
        jdbc.update("INSERT INTO rate_limit_bucket (bucket_key, window_start, tokens, updated_at) VALUES (?, ?, ?, ?)",
            fresh, Timestamp.from(now), 3, Timestamp.from(now));
        assertThat(limiter.deleteExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_bucket WHERE bucket_key = ?", Integer.class, stale))
            .isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_bucket WHERE bucket_key = ?", Integer.class, fresh))
            .isEqualTo(1);
    }
}
