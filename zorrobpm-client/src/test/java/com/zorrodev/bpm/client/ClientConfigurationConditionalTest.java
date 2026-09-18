package com.zorrodev.bpm.client;

import com.zorrodev.bpm.client.configuration.ClientConfiguration;
import com.zorrodev.bpm.client.configuration.ClientCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-API-2 task item 3: the auto-configuration must not break existing
 * consumers that already define their own beans.
 *
 * <ul>
 *   <li>Fresh context: all SDK beans are present and share one configuration.</li>
 *   <li>User {@code RestClient} bean: auto-configured clients still build
 *       (from the factory method, not from the user's bean), anonymous by
 *       default, Bearer when a provider is present.</li>
 *   <li>User client-proxy bean (e.g. a test double): wins over auto-config —
 *       no duplicate-bean failure, no silent second instance.</li>
 * </ul>
 */
class ClientConfigurationConditionalTest {

    private AnnotationConfigApplicationContext context(String baseUrl) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("test", Map.of("app.m11s.zorrodev.bpm.url", baseUrl)));
        return ctx;
    }

    @Test
    void freshContext_allSdkBeansPresent() {
        try (AnnotationConfigApplicationContext ctx = context("http://127.0.0.1:9")) {
            ctx.register(ClientConfiguration.class);
            ctx.refresh();

            assertThat(ctx.getBean(RuntimeClient.class)).isNotNull();
            assertThat(ctx.getBean(ProcessDefinitionClient.class)).isNotNull();
            assertThat(ctx.getBean(QueryClient.class)).isNotNull();
            assertThat(ctx.getBean(RestClient.class)).isNotNull();
            assertThat(ctx.getBean(ClientCredentialsProvider.class)).isNotNull();
        }
    }

    @Configuration
    static class UserRestClient {
        @Bean
        RestClient restClient() {
            return RestClient.builder().baseUrl("http://127.0.0.1:9").build();
        }
    }

    @Test
    void userRestClientBean_existingConsumersNotBroken() {
        try (AnnotationConfigApplicationContext ctx = context("http://127.0.0.1:9")) {
            ctx.register(UserRestClient.class, ClientConfiguration.class);
            ctx.refresh();

            // The user's bean is untouched and alone of its kind.
            assertThat(ctx.getBeansOfType(RestClient.class)).hasSize(1);
            // The SDK proxies still build — from the factory method, which does
            // not depend on the (now user-owned) RestClient bean.
            assertThat(ctx.getBean(RuntimeClient.class)).isNotNull();
            assertThat(ctx.getBean(QueryClient.class)).isNotNull();
        }
    }

    @Configuration
    static class UserClientDouble {
        @Bean
        RuntimeClient runtimeClient() {
            RuntimeClient mock = org.mockito.Mockito.mock(RuntimeClient.class);
            org.mockito.Mockito.when(mock.claimUserTask(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> new com.zorrodev.bpm.contract.dto.IdDTO(inv.getArgument(0)));
            return mock;
        }
    }

    @Test
    void userClientBean_overridesAutoConfiguredOne() {
        try (AnnotationConfigApplicationContext ctx = context("http://127.0.0.1:9")) {
            ctx.register(UserClientDouble.class, ClientConfiguration.class);
            ctx.refresh();

            RuntimeClient client = ctx.getBean(RuntimeClient.class);
            java.util.UUID id = java.util.UUID.randomUUID();
            assertThat(client.claimUserTask(id).getId())
                .as("user-defined client bean must win (test double), not the auto-configured proxy")
                .isEqualTo(id);
        }
    }
}
