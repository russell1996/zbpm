package com.zorrodev.bpm.rest.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    @Value("${zorrobpm.cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // WO-QW-1 S-9: trim each entry (a "host, host" gap must not register a
        // space-prefixed origin) and fail closed on "*" — Spring rejects "*" with
        // allowCredentials(true) at runtime; an explicit throw names the cause.
        String[] origins = allowedOrigins.split(",");
        for (String o : origins) {
            if ("*".equals(o.trim())) {
                throw new IllegalStateException(
                    "WO-QW-1 S-9: wildcard CORS origin '*' is not allowed with allowCredentials(true) — list explicit origins");
            }
        }
        String[] cleaned = java.util.Arrays.stream(origins)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
        registry.addMapping("/**")
            .allowedOrigins(cleaned)
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            // WO-SEC-31a: explicit header list instead of "*" — narrower surface with credentials
            .allowedHeaders("Authorization", "Content-Type", "X-On-Behalf-Of", "Last-Event-ID")
            .allowCredentials(true);
    }
}
