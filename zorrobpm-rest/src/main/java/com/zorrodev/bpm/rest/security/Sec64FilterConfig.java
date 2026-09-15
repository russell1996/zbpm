package com.zorrodev.bpm.rest.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * WO-SEC-64: registers the CSRF guard + backend security headers.
 *
 * <p>G-C notice (filed in agent-to-cto.md before code, WO-mandated): this IS a
 * security-config change — filter order/registration — but it is the direct
 * subject of S-2/S-3, there is no non-security way to do it. Kept minimal:
 * two new filters, existing chain/CORS untouched. {@code SecurityHeadersFilter}
 * runs at {@code HIGHEST_PRECEDENCE + 1} so headers land even on rejections;
 * {@code CsrfFilter} runs after {@code JwtAuthFilter} (plain component, default
 * {@code LOWEST_PRECEDENCE}) and never authenticates — it only checks transport.
 */
@Configuration
public class Sec64FilterConfig {

    @Value("${zorrobpm.cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilterRegistration(
            SecurityHeadersFilter filter) {
        FilterRegistrationBean<SecurityHeadersFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SecurityHeadersFilter.ORDER);
        registration.addUrlPatterns("/*");
        registration.setName("securityHeadersFilter");
        return registration;
    }

    @Bean
    public CsrfFilter csrfFilter() {
        return new CsrfFilter(allowedOrigins);
    }

    @Bean
    public FilterRegistrationBean<CsrfFilter> csrfFilterRegistration(CsrfFilter filter) {
        FilterRegistrationBean<CsrfFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(CsrfFilter.ORDER);
        registration.addUrlPatterns("/*");
        registration.setName("csrfFilter");
        return registration;
    }
}
