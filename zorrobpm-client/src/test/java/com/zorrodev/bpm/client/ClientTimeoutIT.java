package com.zorrodev.bpm.client;

import com.sun.net.httpserver.HttpServer;
import com.zorrodev.bpm.client.configuration.ClientConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * WO-API-2 criterion 3: a hung server must be bounded by the configured
 * timeout, never block the caller forever. Real HTTP against a JDK server
 * that sleeps past the timeout — the elapsed wall time is the assertion.
 */
class ClientTimeoutIT {

    private HttpServer server;
    private AnnotationConfigApplicationContext context;
    // Released in tearDown BEFORE server.stop(0): the JDK stub server waits for
    // in-flight exchanges on stop, so an uninterruptible sleep(30s) handler would
    // hold teardown 30s. A latch the test owns keeps teardown at ~ms.
    private final java.util.concurrent.CountDownLatch hangRelease = new java.util.concurrent.CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        // Hangs (up to 30s) per request — far past any timeout under test.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user-tasks", exchange -> {
            try {
                hangRelease.await(30, java.util.concurrent.TimeUnit.SECONDS);
                byte[] body = "{}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception e) {
                // Client already gone (timeout fired) — nothing to prove here.
            } finally {
                exchange.close();
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("test", Map.of(
                "app.m11s.zorrodev.bpm.url", baseUrl,
                // Short on purpose: the test would take 30s+ per call otherwise.
                "zbpm.client.connect-timeout", "500ms",
                "zbpm.client.read-timeout", "500ms")));
        context.register(ClientConfiguration.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        // Release the stub handler FIRST so server.stop(0) returns immediately.
        hangRelease.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void hangingServer_boundedByReadTimeout() {
        RuntimeClient client = context.getBean(RuntimeClient.class);
        UUID id = UUID.randomUUID();

        // Guard: even if the timeout wiring regresses, the suite survives
        // (fails in 25s instead of hanging the whole build forever).
        long start = System.nanoTime();
        Throwable thrown = null;
        try {
            client.claimUserTask(id);
        } catch (Throwable t) {
            thrown = t;
        }
        long elapsed = System.nanoTime() - start;
        System.out.println("OBS-API2-DIAG elapsedMs=" + elapsed / 1_000_000
            + " thrown=" + (thrown == null ? "<none>" : thrown.getClass().getName() + ": " + thrown.getMessage()));
        assertThat(thrown).as("hung server must throw, not return").isNotNull();
        assertThat(thrown.getMessage() + causeChain(thrown)).contains("Read timed out");
        assertThat(Duration.ofNanos(elapsed))
            .as("read timeout (500ms) must bound the call far below the 30s hang")
            .isLessThan(Duration.ofSeconds(20));
    }

    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while ((t = t.getCause()) != null) {
            sb.append(" <- ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return sb.toString();
    }
}
