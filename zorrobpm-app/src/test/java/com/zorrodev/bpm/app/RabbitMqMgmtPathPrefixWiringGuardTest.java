package com.zorrodev.bpm.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-QW-12 (ДОПОЛНЕНИЕ CTO, требование 1): сторожевой тест на инвариант
 * «конфигурация брокера ⇔ base-url приложения».
 *
 * <p><b>Дефект, который закрывается.</b> QW12-1 включил на брокере
 * {@code management.path_prefix = /rabbitmq} (иначе UI под подпутём nginx отдаёт
 * абсолютные ссылки и ломается). Настройка узловая: она переносит ВСЕ пути
 * management-HTTP за префикс. Приложение ходит в Management API по
 * {@code RABBITMQ_MGMT_BASE_URL} и собирает пути как {@code <base>/api/users/…}
 * ({@code RabbitMqProvisioningService.mgmtPut}, без знания о префиксе). Связь между
 * «как настроен брокер» и «что записано в base-url» ничем не была обеспечена: в
 * {@code docker-compose.yml} брокер монтировал префиксный conf.d, а переменная
 * оставалась {@code http://rabbitmq:15672}. Живой прогон на брокере с префиксом:
 * {@code GET|PUT|DELETE /api/users/<login>} → <b>404</b>, то есть выдача и отзыв
 * брокерских учёток воркеров упали бы в fail-closed 503.
 *
 * <p>Почему именно сторож, а не комментарий у переменной: расхождение тут не
 * «ошибка значения», а рассогласование ДВУХ файлов, и ни один тест его не видит —
 * compose-файлы не компилируются, контекст Spring поднимается с дефолтом из
 * properties-файла, а не из compose. Регрессия «скопировали compose-файл и забыли
 * про префикс» (ровно то, что случилось с {@code docker-compose.multi.yml} и
 * {@code ci/docker-compose.rabbit.yml}) вернулась бы молча.
 *
 * <p>Проверяется форма конфигурации на диске — то же, что компилятор пропускает по
 * определению (тот же приём, что в {@link ModuleConfigImportGuardTest}). Живое поведение
 * на настоящем брокере — в {@code RabbitMqMgmtPathPrefixRabbitIT} (rabbit-сьют).
 *
 * <p>Намеренно НЕ «сверять со списком файлов, которые сегодня правильные»: правило
 * выводится из признака в каждом файле, поэтому новый compose с брокером проверяется
 * сам, без правки теста.
 */
class RabbitMqMgmtPathPrefixWiringGuardTest {

    /** Метка префиксного конфига брокера: монтируется ли он этому брокеру. */
    private static final String PREFIX_CONF_MOUNT =
        "30-management-path-prefix.conf:/etc/rabbitmq/conf.d/30-management-path-prefix.conf";

    /** Суффикс base-url, который обязан сопровождать префиксный брокер. */
    private static final String MGMT_PATH_PREFIX = "/rabbitmq";

    /** Compose-файлы, где вообще может быть объявлен base-url или смонтирован conf. */
    private static final List<String> COMPOSE_FILES = List.of(
        "docker-compose.yml",
        "docker-compose.multi.yml",
        "ci/docker-compose.rabbit.yml",
        "ci/docker-compose.e2e.yml");

    /**
     * Ключ инварианта: {@code RABBITMQ_MGMT_BASE_URL} с любым разделителем и любым
     * умолчанием после него — {@code ${VAR:-value}}, {@code ${VAR:value}},
     * {@code VAR: value}. Само объявление переменной в compose.
     */
    private static final Pattern MGMT_URL_DECLARATION = Pattern.compile(
        "RABBITMQ_MGMT_BASE_URL\\s*(?::|=|-)\\s*\\$?\\{?[^\\n]*");

    /**
     * Инвариант по каждому compose-файлу: префиксный конфиг ⇔ префикс в base-url.
     *
     * <p>Нарушение в любую из сторон — реальная авария:
     * <ul>
     *   <li>конфиг есть, префикса в URL нет → провижининг 404 → fail-closed 503;</li>
     *   <li>префикса в URL нет, конфига нет → работает, но почему-то «случайно»:
     *   как только конфиг долетят (копипаст compose-файла), вторая сторона молча
     *   сломается — ровно тот класс регрессии, ради которого тест и написан;</li>
     *   <li>URL с префиксом, а конфига нет → 404 уже сейчас, и непонятно почему.</li>
     * </ul>
     */
    @Test
    void managementPrefixConfAndBaseUrlMustAgreeInEveryComposeFile() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (String file : COMPOSE_FILES) {
            String text = read(file);
            if (text == null) continue;
            boolean confMounted = text.contains(PREFIX_CONF_MOUNT);
            List<String> declaredUrls = declaredMgmtUrls(text);

            // Файл не объявляет base-url (override наследует базовый compose) —
            // судить нечего, инвариант проверяется на объявляющем файле.
            if (declaredUrls.isEmpty()) continue;

            for (String url : declaredUrls) {
                boolean urlHasPrefix = url.contains(MGMT_PATH_PREFIX);
                if (confMounted != urlHasPrefix) {
                    offenders.add(file + ": broker mounts the prefix conf = " + confMounted
                        + ", but base-url '" + url + "' has the "
                        + MGMT_PATH_PREFIX + " suffix = " + urlHasPrefix);
                }
            }
        }

        assertThat(offenders)
            .as("management.path_prefix on the broker and RABBITMQ_MGMT_BASE_URL in compose "
                + "must agree (WO-QW-12). With the prefix conf mounted, every management path "
                + "answers 404 without the suffix, and per-system credential provisioning "
                + "fails closed with 503; without the conf, a suffixed URL 404s immediately. "
                + "The rabbit stand mirrors the prod broker on purpose — "
                + "ci/docker-compose.rabbit.yml mounts the same conf.")
            .isEmpty();
    }

    /**
     * Тот же инвариант для rabbit-стенда, который гоняет {@code ci/run-rabbit-tests.sh}:
     * скрипт собирает base-url сам, поэтому «забытый префикс» там не поймал бы ни один
     * compose-сторож — и вся rabbit-сюита (включая провижининг) поехала бы на URL,
     * которого на этом брокере не существует.
     */
    @Test
    void rabbitStandBaseUrlFollowsTheBrokerItStarts() throws IOException {
        String compose = read("ci/docker-compose.rabbit.yml");
        String script = read("ci/run-rabbit-tests.sh");
        assertThat(compose).as("ci/docker-compose.rabbit.yml must exist").isNotNull();
        assertThat(script).as("ci/run-rabbit-tests.sh must exist").isNotNull();

        boolean brokerIsPrefixed = compose.contains(PREFIX_CONF_MOUNT);
        boolean urlIsPrefixed = script.contains("MGMT_PATH_PREFIX=\"/rabbitmq\"")
            && script.contains("${MGMT_PATH_PREFIX}");
        assertThat(urlIsPrefixed)
            .as("ci/run-rabbit-tests.sh builds RABBITMQ_MGMT_BASE_URL itself (broker is "
                + "prefix-scoped = " + brokerIsPrefixed + "), so the stand must add the "
                + MGMT_PATH_PREFIX + " suffix to it — otherwise the whole rabbit suite "
                + "runs against a management path that 404s on this broker.")
            .isEqualTo(brokerIsPrefixed);

        // Скрипт ждёт готовности именно по префиксованному пути: «AMQP пингуется, а
        // management-HTTP ещё не слушает» — это гонка, которая выглядела бы как
        // регрессия провижининга в середине сьюта.
        assertThat(script)
            .as("the stand must wait for the prefixed management HTTP endpoint, not just "
                + "AMQP (rabbitmq-diagnostics ping answers about AMQP only)")
            .contains("Waiting for RabbitMQ Management HTTP");
    }

    /**
     * Умолчание в properties-файлах остаётся БЕЗ префикса — сознательно: дефолт
     * обслуживает запуск движка вне compose, где брокер поднимают без нашего conf.d
     * (и префиксный URL там 404-ил бы сразу). Проверяем явно, чтобы «унифицировали
     * дефолты» не сделали этого молча и не сломали локальный запуск.
     */
    @Test
    void propertiesDefaultStaysPrefixlessForLocalRunsOutsideCompose() throws IOException {
        List<String> prefixlessDefaults = new ArrayList<>();
        for (String file : List.of(
            "zorrobpm-app/src/main/resources/application.properties",
            "zorrobpm-rest/src/main/resources/zorrobpm-rest.properties",
            "zorrobpm-rest/src/main/resources/application-prod.yml")) {
            String text = read(file);
            assertThat(text).as(file + " must exist").isNotNull();
            for (String line : text.split("\n")) {
                // Строка настройки — это любая, что про настройку говорит: и
                // плоский `zorrobpm.rabbitmq.management.base-url=…` (properties), и
                // вложенный `base-url: …` под `zorrobpm.rabbitmq.management`
                // (application-prod.yml) — по одному лишь имени ключа второй бы
                // не попал в разбор.
                boolean declares = line.contains("management.base-url")
                    || (line.contains("base-url") && line.contains("RABBITMQ_MGMT_BASE_URL"));
                if (!declares) continue;
                assertThat(line)
                    .as(file + " keeps the prefixless default on purpose (local run against a "
                        + "broker started without our conf.d); compose is what adds the suffix")
                    .doesNotContain(MGMT_PATH_PREFIX);
                prefixlessDefaults.add(file);
            }
        }
        assertThat(prefixlessDefaults)
            .as("at least one properties file must still declare the setting, otherwise this "
                + "test guards a renamed key and nothing else")
            .isNotEmpty();
    }

    /** Объявления base-url в compose: ключ + значение умолчаления. */
    private static List<String> declaredMgmtUrls(String composeText) {
        List<String> urls = new ArrayList<>();
        for (String line : composeText.split("\n")) {
            // Комментарии — не объявления: в них ключ упоминается в прозе («приложение
            // ходит по RABBITMQ_MGMT_BASE_URL»), и без этого фильтра разбор подцепил бы
            // чужую строку и выдал бы ложное нарушение.
            if (line.stripLeading().startsWith("#")) continue;
            Matcher m = MGMT_URL_DECLARATION.matcher(line);
            if (!m.find()) continue;
            String declaration = m.group();
            int key = declaration.indexOf("RABBITMQ_MGMT_BASE_URL");
            int colon = declaration.indexOf(':', key);
            int eq = declaration.indexOf('=', key);
            int cut = colon >= 0 ? colon : eq;
            if (cut > 0) {
                urls.add(declaration.substring(cut + 1).trim());
            }
        }
        return urls;
    }

    private static String read(String relative) throws IOException {
        Path path = repoRoot().resolve(relative);
        return Files.exists(path) ? Files.readString(path) : null;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("pom.xml"))) dir = dir.getParent();
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }
}