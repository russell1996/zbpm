package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.service.PgRateLimiter;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * WO-SCALE-2: builds a {@link PgRateLimiter} backed by a FRESH in-memory H2
 * database per call, so plain unit tests of {@link RateLimitFilter} exercise
 * the REAL prod SQL path (same dialect-portable statements as production)
 * with full state isolation between tests.
 *
 * <p>Cluster-safety and race-freedom themselves are proven on real
 * PostgreSQL ({@code RateLimitClusterPgIT} in zorrobpm-engine); this helper
 * only keeps the pre-existing limit-semantics corpus green (criterion 2).
 */
final class TestRateLimitBuckets {

    private TestRateLimitBuckets() {
    }

    static PgRateLimiter create() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rl-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE rate_limit_bucket (bucket_key VARCHAR(255) PRIMARY KEY, "
            + "window_start TIMESTAMP WITH TIME ZONE NOT NULL, "
            + "tokens INT NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        return new PgRateLimiter(jdbc);
    }
}
