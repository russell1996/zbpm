package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-70 (критерий 1): живой прогон через реальный nginx с ДЕФОЛТНЫМ
 * {@code proxy_buffering on} (как внешний edge) → {@code proxy_pass} на
 * реальный Spring SSE-эндпоинт.
 *
 * <p>RED на коде до фикса (эмуляция флагами {@code REL70_PREMOVE_HEADERS=1} /
 * {@code REL70_PREMOVE_HELLO=1} — побитово поведение прод-кода до WO:
 * заголовков нет + первого flush нет, только heartbeat через 15с): первый
 * байт тела клиент не получает 6с — тест падает
 * ({@code Expecting value to be true but was false}). GREEN после фикса:
 * заголовки + {@code :connected} ≤ 5с (замер обычно ~0.3с).
 *
 * <p>Связь с прод-кодом (честно, двумя половинами): сам механизм
 * «заголовки+первый байт проходят буферизующий nginx» доказывается здесь
 * на живом nginx; что ИМЕННО эти байты шлёт прод-контроллер/сервис —
 * {@code SseProxyBufferingHeadersTest} (заголовки, мутация M1) и
 * {@code SseImmediateHelloTest} (hello, мутация M2) на настоящих
 * {@code SseEventStreamController}/{@code SseEventStreamService}. Значения
 * заголовков стенд берёт из прод-констант
 * ({@link SseEventStreamController#HDR_X_ACCEL_BUFFERING},
 * {@link SseEventStreamController#CACHE_CONTROL_VALUE}) во время генерации —
 * переименование/удаление константы ломает компиляцию этого класса, а смена
 * значения едет в стенд автоматически (пин значения — в MockMvc-тесте).
 *
 * <p>Инфраструктура (СВОЯ, чужие контейнеры не трогаются): nginx — sibling-
 * контейнер через Docker Engine API по unix-сокету (в maven-образе НЕТ
 * бинарника {@code docker} — прецедент {@code ChaosDocker}, WO-TEST-10;
 * клиент ниже — те же 3 вызова: create/start/remove). Маунтов нет (файлы
 * форка нарушителя сокета не видны хост-демону — тот же корень, что
 * P-35): nginx.conf передаётся через {@code base64 -d} в entrypoint.
 * {@code --network host} с обеих сторон (бэкенд-форк и nginx): иначе
 * sibling не видит эфемерный порт форка. Порты — эфемерные (freePort),
 * коллизий нет. {@code --rm}-аналог: явный force-remove в
 * {@code @AfterAll}. Без {@code sleep}-подгонки: ожидание — по событию
 * (первый байт), дедлайны — только верхние границы.
 *
 * <p>Группа {@code nginx}: из дефолтного verify исключена
 * ({@code zbpm.excludedGroups} в zorrobpm-rest/pom.xml — внутри
 * {@code docker build --target test} нет ни сокета, ни сети для pull),
 * гоняется явно: {@code -Dgroups=nginx -Dzbpm.excludedGroups=
 * -Dsurefire.skip=true} (приём из {@code ci/run-rabbit-tests.sh}).
 *
 * <p>Кейс «edge с {@code proxy_ignore_headers X-Accel-Buffering}» этим
 * тестом НЕ покрывается (заголовок апстрима там игнорируется — нужны
 * прямые директивы на edge, точный текст — в отчёте WO-REL-70 §edge).
 */
@Tag("nginx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseNginxBufferingLiveIT {

    private static final String NGINX_NAME = "rel70-nginx-edge";

    private int backendPort;
    private int nginxPort;
    private Process backendProcess;
    private Path workDir;
    private String containerId;

    @BeforeAll
    void up() throws Exception {
        backendPort = freePort();
        nginxPort = freePort();
        // Значения — из прод-констант (связь с прод-кодом, см. шапку).
        String accelName = SseEventStreamController.HDR_X_ACCEL_BUFFERING;
        String cacheName = SseEventStreamController.HDR_CACHE_CONTROL;
        String cacheValue = SseEventStreamController.CACHE_CONTROL_VALUE;
        assertThat(accelName).isEqualTo("X-Accel-Buffering");
        assertThat(cacheValue).isEqualTo("no-cache, no-transform");

        // REL70_WORKDIR (только для отладки стенда): фиксированная папка
        // вместо tmp — backend.log переживает --rm контейнер maven.
        String fixedWork = System.getenv("REL70_WORKDIR");
        if (fixedWork != null && !fixedWork.isBlank()) {
            workDir = Path.of(fixedWork);
            Files.createDirectories(workDir);
        } else {
            workDir = Files.createTempDirectory("rel70-sse-backend");
        }
        writeBackend(workDir, accelName, cacheName, cacheValue);

        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-cp");
        cmd.add(backendCp());
        if ("1".equals(System.getenv("REL70_PREMOVE_HEADERS"))) {
            cmd.add("-Drel70.noHeaders=1");
        }
        if ("1".equals(System.getenv("REL70_PREMOVE_HELLO"))) {
            cmd.add("-Drel70.noHello=1");
        }
        cmd.add("-Dserver.port=" + backendPort);
        // Стенду не нужны БД/Rabbit/Liquibase (их auto-config из jar'ов на
        // classpath иначе поднимают H2-пул и ищут changelog движка).
        cmd.add("-Dspring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
            + "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration,"
            + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration");
        cmd.add("rel70.SseBackend");
        backendProcess = new ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectOutput(workDir.resolve("backend.log").toFile())
            .redirectErrorStream(true)
            .start();
        waitForBackend();

        // Edge с ДЕФОЛТНОЙ буферизацией: proxy_buffering НЕ выключаем
        // (ровно топология прод-edge до фикса).
        String conf = "events {}\n"
            + "http {\n"
            + "    access_log off;\n"
            + "    server {\n"
            + "        listen " + nginxPort + ";\n"
            + "        location = /events/stream {\n"
            + "            proxy_pass http://127.0.0.1:" + backendPort + ";\n"
            + "            proxy_http_version 1.1;\n"
            + "            proxy_set_header Connection \"\";\n"
            + "            proxy_read_timeout 60s;\n"
            + "        }\n"
            + "    }\n"
            + "}\n";
        String b64 = Base64.getEncoder()
            .encodeToString(conf.getBytes(StandardCharsets.UTF_8));
        ensureImage("nginx", "alpine");
        dockerRmQuiet();
        String createBody = "{\"Image\":\"nginx:alpine\",\"Name\":\"" + NGINX_NAME + "\","
            + "\"Entrypoint\":[\"sh\",\"-c\","
            + "\"echo " + b64 + " | base64 -d > /etc/nginx/nginx.conf"
            + " && nginx -t && nginx -g 'daemon off;'\"],"
            + "\"HostConfig\":{\"NetworkMode\":\"host\",\"AutoRemove\":true}}";
        String createResp = dockerApi("POST", "/containers/create", createBody);
        containerId = jsonField(createResp, "Id");
        assertThat(containerId)
            .as("docker create must return a container id, got: " + createResp)
            .isNotBlank();
        int startStatus = dockerApiStatus("POST", "/containers/" + containerId + "/start", null);
        assertThat(startStatus).as("docker start must be 204").isEqualTo(204);
        waitForNginx();
    }

    @AfterAll
    void down() {
        try {
            if (backendProcess != null) {
                backendProcess.destroy();
                try {
                    backendProcess.waitFor(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                backendProcess.destroyForcibly();
            }
        } finally {
            dockerRmQuiet();
        }
    }

    /**
     * Критерий 1: через буферизующий nginx заголовки и первый байт
     * приходят сразу (не после заполнения буфера).
     */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void bufferedProxy_firstByteAndHeadersImmediate() throws Exception {
        long t0 = System.nanoTime();
        HttpURLConnection conn = (HttpURLConnection)
            URI.create("http://127.0.0.1:" + nginxPort + "/events/stream").toURL().openConnection();
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        assertThat(conn.getResponseCode())
            .as("nginx must proxy the SSE handshake")
            .isEqualTo(200);
        long headersAtMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(headersAtMs)
            .as("response head through a buffering proxy must arrive in <=5s")
            .isLessThanOrEqualTo(5_000);
        assertThat(conn.getHeaderField("Content-Type"))
            .as("SSE content type survives the proxy")
            .contains("text/event-stream");
        // X-Accel-Buffering здесь НЕ проверяется: nginx потребляет его сам
        // (служебный заголовок апстрима, клиенту не проксируется никогда —
        // ни в RED, ни в GREEN — поймано живьём: клиент видел 200 +
        // Content-Type, а X-Accel в ответе отсутствовал при стриминге).
        // Пара заголовков прод-контроллера пинована значением в
        // SseProxyBufferingHeadersTest (мутация M1), стенд берёт её же
        // значения из прод-констант при генерации.

        // Первый байт ТЕЛА: :connected обязан прийти за ≤6с. До фикса
        // (REL70_PREMOVE_HELLO=1 — тишина до heartbeat 15с) — RED: пусто.
        List<String> lines = new CopyOnWriteArrayList<>();
        CountDownLatch firstByte = new CountDownLatch(1);
        AtomicLong firstByteAtMs = new AtomicLong(-1);
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                    if (firstByte.getCount() > 0) {
                        firstByteAtMs.set((System.nanoTime() - t0) / 1_000_000);
                        firstByte.countDown();
                    }
                    if (lines.size() >= 8) {
                        break;
                    }
                }
            } catch (Exception ignore) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean got = firstByte.await(6, TimeUnit.SECONDS);
        conn.disconnect();
        assertThat(got)
            .as("first SSE body byte through a buffering proxy must arrive within 6s "
                + "(pre-fix emulation REL70_PREMOVE_HELLO=1: silence until the 15s "
                + "heartbeat tick → RED)")
            .isTrue();
        assertThat(firstByteAtMs.get())
            .as("first byte latency through a buffering proxy")
            .isLessThanOrEqualTo(5_000);
        assertThat(lines)
            .as("first flushed frame must be the ':connected' greeting, got: " + lines)
            .anySatisfy(line -> assertThat(line).startsWith(":connected"));
    }

    // ---- стенд ----

    private void writeBackend(Path dir, String accelName, String cacheName,
            String cacheValue) throws Exception {
        Path src = dir.resolve("rel70/SseBackend.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package rel70;

            public class SseBackend {
                public static void main(String[] args) {
                    var app = new org.springframework.boot.SpringApplication(rel70.SseBackend.App.class);
                    app.run(args);
                }

                @org.springframework.boot.autoconfigure.SpringBootApplication(exclude = {
                    org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                    org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                    org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
                    org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration.class,
                    org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration.class})
                public static class App {
                    // Контроллер подхватывается component-scan'ом пакета
                    // rel70 (@RestController) — явный @Bean здесь давал бы
                    // вторую регистрацию и Ambiguous mapping.
                }
            }
            """);
        // Проводная форма — та же, что у прод-контроллера (значения
        // заголовков подставлены из прод-констант выше): без флага —
        // с фиксом; -Drel70.noHeaders=1 — код ДО фикса (RED); то же hello.
        Path ctl = dir.resolve("rel70/SseBackendController.java");
        String ctlSrc = """
            package rel70;

            @org.springframework.web.bind.annotation.RestController
            @org.springframework.web.bind.annotation.RequestMapping("/events/stream")
            public class SseBackendController {

                @org.springframework.web.bind.annotation.GetMapping(
                    produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
                public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
                        jakarta.servlet.http.HttpServletResponse response) {
                    if (!"1".equals(System.getProperty("rel70.noHeaders"))) {
                        response.setHeader("@ACCEL@", "no");
                        response.setHeader("@CACHE@", "@CACHEVAL@");
                    }
                    var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60_000L);
                    // Пул, а не single-thread: каждый висящий SSE-хендлер
                    // иначе занимает ЕДИНСТВЕННЫЙ поток JDK HttpServer...
                    // здесь Tomcat, но принцип тот же — висящие стримы не
                    // должны отъедать очередь новых handshake (поймано живьём
                    // на однопоточном проб-апстриме: 2-й и 3-й запросы ждали
                    // 30с не из-за nginx, а из-за занятого потока).
                    var exec = java.util.concurrent.Executors.newCachedThreadPool();
                    exec.execute(() -> {
                        try {
                            if (!"1".equals(System.getProperty("rel70.noHello"))) {
                                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                    .event().comment("connected").reconnectTime(3000));
                            }
                            // Дальше — тишина до heartbeat-периода (15с, как
                            // прод): до фикса первый байт раньше не приходит.
                            Thread.sleep(15_000);
                            emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                .event().comment("heartbeat"));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        } finally {
                            exec.shutdown();
                        }
                    });
                    return emitter;
                }
            }
            """
            .replace("@ACCEL@", accelName)
            .replace("@CACHE@", cacheName)
            .replace("@CACHEVAL@", cacheValue);
        Files.writeString(ctl, ctlSrc);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        runChecked(dir,
            System.getProperty("java.home") + "/bin/javac",
            "-cp", backendCp(), "-d", classes.toString(),
            src.toString(), ctl.toString());
    }

    /**
     * Classpath форка: тестовый classpath ЭТОЙ JVM (там уже весь Spring —
     * surefire его собрал) плюс скомпилированный стенд. Классы МОДУЛЕЙ
     * (каталоги target/classes и snapshot-jar'ы zorrobpm) ИСКЛЮЧЕНЫ
     * намеренно: engine-AutoConfiguration тянет JPA-сканирование и fail-fast
     * AdminPasswordValidator — стенду они не нужны (проводная форма SSE не
     * зависит от домена; значения заголовков подставлены строками из
     * прод-констант при генерации). Никаких блужданий по локальному
     * репозиторию (внутри maven-контейнера путь другой).
     */
    private String backendCp() {
        List<String> parts = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(":")) {
            if (entry.isBlank()) {
                continue;
            }
            if (entry.contains("zorrobpm-")
                    && (entry.endsWith("/target/classes")
                        || entry.endsWith("/target/test-classes")
                        || entry.matches(".*zorrobpm-[a-z-]+[0-9.\\-]*SNAPSHOT\\.jar"))) {
                continue;
            }
            parts.add(entry);
        }
        if (workDir != null) {
            parts.add(workDir.resolve("classes").toString());
        }
        return String.join(":", parts);
    }

    private void waitForBackend() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (!backendProcess.isAlive()) {
                String log = Files.exists(workDir.resolve("backend.log"))
                    ? Files.readString(workDir.resolve("backend.log")) : "<no log>";
                throw new IllegalStateException("SSE backend died on start:\n" + log);
            }
            try {
                HttpURLConnection c = (HttpURLConnection)
                    URI.create("http://127.0.0.1:" + backendPort + "/events/stream").toURL()
                        .openConnection();
                c.setConnectTimeout(1_000);
                // Spring коммитит заголовки SSE-ответа только на первой
                // записи: в RED-эмуляции (без hello — тишина до heartbeat
                // 15с, как прод до фикса) заголовки приходят только тогда.
                // Это готовность стенда, не измерение — щедрый таймаут.
                c.setReadTimeout(30_000);
                c.setRequestProperty("Accept", "text/event-stream");
                int code = c.getResponseCode();
                c.disconnect();
                if (code == 200) {
                    return;
                }
            } catch (Exception ignore) {
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("SSE backend did not start in 120s");
    }

    private void waitForNginx() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection c = (HttpURLConnection)
                    URI.create("http://127.0.0.1:" + nginxPort + "/events/stream").toURL()
                        .openConnection();
                c.setConnectTimeout(1_000);
                // Тот же 15с-хвост RED-эмуляции сквозь nginx: заголовки
                // апстрим коммитит только на первой записи — готовность.
                c.setReadTimeout(30_000);
                c.setRequestProperty("Accept", "text/event-stream");
                int code = c.getResponseCode();
                if (code == 200) {
                    c.disconnect();
                    return;
                }
                c.disconnect();
            } catch (Exception ignore) {
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("nginx did not proxy the SSE backend in 60s");
    }

    private static int freePort() throws Exception {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void runChecked(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean ok = p.waitFor(180, TimeUnit.SECONDS) && p.exitValue() == 0;
        if (!ok) {
            throw new IllegalStateException("command failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }

    // ---- минимальный клиент Docker Engine API (прецедент ChaosDocker) ----

    private static final Path DOCKER_SOCK = Path.of("/var/run/docker.sock");

    private record DockerResp(int status, String body) {
    }

    private static DockerResp dockerRaw(String method, String path, String jsonBody) throws Exception {
        byte[] bodyBytes = jsonBody == null ? new byte[0]
            : jsonBody.getBytes(StandardCharsets.UTF_8);
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
        ByteBuffer out = ByteBuffer.allocate(headBytes.length + bodyBytes.length);
        out.put(headBytes);
        out.put(bodyBytes);
        out.flip();
        try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(DOCKER_SOCK))) {
            while (out.hasRemaining()) {
                ch.write(out);
            }
            // БЕЗ shutdownOutput: half-close трактуется Engine как обрыв
            // клиента и даёт 499 (поймано живьём) — читаем до EOF по
            // Connection: close (прецедент ChaosDocker: shutdown нет).
            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            StringBuilder sb = new StringBuilder();
            while (ch.read(buf) > 0) {
                buf.flip();
                sb.append(StandardCharsets.UTF_8.decode(buf));
                buf.clear();
                if (sb.length() > (1 << 20)) {
                    break;
                }
            }
            String raw = sb.toString();
            int eol = raw.indexOf("\r\n");
            int status = Integer.parseInt(raw.substring(9, 12));
            int hdrEnd = raw.indexOf("\r\n\r\n");
            String body = hdrEnd >= 0 ? raw.substring(hdrEnd + 4) : "";
            // Chunked вниз не разбираем глубоко: для create — короткий JSON
            // одной чанкой; склеиваем чанки грубо (hex-размерные строки —
            // вне JSON-скобок их нет, Id ищем по ключу).
            body = dechunk(body);
            return new DockerResp(status, body);
        }
    }

    private static String dechunk(String body) {
        // Грубая склейка chunked: строки с hex-размером отбрасываем,
        // остальное склеиваем. Для коротких JSON Engine API достаточно.
        String[] lines = body.split("\r\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.matches("[0-9a-fA-F]+")) {
                continue;
            }
            sb.append(line);
        }
        String glued = sb.toString();
        return glued.contains("{") ? glued : body;
    }

    private static String dockerApi(String method, String path, String jsonBody) throws Exception {
        DockerResp r = dockerRaw(method, path, jsonBody);
        if (r.status() < 200 || r.status() >= 300) {
            throw new IllegalStateException(
                "docker API " + method + " " + path + " → " + r.status() + ": " + r.body());
        }
        return r.body();
    }

    private static int dockerApiStatus(String method, String path, String jsonBody) throws Exception {
        return dockerRaw(method, path, jsonBody).status();
    }

    private static void ensureImage(String repo, String tag) throws Exception {
        DockerResp r = dockerRaw("GET", "/images/" + repo + ":" + tag + "/json", null);
        if (r.status() == 200) {
            return;
        }
        // Pull (долго — до 5 минут, образ ~30МБ).
        DockerResp pull = dockerRaw("POST",
            "/images/create?fromImage=" + repo + "&tag=" + tag, null);
        if (pull.status() != 200) {
            throw new IllegalStateException(
                "nginx image missing and pull failed: " + pull.status() + " " + pull.body());
        }
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (System.nanoTime() < deadline) {
            DockerResp check = dockerRaw("GET", "/images/" + repo + ":" + tag + "/json", null);
            if (check.status() == 200) {
                return;
            }
            Thread.sleep(2_000);
        }
        throw new IllegalStateException("nginx image pull did not finish in 5 min");
    }

    private void dockerRmQuiet() {
        try {
            if (containerId != null) {
                dockerRaw("DELETE", "/containers/" + containerId + "?force=1", null);
                containerId = null;
            }
        } catch (Exception ignore) {
        }
        try {
            dockerRaw("DELETE", "/containers/" + NGINX_NAME + "?force=1", null);
        } catch (Exception ignore) {
        }
    }

    private static String jsonField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int j = json.indexOf('"', i + needle.length());
        return j < 0 ? "" : json.substring(i + needle.length(), j);
    }
}
