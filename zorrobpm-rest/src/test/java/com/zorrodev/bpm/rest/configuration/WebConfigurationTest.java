package com.zorrodev.bpm.rest.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-QW-1 S-9: CORS origins must be trimmed and "*" must fail closed
 * (Spring rejects "*" with allowCredentials at runtime — the explicit throw
 * names the cause instead of a cryptic startup failure).
 */
class WebConfigurationTest {

    private WebConfiguration config(String origins) {
        WebConfiguration c = new WebConfiguration();
        ReflectionTestUtils.setField(c, "allowedOrigins", origins);
        return c;
    }

    @Test
    void trimsSpacesAroundOrigins() {
        CorsRegistry registry = mock(CorsRegistry.class);
        CorsRegistration registration = mock(CorsRegistration.class, org.mockito.Mockito.RETURNS_SELF);
        when(registry.addMapping(anyString())).thenReturn(registration);

        config("http://localhost:5173, http://localhost:3000").addCorsMappings(registry);

        var captor = org.mockito.ArgumentCaptor.forClass(String[].class);
        verify(registration).allowedOrigins(captor.capture());
        assertThat(captor.getValue()).containsExactly("http://localhost:5173", "http://localhost:3000");
    }

    @Test
    void wildcardOrigin_failsClosed() {
        CorsRegistry registry = mock(CorsRegistry.class);

        assertThatThrownBy(() -> config("*").addCorsMappings(registry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("wildcard");
    }

    @Test
    void wildcardAmongOthers_failsClosed() {
        CorsRegistry registry = mock(CorsRegistry.class);

        assertThatThrownBy(() -> config("http://localhost:5173, *").addCorsMappings(registry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("wildcard");
    }
}
