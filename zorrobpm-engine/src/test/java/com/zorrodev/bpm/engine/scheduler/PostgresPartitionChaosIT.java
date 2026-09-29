package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * WO-TEST-10 scenario 2 (WB-002): обрыв сети к Postgres посреди работы — реальный инжект.
 *
 * <p>Инжект: toxiproxy {@code timeout:0} в обе стороны между тестом и PG (адресация
 * через {@code CHAOS_PG_HOST/PORT} — в chaos-топологии это toxiproxy-listen-порт,
 * upstream — живой postgres:16; вне chaos-прогона — прямое соединение, toxic'и
 * недоступны и тест self-skip'ается, см. ниже). Окно partition — 5 секунд.
 *
 * <p>Проверяемое поведение: (1) приложение НЕ падает целиком — вызовы в окне partition
 * бросают recoverable-исключения, а не роняют JVM/контекст; (2) после снятия toxic'а —
 * recovery без рестарта: соединения проходят снова; (3) данные согласованы: outbox-строка,
 * записанная ДО partition, переживает его и видна ПОСЛЕ (запись либо есть целиком,
 * либо её нет, partial-состояния нет).
 *
 * <p>Отличие от {@code OutboxBatchProcessorPgIT}: там детект на подготовленном
 * состоянии (poison-payload уже в таблице); здесь сбой инжектится В РЕАЛЬНОМ ВРЕМЕНИ
 * посреди живых операций.
 *
 * <p>Self-skip: без {@code CHAOS_TOXI_HOST} (обычный PG-прогон/CI test:pg) toxiproxy
 * недоступен — тест помечается skipped assumptions, а не падает (P-34: не крутить
 * то, чего нет). Реальный инжект — только в chaos-топологии {@code ci/run-chaos-tests.sh}.
 *
 * <p>Уроки прогона run3 (зафиксированы в канале, здесь — конструктивно):
 * <ul>
 *   <li>все сетевые чтения — через свежие DriverManager-соединения с явными
 *       {@code connectTimeout/socketTimeout}: никакого unbounded socket read
 *       (probe, висящий в чёрной дыре, хоронил весь тест на 955 секунд);</li>
 *   <li>heal терпит 404 ГРОМКО (warn), затем GET-verify пустоты toxic'ов — исключение
 *       из {@code finally} больше никогда не маскирует истинную причину падения;</li>
 *   <li>имена toxic'ов уникальны на тест + sweep остатков в {@code @BeforeEach}
 *       (toxiproxy живёт отдельно от JVM — toxic'и мёртвого прогона иначе травят
 *       следующий);</li>
 *   <li>пул приложения не трогаем до финального confirmatory-read (после heal,
 *       bounded валидацией Hikari) — фоновый watchdog-шум ему не мешает.</li>
 * </ul>
 *
 * <p>POF (V3/G-N) — инверсией: тот же каркас с toxic'ом, НЕ снятым (partition навсегда),
 * обязан RED — recovery не наступает, final-read таймаутит. См.
 * {@code pgPartitionNeverHeals_red}. Доказывает, что позитивный тест проверяет именно
 * восстановление связи, а не «запросы всегда проходят».
 */
@SpringBootTest(classes = com.zorrodev.bpm.engine.TestMain.class)
@ActiveProfiles({"test", "pgtest"})
@Tag("chaos")
public class PostgresPartitionChaosIT extends PostgresIT {

    private static final String TOXIC_A = "cut-pg-a";
    private static final String TOXIC_B = "cut-pg-b";
    private static final String TOXIC_PERM = "perm-pg-a";

    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> cleanupIds = new java.util.ArrayList<>();

    private String dbUrl;
    private String dbUser;
    private String dbPassword;
    private String toxiBase;

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @BeforeEach
    void setUp() {
        // PG-адрес берём из тех же переменных, что PostgresIT (в chaos-топологии это
        // toxiproxy-listen; таймауты — в секундах, драйверный синтаксис PG).
        String host = cfg("PG_HOST", "127.0.0.1");
        String port = cfg("PG_PORT", "5432");
        String db = cfg("PG_DB", "zorrobpm-db");
        dbUser = cfg("PG_USER", "zorrodev");
        dbPassword = cfg("PG_PASSWORD", "zorrodev");
        dbUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db
            + "?sslmode=disable&connectTimeout=10&socketTimeout=20";
        String toxiHost = cfg("CHAOS_TOXI_HOST", null);
        toxiBase = toxiHost == null ? null
            : "http://" + toxiHost + ":" + cfg("CHAOS_TOXI_PORT", "8474");
        // Sweep остатков мёртвого прогона: toxiproxy пережил бывшую JVM — чужие
        // toxic'и обязаны исчезнуть ДО baseline, иначе ложный RED.
        if (toxiBase != null) {
            for (String t : List.of(TOXIC_A, TOXIC_B, TOXIC_PERM,
                    TOXIC_A + "-down", TOXIC_B + "-down", TOXIC_PERM + "-down",
                    "cut", "cut-down", "perm", "perm-down")) {
                try {
                    toxiRaw("DELETE", "/proxies/chaos-pg/toxics/" + t);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @AfterEach
    void cleanup() {
        // Оборонительный heal: даже упавший тест не оставляет partition следующим.
        if (toxiBase != null) {
            for (String t : List.of(TOXIC_A, TOXIC_B, TOXIC_PERM,
                    TOXIC_A + "-down", TOXIC_B + "-down", TOXIC_PERM + "-down")) {
                try {
                    toxiRaw("DELETE", "/proxies/chaos-pg/toxics/" + t);
                } catch (Exception ignored) {
                }
            }
        }
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                jdbc.update("DELETE FROM outbox WHERE id = ?", id);
            } catch (Exception ignored) {
            }
        }
        cleanupIds.clear();
    }

    // ---------- toxiproxy HTTP API (без SDK — JDK HttpClient, как ChaosToxiProxy) ----------

    private String toxiRaw(String method, String path) throws Exception {
        return toxiRaw(method, path, null);
    }

    private String toxiRaw(String method, String path, String body) throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(toxiBase + path))
            .timeout(java.time.Duration.ofSeconds(15));
        switch (method) {
            case "POST" -> b.header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            case "DELETE" -> b.DELETE();
            default -> b.GET();
        }
        java.net.http.HttpResponse<String> resp =
            http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(method + " " + path + " -> HTTP " + resp.statusCode()
                + ": " + resp.body());
        }
        return resp.body();
    }

    /** Heal, терпящий отсутствие toxic'а ГРОМКО (warn), а не маскировкой исключения. */
    private void healQuietly(String toxic) {
        try {
            toxiRaw("DELETE", "/proxies/chaos-pg/toxics/" + toxic);
            System.out.println("[chaos-pg] healed toxic " + toxic);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                System.out.println("[chaos-pg] WARN: toxic " + toxic + " already gone (404) — continuing");
            } else {
                System.out.println("[chaos-pg] WARN: heal of " + toxic + " failed: " + e + " — continuing");
            }
        }
    }

    /** Verify: после heal toxic'ов быть не должно — иначе hard fail, не тишина. */
    private void verifyNoToxics() throws Exception {
        String list = toxiRaw("GET", "/proxies/chaos-pg/toxics");
        assertThat(list.replaceAll("\\s", ""))
            .as("после heal toxic'ов остаться не должно, иначе следующий шаг врёт: " + list)
            .isIn("[]", "{}");
    }

    // ---------- свежие bounded-соединения (пул не трогаем до recovery) ----------

    /** SELECT 1 по свежему соединению: bounded (~30s worst), бросает при partition. */
    private boolean probeOnce() {
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement ps = c.prepareStatement("SELECT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private String serviceTaskPayload() {
        return "{\"serviceTaskId\":\"" + UUID.randomUUID() + "\"}";
    }

    private UUID insertOutbox(String payload) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO outbox (id, payload, created_at, published, attempts, status, kind) " +
            "VALUES (?, ?, ?, false, 0, 'PENDING', 'SERVICE_TASK')",
            id, payload, Timestamp.from(Instant.now()));
        cleanupIds.add(id);
        return id;
    }

    @Test
    void pgPartition_outboxSurvives_appRecovers() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(toxiBase != null,
            "chaos topology only: CHAOS_TOXI_HOST not set — needs toxiproxy (ci/run-chaos-tests.sh)");

        // 1. Baseline: запись ДО partition видна сразу (контекст жив, связь есть).
        UUID id = insertOutbox(serviceTaskPayload());
        Map<String, Object> before = jdbc.queryForMap("SELECT status, published FROM outbox WHERE id = ?", id);
        assertThat(before.get("status")).isEqualTo("PENDING");

        // 2. ИНЖЕКТ: полный обрыв PG (обе стороны, уникальные имена).
        toxiRaw("POST", "/proxies/chaos-pg/toxics",
            "{\"name\":\"" + TOXIC_A + "\",\"type\":\"timeout\",\"stream\":\"upstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}");
        toxiRaw("POST", "/proxies/chaos-pg/toxics",
            "{\"name\":\"" + TOXIC_A + "-down\",\"type\":\"timeout\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}");
        try {
            // 3. В окне partition свежий коннект падает recoverable-ошибкой — быстро и
            // детерминированно (socketTimeout, не вечное зависание пула).
            long t0 = System.currentTimeMillis();
            boolean failedFast = !probeOnce();
            long dt = System.currentTimeMillis() - t0;
            assertThat(failedFast).as("в partition свежий коннект падает, а не висит вечно").isTrue();
            assertThat(dt).as("падение bounded (таймауты драйвера), не вечное зависание")
                .isLessThan(60_000);

            // 4. Контекст жив: JVM не упала, бин на месте.
            assertThat(jdbc).isNotNull();
            // WO-OPS-14: намеренно sleep, не Awaitility — 5с это ДЛИТЕЛЬНОСТЬ
            // инжекта (окно partition обязано физически существовать, иначе
            // «обрыв» вырождается в мгновенный heal), а не ожидание условия.
            Thread.sleep(5000);
        } finally {
            healQuietly(TOXIC_A);
            healQuietly(TOXIC_A + "-down");
            verifyNoToxics();
        }

        // 6. Recovery без рестарта: свежие соединения проходят снова (дедлайн 90s —
        // запас на eviction мёртвых сокетов и переподключение). WO-OPS-14: ручной
        // deadline-цикл со sleep(2000) заменён на Awaitility — то же условие
        // (probeOnce), тот же дедлайн 90с и тот же poll-интервал 2с.
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(probeOnce())
                .as("recovery без рестарта после снятия partition").isTrue());

        // 7. Confirmatory-read путём приложения (пул): после heal валидация Hikari
        // выселяет мёртвые коннекты bounded-сроком — тоже детерминированно.
        // WO-OPS-14: тот же перевод на Awaitility (дедлайн 60с, интервал 2с);
        // ignoreExceptions — transient-ошибки пула в окне recovery не валят
        // ожидание, как раньше `catch (Exception e)` в цикле.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
            .ignoreExceptions()
            .untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT 1", Integer.class))
                .as("пул приложения тоже recovered").isEqualTo(1));

        // 8. Данные согласованы: строка, записанная ДО partition, цела и на месте.
        Map<String, Object> after = jdbc.queryForMap("SELECT status, published FROM outbox WHERE id = ?", id);
        assertThat(after.get("status")).isEqualTo("PENDING");
        assertThat(after.get("published")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void pgPartitionNeverHeals_red() throws Exception {
        // POF-инверсия критерия: partition НЕ снимается — recovery наступить НЕ может,
        // probe обязан RED (все попытки в дедлайне падают). Доказывает, что позитивный
        // тест выше проверяет именно восстановление связи, а не «запросы всегда проходят».
        org.junit.jupiter.api.Assumptions.assumeTrue(toxiBase != null,
            "chaos topology only: CHAOS_TOXI_HOST not set (ci/run-chaos-tests.sh)");

        UUID id = insertOutbox(serviceTaskPayload());

        toxiRaw("POST", "/proxies/chaos-pg/toxics",
            "{\"name\":\"" + TOXIC_PERM + "\",\"type\":\"timeout\",\"stream\":\"upstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}");
        toxiRaw("POST", "/proxies/chaos-pg/toxics",
            "{\"name\":\"" + TOXIC_PERM + "-down\",\"type\":\"timeout\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}");
        try {
            // WO-OPS-14: намеренно НЕ Awaitility. Это негативное доказательство
            // («recovery НЕ наступает за 30с»): `until(!probe)` вернулся бы
            // мгновенно в первую же секунду и ничего не доказывал (фиктивная
            // замена, запрещена критерием 2 WO). Цикл уже опрашивает реальное
            // условие с дедлайном — sleep(2000) здесь poll-интервал, не фикс-пауза.
            boolean recovered = false;
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && !recovered) {
                recovered = probeOnce();
                if (!recovered) {
                    Thread.sleep(2000);
                }
            }
            assertThat(recovered).as("без снятия partition recovery невозможен — обязано RED").isFalse();
        } finally {
            healQuietly(TOXIC_PERM);
            healQuietly(TOXIC_PERM + "-down");
        }
        // Partition бьёт по связи, не по данным: строка цела (проверено ПОСЛЕ heal,
        // чтобы финальный read не упирался в вечный partition).
        Map<String, Object> row = jdbc.queryForMap("SELECT status FROM outbox WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("PENDING");
    }
}
