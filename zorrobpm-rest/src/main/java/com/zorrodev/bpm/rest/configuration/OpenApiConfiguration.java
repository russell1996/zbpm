package com.zorrodev.bpm.rest.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds a "bearerAuth" security scheme so Swagger UI shows an Authorize button. JwtAuthFilter
 * already prefers an {@code Authorization: Bearer <token>} header over the httpOnly cookie
 * (see JwtAuthFilter#extractToken), so pasting the token from /auth/login's response body here
 * is enough to try endpoints directly from Swagger UI without a separate SPA login.
 */
@Configuration
public class OpenApiConfiguration {

    private static final String SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
            .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME))
            .components(new Components().addSecuritySchemes(SCHEME_NAME,
                new SecurityScheme()
                    .name(SCHEME_NAME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Paste the token from POST /auth/login's response body (no \"Bearer \" prefix needed).")));
    }
}
