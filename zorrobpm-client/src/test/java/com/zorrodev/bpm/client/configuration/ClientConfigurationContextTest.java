package com.zorrodev.bpm.client.configuration;

import com.zorrodev.bpm.client.ProcessDefinitionClient;
import com.zorrodev.bpm.client.QueryClient;
import com.zorrodev.bpm.client.RuntimeClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clients are built from the application's {@link RestClient.Builder} when there is one, so its
 * customisations (such as tracing headers) reach the engine calls, and fall back to a plain builder
 * otherwise.
 */
class ClientConfigurationContextTest {

    private static final String ENGINE = "http://engine.example";

    @Test
    void appliesTheApplicationBuilderToEveryClient() {
        List<HttpHeaders> sent = new ArrayList<>();
        ClientHttpRequestInterceptor recorder = (request, body, execution) -> {
            sent.add(HttpHeaders.copyOf(request.getHeaders()));
            MockClientHttpResponse response =
                new MockClientHttpResponse("<bpmn/>".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        };
        // A singleton on purpose: every client must still get a builder of its own.
        RestClient.Builder applicationBuilder = RestClient.builder()
            .requestInterceptor(recorder)
            .defaultHeader("sentry-trace", "from-the-application");

        try (AnnotationConfigApplicationContext context = context("zbpa_secret")) {
            context.registerBean(RestClient.Builder.class, () -> applicationBuilder);
            context.register(ClientConfiguration.class);
            context.refresh();
            context.getBean(RuntimeClient.class);
            context.getBean(QueryClient.class);

            context.getBean(ProcessDefinitionClient.class).getProcessDefinitionXml(UUID.randomUUID());
        }

        assertThat(sent).hasSize(1);
        HttpHeaders headers = sent.get(0);
        assertThat(headers.get(HttpHeaders.AUTHORIZATION)).containsExactly("Bearer zbpa_secret");
        assertThat(headers.get(HttpHeaders.ACCEPT)).containsExactly("application/json");
        assertThat(headers.get("sentry-trace")).containsExactly("from-the-application");
    }

    @Test
    void createsTheClientsWithoutAnApplicationBuilder() {
        try (AnnotationConfigApplicationContext context = context("")) {
            context.register(ClientConfiguration.class);
            context.refresh();

            assertThat(context.getBean(RuntimeClient.class)).isNotNull();
            assertThat(context.getBean(QueryClient.class)).isNotNull();
            assertThat(context.getBean(ProcessDefinitionClient.class)).isNotNull();
        }
    }

    private static AnnotationConfigApplicationContext context(String token) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MockPropertySource()
            .withProperty("app.m11s.zorrodev.bpm.url", ENGINE)
            .withProperty("app.m11s.zorrodev.bpm.token", token));
        return context;
    }
}
