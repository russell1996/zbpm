package com.zorrodev.bpm.handler.boot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * WO-TEST-10: минимальный HTTP-клиент к toxiproxy (без toxiproxy-java зависимости).
 *
 * <p>Toxiproxy 2.x expose'ит REST API на :8474: {@code POST /proxies} (создать),
 * {@code POST /proxies/{name}/toxics} (добавить toxic), {@code DELETE
 * /proxies/{name}/toxics/{toxic}} (снять), {@code DELETE /proxies/{name}}
 * (удалить). Этого достаточно для сценариев «обрыв сети»: toxic типа
 * {@code timeout} с {@code timeout:0} роняет TCP-стрим в обе стороны, удаление
 * toxic восстанавливает связь без рестарта upstream.
 *
 * <p>Почему без SDK: `eu.rekawek.toxiproxy:toxiproxy-java` нет в `~/.m2` и ни в
 * одном pom'е проекта; тянуть новую зависимость ради 4 REST-вызовов —
 * неоправданно (P-12: package-lock-синхрон тут не про Java, но принцип тот же —
 * не плодить зависимости без нужды). JDK HttpClient покрывает всё.
 *
 * <p>Используется {@code ChaosWorkerMain}-соседями косвенно: прямые вызовы идут
 * через локальные {@code toxi()}-хелперы тестов (им нужен per-test timeout/sweep),
 * этот класс — канонический клиент для будущих chaos-тестов и ручной диагностики.
 */
final class ChaosToxiProxy {

    private final HttpClient http;
    private final String base;

    ChaosToxiProxy(String host, int apiPort) {
        this.base = "http://" + host + ":" + apiPort;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private String request(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .timeout(Duration.ofSeconds(15));
        switch (method) {
            case "POST" -> b.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            case "DELETE" -> b.DELETE();
            default -> b.GET();
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(
                method + " " + path + " -> HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /** Создать прокси name: listenPort -> upstreamHost:upstreamPort. */
    void createProxy(String name, int listenPort, String upstreamHost, int upstreamPort) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"listen\":\"0.0.0.0:" + listenPort
            + "\",\"upstream\":\"" + upstreamHost + ":" + upstreamPort + "\"}";
        request("POST", "/proxies", body);
    }

    /** Полный обрыв TCP в обе стороны (timeout:0 роняет стрим мгновенно). */
    void cut(String proxyName, String toxicName) throws Exception {
        String body = "{\"name\":\"" + toxicName + "\",\"type\":\"timeout\","
            + "\"stream\":\"upstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}";
        request("POST", "/proxies/" + proxyName + "/toxics", body);
        String down = "{\"name\":\"" + toxicName + "-down\",\"type\":\"timeout\","
            + "\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}";
        request("POST", "/proxies/" + proxyName + "/toxics", down);
    }

    /** Снять обрыв (оба направления), связь восстанавливается без рестарта upstream. */
    void heal(String proxyName, String toxicName) throws Exception {
        request("DELETE", "/proxies/" + proxyName + "/toxics/" + toxicName, null);
        request("DELETE", "/proxies/" + proxyName + "/toxics/" + toxicName + "-down", null);
    }

    /** Удалить прокси целиком (cleanup). */
    void deleteProxy(String name) throws Exception {
        try {
            request("DELETE", "/proxies/" + name, null);
        } catch (IllegalStateException e) {
            if (!e.getMessage().contains("HTTP 404")) {
                throw e;
            }
        }
    }

    /** Санити-чек: toxiproxy отвечает и версия совпадает с пином CI. */
    String version() throws Exception {
        return request("GET", "/version", null).replace("\"", "").trim();
    }

    static void closeQuietly(java.lang.AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
