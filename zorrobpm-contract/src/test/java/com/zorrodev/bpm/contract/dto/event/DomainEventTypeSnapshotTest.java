package com.zorrodev.bpm.contract.dto.event;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-UI-25 post-merge CI fix: снимок {@code backend-event-types.json} обязан
 * повторять строковые значения {@link DomainEventType} один в один.
 *
 * <p>Фронт-тест {@code realtime-event-contract.ui25.test.ts} читает ТОЛЬКО этот
 * снимок: фронт-сборка в CI ({@code docker build}, контекст
 * {@code zorrobpm-frontend/}) не видит файлов бэкенда, прямое чтение
 * {@code DomainEventType.java} давало ENOENT (master 4ea33378, pipeline 177171).
 * Без этой сверки снимок протухнет молча: тип добавят в enum, JSON не обновят,
 * фронт-тест останется зелёным на вранье.
 *
 * <p>Путь — относительно модуля (working dir surefire = basedir модуля), как
 * прецедент {@code HttpConnectorComposeWiringTest} читает
 * {@code ../docker-compose.yml}. В {@code docker build --target test} файл
 * виден благодаря точечному исключению в корневом {@code .dockerignore}
 * (только этот JSON, не весь фронт).
 */
class DomainEventTypeSnapshotTest {

    private static final Path SNAPSHOT = Path.of(
        "..", "zorrobpm-frontend", "src", "composables", "backend-event-types.json");

    @Test
    void filesUnderTestExist() {
        // Молча сверять отсутствующий снимок нельзя — иначе тест «зелёный» в вакууме.
        assertThat(Files.isRegularFile(SNAPSHOT))
            .as("backend-event-types.json доступен из модуля zorrobpm-contract")
            .isTrue();
    }

    @Test
    void enumValuesMatchSnapshotExactly() throws IOException {
        Set<String> enumValues = Arrays.stream(DomainEventType.values())
            .map(DomainEventType::getValue)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> snapshotValues = snapshotValues(read(SNAPSHOT));

        assertThat(snapshotValues)
            .as("снимок не пуст — иначе сверка ни о чём")
            .isNotEmpty();
        assertThat(snapshotValues)
            .as("тип добавили в DomainEventType без обновления backend-event-types.json — "
                + "обнови JSON тем же коммитом, иначе фронт-тест проверяет протухший список")
            .containsExactlyInAnyOrderElementsOf(enumValues);
        assertThat(enumValues)
            .as("в снимке лишнее значение, которого нет в DomainEventType — "
                + "удали его из JSON тем же коммитом, что удалил тип")
            .containsExactlyInAnyOrderElementsOf(snapshotValues);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    /**
     * Значения массива {@code "values"} из снимка. Без Jackson-databind (в
     * contract только annotations — тянуть databind ради сверки строк не
     * окупается): массив ищется по ключу, строки — по кавычкам внутри него со
     * счётчиком глубины, поэтому {@code _comment} со своими кавычками в
     * выборку не попадает.
     */
    private static Set<String> snapshotValues(String json) {
        int key = json.indexOf("\"values\"");
        assertThat(key).as("в снимке есть ключ \"values\"").isNotNegative();
        int open = json.indexOf('[', key);
        assertThat(open).as("за ключом \"values\" идёт массив").isNotNegative();
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(json);
        int depth = 0;
        // Сканируем от '[': строки забираем только с глубины 1 (сам массив).
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    break;
                }
            } else if (c == '"' && depth == 1) {
                m.region(i, json.length());
                assertThat(m.lookingAt()).as("кавычка открывает строку").isTrue();
                out.add(m.group(1));
                i = m.end() - 1;
            }
        }
        return out;
    }
}
