package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.repository.IdempotencyRecordRepository;
import com.zorrodev.bpm.engine.service.AdvisoryDeployLock;
import com.zorrodev.bpm.engine.service.IdempotencyReplayAuthorizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WO-REL-21/32: registers {@link IdempotencyFilter} AFTER auth (JwtAuth HIGHEST+20,
 * Csrf HIGHEST+25) so a cache-hit still re-validates the credential (F04 — auth
 * before replay) and keys on stable actor_id (F05). Order HIGHEST+30 — after
 * auth, before controllers. Exact paths + mutation wildcards.
 */
@Configuration
public class IdempotencyFilterConfig {

    /**
     * The filter bean itself (constructor mirrors {@code RateLimitFilterConfig}:
     * explicit {@code new}, no component scan magic — a missing bean would fail
     * every rest context at startup).
     */
    @Bean
    public IdempotencyFilter idempotencyFilter(IdempotencyRecordRepository repository,
                                              AdvisoryDeployLock advisoryLock,
                                              TransactionTemplate transactionTemplate,
                                              IdempotencyReplayAuthorizer replayAuthorizer) {
        return new IdempotencyFilter(repository, advisoryLock, transactionTemplate, replayAuthorizer);
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        registration.addUrlPatterns(
            "/process-instances",
            "/process-instances/*",
            "/deployments",
            "/deployments/*",
            "/dmn",
            "/dmn/*",
            "/forms",
            "/forms/*",
            "/auth/register",
            "/user-tasks/*",
            "/service-tasks/*",
            "/incidents/*",
            // WO-DIFF-5: POST /messages/publish is idempotent (create-like) — without this
            // the filter never executes for it and the isIdempotentPath registration is dead.
            "/messages",
            "/messages/*"
        );
        registration.setName("idempotencyFilter");
        return registration;
    }
}
