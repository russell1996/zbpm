package com.zorrodev.bpm.httpconnector;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NEW5-07 (WO-QW-10): секреты коннектора физически не доходили до контейнера.
 *
 * <p>Находка аудита: {@code docker-compose.yml} пробрасывал 7 скалярных
 * {@code ZORROBPM_HTTP_CONNECTOR_*}, а {@code zorrobpm.http-connector.secrets.<name>}
 * — никак. В штатном compose-деплое {@code authType != none} был недостижим без
 * ручной правки compose. Ровно эта ошибка класса «переменная есть, но её никто не
 * читает» ловится ТОЛЬКО связкой двух файлов: ни тест воркера, ни тест биндинга
 * её не видят — проброс заканчивается на границе compose→контейнер.
 *
 * <p>Проверка общая для всей namespaces {@code ZORROBPM_HTTP_CONNECTOR_*}, а не
 * только для ново добавленной ручки: любая следующая переменная, добавленная в
 * compose без строки в {@code application.properties}, уронит этот тест (P-41 —
 * новая настройка рядом с проверяемой проходит ту же проверку).
 */
class HttpConnectorComposeWiringTest {

    private static final Path COMPOSE = Path.of("..", "docker-compose.yml");
    private static final Path APP_PROPERTIES =
        Path.of("..", "zorrobpm-app", "src", "main", "resources", "application.properties");
    private static final String NAMESPACE = "ZORROBPM_HTTP_CONNECTOR_";

    @Test
    void filesUnderTestExist() {
        // Молча прочитать несуществующий файл нельзя — иначе тест «зелёный» в вакууме.
        assertThat(Files.isRegularFile(COMPOSE)).as("docker-compose.yml доступен из модуля").isTrue();
        assertThat(Files.isRegularFile(APP_PROPERTIES)).as("application.properties доступен из модуля").isTrue();
    }

    @Test
    void everyConnectorEnvInComposeIsReadByApplicationProperties() throws IOException {
        Set<String> composeKeys = connectorEnvKeysOfAppService(read(COMPOSE));
        assertThat(composeKeys)
            .as("в app-сервисе compose есть ключи коннектора — иначе проверка ни о чём")
            .isNotEmpty();

        String properties = read(APP_PROPERTIES);
        List<String> unread = new ArrayList<>();
        for (String key : composeKeys) {
            // application.properties читает переменную как ${KEY:дефолт}
            if (!properties.contains("${" + key)) {
                unread.add(key);
            }
        }
        assertThat(unread)
            .as("эти переменные compose пробрасывает в контейнер, но application.properties их НЕ читает — "
                + "значение молча теряется, ровно как было с секретами (NEW5-07)")
            .isEmpty();
    }

    @Test
    void secretsJsonReachesContainerAndIsRead() throws IOException {
        String key = NAMESPACE + "SECRETS_JSON";
        assertThat(connectorEnvKeysOfAppService(read(COMPOSE)))
            .as("NEW5-07: секреты обязаны доезжать до контейнера")
            .contains(key);
        assertThat(read(APP_PROPERTIES))
            .as("NEW5-07: и читаться приложением")
            .contains("zorrobpm.http-connector.secrets-json=${" + key + ":}");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    /**
     * Ключи {@code ZORROBPM_HTTP_CONNECTOR_*} из секции {@code environment:} сервиса
     * {@code app}. Секция вырезается по отступам (сервисы — 2 пробела), дальше берутся
     * строки-«KEY: value». Без библиотеки YAML: контракт здесь — плоский список
     * скаляров, а лишняя зависимость ради проверки строки в файле не окупается.
     */
    private static Set<String> connectorEnvKeysOfAppService(String compose) {
        List<String> lines = compose.lines().toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("  app:")) {
                start = i;
                break;
            }
        }
        assertThat(start).as("сервис app найден в docker-compose.yml").isNotNegative();
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^\\s+(" + Pattern.quote(NAMESPACE) + "[A-Z0-9_]+):").matcher("");
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            // следующий сервис на том же уровне — конец секции app
            if (line.matches("^ {2}[a-zA-Z0-9_-]+:\\s*$")) {
                break;
            }
            m.reset(line);
            if (m.find()) {
                keys.add(m.group(1));
            }
        }
        return keys;
    }

}
