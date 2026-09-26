package com.zorrodev.bpm.engine.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-77 (S-4): {@link JsonSchemaValidator} must not resolve remote
 * {@code $ref}s. VARIABLE_SCHEMA artifacts are self-contained — the project
 * has no use case for remote refs, so the policy is local-only: any
 * {@code $ref} (also {@code $recursiveRef}/{@code $dynamicRef}) that is not
 * a pure local JSON pointer ({@code #...}) is rejected BEFORE the networknt
 * factory ever sees the schema, plus the factory itself is built with a
 * disallow loader as defense-in-depth.
 *
 * <p>POF link (G-N): every test goes through the public
 * {@code validateSchema} end to end. Reverting the fix (plain
 * {@code JsonSchemaFactory.getInstance(...)} + no pre-scan) makes the
 * remote-ref tests RED — the factory fetches the controlled server (hit
 * counter &gt; 0) and reports the schema as valid.
 */
class JsonSchemaValidatorTest {

    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    @Test
    void plainValidSchema_accepted() {
        assertThat(validator.validateSchema("{\"type\":\"object\"}")).isEmpty();
    }

    @Test
    void localPointerRef_accepted() {
        String schema = "{\"$defs\":{\"name\":{\"type\":\"string\"}},"
            + "\"properties\":{\"n\":{\"$ref\":\"#/$defs/name\"}},\"type\":\"object\"}";
        assertThat(validator.validateSchema(schema)).isEmpty();
    }

    /**
     * Criterion 1: schema with a $ref to a controlled local HTTP server must
     * be rejected AND the server must see zero requests (no SSRF probe).
     */
    @Test
    void remoteHttpRef_blockedWithoutNetworkRequest() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/evil-schema", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"type\":\"string\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/schema+json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String schema = "{\"properties\":{\"p\":{\"$ref\":\"http://127.0.0.1:"
                + port + "/evil-schema\"}},\"type\":\"object\"}";
            assertThat(validator.validateSchema(schema))
                .as("remote $ref must be rejected")
                .isNotEmpty();
            assertThat(hits.get())
                .as("no request must reach the remote host")
                .isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteSchemeRefs_rejected() {
        String[] remoteRefs = {
            "https://example.com/schema.json",
            "http://internal-host.local/secret",
            "file:///etc/passwd",
            "ftp://files.internal/x.json",
            "jar:http://evil/x.jar!/schema.json",
            "//protocol-relative-host/x.json",
            "other.json#/$defs/x",
            "subdir/other.json"
        };
        for (String ref : remoteRefs) {
            String schema = "{\"properties\":{\"p\":{\"$ref\":\"" + ref + "\"}}}";
            assertThat(validator.validateSchema(schema))
                .as("remote/non-local $ref must be rejected: %s", ref)
                .isNotEmpty();
        }
    }

    @Test
    void remoteRecursiveAndDynamicRefs_rejected() {
        assertThat(validator.validateSchema(
            "{\"$recursiveRef\":\"http://127.0.0.1:9/x\"}")).isNotEmpty();
        assertThat(validator.validateSchema(
            "{\"$dynamicRef\":\"http://127.0.0.1:9/x\"}")).isNotEmpty();
        assertThat(validator.validateSchema(
            "{\"properties\":{\"p\":{\"$recursiveRef\":\"#/properties/p\"}}}")).isEmpty();
    }

    @Test
    void malformedSchema_stillRejected() {
        assertThat(validator.validateSchema("{not json")).isNotEmpty();
    }
}
