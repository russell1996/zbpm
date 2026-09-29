package com.zorrodev.bpm.client;

import com.sun.net.httpserver.HttpServer;
import com.zorrodev.bpm.client.configuration.ClientConfiguration;
import com.zorrodev.bpm.client.configuration.ClientCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-API-2 criteria 1+2: the auto-configured SDK client really calls a
 * protected endpoint with {@code Authorization: Bearer} (criterion 1), and a
 * rotated credential applies to subsequent calls without rebuilding the client
 * (criterion 2).
 *
 * <p>Real HTTP against a JDK {@link HttpServer} (no mocks of the client itself):
 * the server records the received {@code Authorization} header and answers
 * {@code {"id": ...}} for {@code POST /user-tasks/{id}/claim}.
 */
class ClientAuthorizationIT {

    private HttpServer server;
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private AnnotationConfigApplicationContext context;

    /** Test provider BEFORE the auto-config so the default no-op backs off. */
    @Configuration
    static class TestCredentials {
        @Bean
        ClientCredentialsProvider provider(AtomicReference<String> tokenHolder) {
            return tokenHolder::get;
        }

        @Bean
        AtomicReference<String> tokenHolder() {
            return new AtomicReference<>("tok-1");
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user-tasks", exchange -> {
            List<String> auth = exchange.getRequestHeaders().get("Authorization");
            seenAuthorization.add(auth == null ? "<absent>" : auth.get(0));
            String id = exchange.getRequestURI().getPath().split("/")[2];
            byte[] body = ("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("test", Map.of("app.m11s.zorrodev.bpm.url", baseUrl)));
        context.register(TestCredentials.class, ClientConfiguration.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void autoConfiguredClient_sendsBearerAuthorization() {
        RuntimeClient client = context.getBean(RuntimeClient.class);
        UUID id = UUID.randomUUID();

        client.claimUserTask(id);

        assertThat(seenAuthorization)
            .as("auto-configured client must send Authorization: Bearer (WO-API-2 criterion 1)")
            .containsExactly("Bearer tok-1");
    }

    @Test
    void rotatedCredential_appliesWithoutRebuildingClient() {
        RuntimeClient client = context.getBean(RuntimeClient.class);
        client.claimUserTask(UUID.randomUUID());
        assertThat(seenAuthorization).containsExactly("Bearer tok-1");

        tokenHolder().set("tok-2");

        client.claimUserTask(UUID.randomUUID());

        assertThat(seenAuthorization)
            .as("rotated token must apply on the SAME client instance (WO-API-2 criterion 2)")
            .containsExactly("Bearer tok-1", "Bearer tok-2");
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<String> tokenHolder() {
        return context.getBean(AtomicReference.class);
    }
}
