package com.zorrodev.bpm.handler.boot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * WO-TEST-10: минимальный клиент Docker Engine API через unix-сокет (без docker CLI).
 *
 * <p>Зачем: chaos-тесты гоняются ВНУТРИ maven-контейнера
 * ({@code ci/run-chaos-tests.sh}), в образе {@code maven:3.9.9-eclipse-temurin-21}
 * НЕТ бинарника {@code docker} — {@code new ProcessBuilder("docker", ...)} упал бы с
 * {@code "No such file or directory"}. Сокет при этом проброшен
 * ({@code -v /var/run/docker.sock:/var/run/docker.sock}), так что Engine API доступен
 * напрямую. Только JDK: {@code java.net.Socket} + {@code UnixDomainSocketAddress}
 * (Java 16+) + ручной HTTP/1.1 — новых зависимостей ноль.
 *
 * <p>Покрыты ровно нужные тесты вызовы: create/start/kill/inspect/remove контейнеров.
 * Ответы парсятся минимально (status line + Content-Length/chunked body); этого
 * достаточно для управляющих вызовов (204 No Content / JSON inspect).
 */
final class ChaosDocker {

    private final Path socketPath;

    ChaosDocker() {
        this(Path.of("/var/run/docker.sock"));
    }

    ChaosDocker(Path socketPath) {
        this.socketPath = socketPath;
    }

    /** Сырой ответ: статус + тело. */
    record Response(int status, String body) {
    }

