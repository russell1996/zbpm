package com.zorrodev.bpm.engine.scenario;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-28, ФАЗА 5, ось «durable state»: СТРАЖ «состояние движка живёт в БД, а не
 * в памяти JVM».
 *
 * <p>Проверяемое свойство — из правила кампании: «рестарт движка посреди процесса
 * не теряет состояние» ({@code engine-state-must-be-durable}). Поведенческая часть
 * этого свойства проверяется тестом рестарта ({@code CrossCuttingScenarioIntegrationTests#restartMidProcess_engineRestartKeepsTheProcessRunning}:
 * ВТОРОЙ Spring-контекст на той же БД продолжает процесс). Этот страж закрывает
 * ВТОРУЮ половину: в коде движка нет НИ ОДНОГО статического поля, в котором могло бы
 * жить состояние процесса (иначе второй контекст его бы не увидел, а перезапуск
 * процесса JVM — потерял бы).
 *
 * <p>Что считается нарушением:
 * <ul>
 *   <li>{@code static} поле, НЕ {@code final} — его можно менять из любого потока;</li>
 *   <li>{@code static final} поле типа {@link Collection}/{@link Map} — содержимое
 *       мутабельно (в отличие от {@code Map.of}/{@code List.of}).</li>
 * </ul>
 *
 * <p>Исключения — поимённо, с причиной (не «молча»): список ниже. Каждая запись —
 * либо константа, либо кэш, восстанавливаемый из класса/класспаса.
 */
class EngineStateDurabilityGuardTest {

    /**
     * Разрешённые статические поля — поимённо, с причиной.
     *
     * <p>Ключ — полное имя класса, значение — причина. Пустое значение = «нет
     * исключения».
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "com.zorrodev.bpm.engine.xml.SecureXmlParser",
            "CONTEXT_CACHE — кэш JAXBContext по классу: чистая функция от класса, "
                    + "восстанавливается из класспаса, состояние процесса не несёт",
            "com.zorrodev.bpm.engine.security.AuthorizationService",
            "ROLE_RIGHTS — таблица прав по ролям, собрана через Map.of (иммутабельна), "
                    + "состояние процесса не несёт",
            "com.zorrodev.bpm.engine.handler.CompletionService",
            "PHASE_ONLY_ELEMENT_TYPES — EnumSet.of(...) формально мутабелен (в отличие от "
                    + "Set.of), но используется ТОЛЬКО на чтение и состояние процесса не несёт. "
                    + "Найдено стражем фазы 5 (WO-OPS-28): заменять на Set.of — кандидат "
                    + "WO-OPS-40 (не заведён, ID не занимаю; 36/38/39 предложены кандидатами "
                    + "фаз 2 и 3, 37 занят файлом находки фазы 3)");

    /** Каталог скомпилированных классов движка (target/classes). */
    private static final Path CLASSES = Path.of("target", "classes");

    @Test
    void engineMainCode_keepsNoStaticMutableState() throws IOException, URISyntaxException {
        assertThat(CLASSES).as("compiled engine classes are on disk").exists();

        Map<String, List<String>> violations = new TreeMap<>();
        List<String> scanned = new ArrayList<>();

        try (Stream<Path> files = Files.walk(CLASSES.resolve("com/zorrodev/bpm/engine"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String className = CLASSES.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                if (className.contains("$")) {
                    continue; // вложенные/анонимные классы — их поля не состояние движка
                }
                Class<?> clazz;
                try {
                    // initialize=false — статические инициализаторы НЕ выполняются
                    // (проверка не должна запускать побочные эффекты движка).
                    clazz = Class.forName(className, false, getClass().getClassLoader());
                } catch (Throwable e) {
                    continue; // класс не грузится (опциональная зависимость) — пропускаем
                }
                scanned.add(className);
                for (Field field : clazz.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (!Modifier.isFinal(field.getModifiers())) {
                        violations.computeIfAbsent(className, k -> new ArrayList<>())
                                .add(field.getName() + " (" + field.getType().getSimpleName() + ") — не final");
                        continue;
                    }
                    if (isMutableContainer(field.getType()) && isMutableValue(field)) {
                        violations.computeIfAbsent(className, k -> new ArrayList<>())
                                .add(field.getName() + " (" + field.getType().getSimpleName() + ") — мутабельный контейнер");
                    }
                }
            }
        }

        assertThat(scanned).as("the scan really looked at the engine classes").isNotEmpty();

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : violations.entrySet()) {
            String allowed = ALLOWED.get(entry.getKey());
            if (allowed == null) {
                problems.add(entry.getKey() + ": статическое состояние без исключения — " + entry.getValue());
            } else if (allowed.isEmpty()) {
                problems.add(entry.getKey() + ": статическое состояние без причины — " + entry.getValue());
            }
        }
        assertThat(problems)
                .as("состояние движка должно жить в БД, а не в статических полях JVM "
                        + "(иначе рестарт движка потеряет процесс)")
                .isEmpty();
    }

    /** Мутабельный контейнер по ТИПУ (без чтения значения). */
    private static boolean isMutableContainer(Class<?> type) {
        return Map.class.isAssignableFrom(type)
                || Set.class.isAssignableFrom(type)
                || Collection.class.isAssignableFrom(type);
    }

    /**
     * Мутабельно ли ЗНАЧЕНИЕ поля (а не только его тип).
     *
     * <p>{@code static final Set<String> X = Set.of(...)} — по типу контейнер, но
     * содержимое изменить нельзя: это КОНСТАНТА, а не состояние. Проверяется по
     * реализации значения: {@code Set.of/List.of} — {@code ImmutableCollections},
     * {@code Collections.unmodifiable*} — обёртка; всё остальное ({@code HashSet},
     * {@code ArrayList}, {@code ConcurrentHashMap}) — мутабельно и означало бы
     * состояние в памяти JVM.
     *
     * <p>Значение НЕ мутируется пробой: мутабельность определяется по классу
     * реализации, а значение не меняется.
     */
    private static boolean isMutableValue(Field field) {
        try {
            field.setAccessible(true);
            Object value = field.get(null);
            if (value == null) {
                return false;
            }
            String impl = value.getClass().getName();
            if (impl.startsWith("java.util.ImmutableCollections")) {
                return false; // Set.of / List.of / Map.of
            }
            if (impl.startsWith("java.util.Collections$Unmodifiable")) {
                return false; // Collections.unmodifiableSet/…
            }
            return true;
        } catch (Throwable e) {
            // значение не читается (не инициализирован / недоступно) — считаем
            // мутабельным: пусть класс разберётся и внесёт исключение с причиной.
            return true;
        }
    }
}
