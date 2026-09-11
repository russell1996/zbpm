package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.repository.IdempotencyRecordRepository;
import com.zorrodev.bpm.engine.service.AdvisoryDeployLock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WO-REL-21: registers {@link IdempotencyFilter} right after the rate limiter —
 * before auth, controllers, everything else. Auth interplay is handled inside the
 * filter itself (401/403 are never cached), so no security-file ordering is touched
 * (G-C). Exact paths only (never {@code /dmn/{id}/evaluate}, never GET).
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
                                              TransactionTemplate transactionTemplate) {
        return new IdempotencyFilter(repository, advisoryLock, transactionTemplate);
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns(
            "/process-instances",
            "/deployments",
            "/dmn",
            "/forms",
            "/auth/register"
        );
        registration.setName("idempotencyFilter");
        return registration;
    }
}