    Response request(String method, String path, String jsonBody) throws IOException {
        byte[] bodyBytes = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: localhost\r\n");
        head.append("Connection: close\r\n");
        if (bodyBytes.length > 0 || method.equals("POST")) {
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        }
        head.append("\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);

        // POF-ловушка run6: у Unix-domain SocketChannel НЕТ Socket-адаптера —
        // channel.socket() бросает UnsupportedOperationException. Только чистый
        // NIO: ByteBuffer напрямую в канал, без Channels.newXxxStream.
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))) {
            channel.configureBlocking(true);
            ByteBuffer out = ByteBuffer.allocate(headBytes.length + bodyBytes.length);
            out.put(headBytes);
            out.put(bodyBytes);
            out.flip();
            while (out.hasRemaining()) {
                channel.write(out);
            }
            return readResponse(channel);
        }
    }

    private static Response readResponse(SocketChannel channel) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(8192);
        int n;
        while ((n = channel.read(buf)) > 0) {
            raw.write(buf.array(), 0, n);
            buf.clear();
        }
        if (n < 0 || raw.size() == 0) {
            // n == 0 в blocking-режиме не бывает; -1/EOF — конец ответа (Connection: close).
        }
        byte[] all = raw.toByteArray();
        int headerEnd = indexOf(all, new byte[]{'\r', '\n', '\r', '\n'});
        if (headerEnd < 0) {
            throw new IOException("malformed HTTP response from docker socket");
        }
        String headers = new String(all, 0, headerEnd, StandardCharsets.UTF_8);
        String[] lines = headers.split("\r\n");
        int status;
        try {
            status = Integer.parseInt(lines[0].split(" ")[1]);
        } catch (Exception e) {
            throw new IOException("cannot parse docker status line: " + lines[0], e);
        }
        boolean chunked = false;
        int contentLength = -1;
        for (String line : lines) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
            if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(lower.substring(15).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        byte[] bodyRaw = java.util.Arrays.copyOfRange(all, headerEnd + 4, all.length);
        String body;
        if (chunked) {
            body = new String(dechunk(bodyRaw), StandardCharsets.UTF_8);
        } else if (contentLength >= 0 && bodyRaw.length > contentLength) {
            body = new String(bodyRaw, 0, contentLength, StandardCharsets.UTF_8);
        } else {
            body = new String(bodyRaw, StandardCharsets.UTF_8);
        }
        return new Response(status, body);
    }

    private static byte[] dechunk(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < raw.length) {
            int lineEnd = indexOf(raw, new byte[]{'\r', '\n'}, i);
            if (lineEnd < 0) {
                break;
            }
            String sizeLine = new String(raw, i, lineEnd - i, StandardCharsets.UTF_8).trim();
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) {
                sizeLine = sizeLine.substring(0, semi);
            }
            int size;
            try {
                size = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size: " + sizeLine, e);
            }
            if (size == 0) {
                break;
            }
            i = lineEnd + 2;
            if (i + size > raw.length) {
                throw new IOException("truncated chunked body from docker socket");
            }
            out.write(raw, i, size);
            i += size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Создать контейнер, вернуть его id (201 Created). */
    String createContainer(String name, String image, String[] cmd, Map<String, String> env,
            String[] binds, String networkMode, String user) throws IOException {
        StringBuilder envJson = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!first) {
                envJson.append(',');
            }
            first = false;
            envJson.append('"').append(jsonEscape(e.getKey())).append('=')
                .append(jsonEscape(e.getValue())).append('"');
        }
        envJson.append(']');
        StringBuilder bindsJson = new StringBuilder("[");
        first = true;
        for (String b : binds) {
            if (!first) {
                bindsJson.append(',');
            }
            first = false;
            bindsJson.append('"').append(jsonEscape(b)).append('"');
        }
        bindsJson.append(']');
        StringBuilder cmdJson = new StringBuilder("[");
        first = true;
        for (String c : cmd) {
            if (!first) {
                cmdJson.append(',');
            }
            first = false;
            cmdJson.append('"').append(jsonEscape(c)).append('"');
        }
        cmdJson.append(']');
        String body = "{\"Image\":\"" + jsonEscape(image) + "\","
            + "\"Cmd\":" + cmdJson + ","
            + "\"Env\":" + envJson + ","
            + "\"User\":\"" + jsonEscape(user) + "\","
            + "\"HostConfig\":{\"Binds\":" + bindsJson + ",\"NetworkMode\":\"" + jsonEscape(networkMode) + "\"}}";
        Response r = request("POST", "/containers/create?name=" + name, body);
        if (r.status() != 201) {
            throw new IllegalStateException("create " + name + " -> HTTP " + r.status() + ": " + r.body());
        }
        String id = extractJsonString(r.body(), "Id");
        if (id == null) {
            throw new IllegalStateException("create " + name + " returned no Id: " + r.body());
        }
        return id;
    }

    /** Стартовать созданный контейнер (204, 304 уже запущен — тоже ок). */
    void startContainer(String idOrName) throws IOException {
        Response r = request("POST", "/containers/" + idOrName + "/start", null);
        if (r.status() != 204 && r.status() != 304) {
            throw new IllegalStateException("start " + idOrName + " -> HTTP " + r.status() + ": " + r.body());
        }
    }

    /** SIGKILL контейнера (204). Реальный kill -9 живого процесса, не graceful stop. */
    void kill9(String idOrName) throws IOException {
        Response r = request("POST", "/containers/" + idOrName + "/kill?signal=SIGKILL", null);
        if (r.status() != 204) {
            throw new IllegalStateException("kill " + idOrName + " -> HTTP " + r.status() + ": " + r.body());
        }
    }

    /** Удалить контейнер (force — убивает, если ещё жив). Нет контейнера — тихо ок. */
    void removeContainer(String idOrName) throws IOException {
        Response r = request("DELETE", "/containers/" + idOrName + "?force=1&v=1", null);
        if (r.status() != 204 && r.status() != 404) {
            throw new IllegalStateException("rm " + idOrName + " -> HTTP " + r.status() + ": " + r.body());
        }
    }

    /** JSON inspect'а контейнера (200). */
    String inspect(String idOrName) throws IOException {
        Response r = request("GET", "/containers/" + idOrName + "/json", null);
        if (r.status() != 200) {
            throw new IllegalStateException("inspect " + idOrName + " -> HTTP " + r.status() + ": " + r.body());
        }
        return r.body();
    }

    /** Достать ExitCode из inspect-JSON (\\.State.ExitCode). */
    static Integer exitCodeOf(String inspectJson) {
        int i = inspectJson.indexOf("\"ExitCode\"");
        if (i < 0) {
            return null;
        }
        int colon = inspectJson.indexOf(':', i);
        int j = colon + 1;
        while (j < inspectJson.length() && !Character.isDigit(inspectJson.charAt(j)) && inspectJson.charAt(j) != '-') {
            j++;
        }
        int k = j;
        while (k < inspectJson.length() && (Character.isDigit(inspectJson.charAt(k)) || inspectJson.charAt(k) == '-')) {
            k++;
        }
        try {
            return Integer.parseInt(inspectJson.substring(j, k));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Достать Status из inspect-JSON (\\.State.Status). */
    static String statusOf(String inspectJson) {
        int i = inspectJson.indexOf("\"Status\"");
        if (i < 0) {
            return null;
        }
        int colon = inspectJson.indexOf(':', i);
        int q1 = inspectJson.indexOf('"', colon);
        int q2 = inspectJson.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) {
            return null;
        }
        return inspectJson.substring(q1 + 1, q2);
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + needle.length());
        int q1 = json.indexOf('"', colon);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) {
            return null;
        }
        return json.substring(q1 + 1, q2);
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
