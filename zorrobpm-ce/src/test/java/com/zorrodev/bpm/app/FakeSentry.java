package com.zorrodev.bpm.app;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

/**
 * A local stand-in for Sentry: accepts envelopes on any path and keeps their text, so a test can
 * look at exactly what would have left the process.
 */
public final class FakeSentry implements AutoCloseable {

    private final HttpServer server;
    private final List<String> envelopes = new CopyOnWriteArrayList<>();

    public FakeSentry() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
            InputStream in = new ByteArrayInputStream(body);
            if (encoding != null && encoding.contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            envelopes.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    public String dsn() {
        return "http://public@127.0.0.1:" + server.getAddress().getPort() + "/1";
    }

    /** Envelopes carrying an error event (as opposed to transactions, sessions or client reports). */
    public List<String> events() {
        return envelopes.stream().filter(e -> e.contains("\"type\":\"event\"")).toList();
    }

    public List<String> transactions() {
        return envelopes.stream().filter(e -> e.contains("\"type\":\"transaction\"")).toList();
    }

    public List<String> all() {
        return List.copyOf(envelopes);
    }

    public void clear() {
        envelopes.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
