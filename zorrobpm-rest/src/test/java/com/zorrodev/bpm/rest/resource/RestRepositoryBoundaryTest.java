package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DEBT-7 Срез 0: архитектурная граница «веб-слой не лезет в JPA напрямую».
 *
 * <p>Классы из {@code rest/resource/**} не должны зависеть от
 * {@code engine.repository.*} / {@code engine.entity.*} — ни через import, ни
 * через fully-qualified использование (оба варианта ловит один паттерн).
 *
 * <p>Срез 0 фиксирует существующую границу, а не чинит её: текущие нарушители
 * перечислены в {@link #WHITELIST}. Каждый следующий срез эпика убирает из
 * whitelist по файлу; финальный срез оставляет его пустым.
 */
class RestRepositoryBoundaryTest {

    /** Matches both {@code import ...engine.repository.X;} and fully-qualified usages. */
    private static final Pattern ENGINE_PERSISTENCE =
            Pattern.compile("com\\.zorrodev\\.bpm\\.engine\\.(repository|entity)\\.");

    /**
     * Текущие нарушители границы (сверено с диском 2026-09-12: аудит говорил о
     * 18 файлах, по факту в {@code rest/resource/**} их 16 — ещё 5 файлов с
     * теми же импортами лежат в {@code rest/security/**}, вне правила этого
     * эпика, см. отчёт Среза 0).
     */
    private static final Set<String> WHITELIST = Set.of(
            "AuditLogResource",
            "AuthResource",
            "OutboxAdminResource",
            "ProcessDefinitionResource",
            "UserTaskRuntimeOperationsImpl");

    private static Set<String> actualViolators() {
        Path root = Paths.get("src/main/java/com/zorrodev/bpm/rest/resource");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(RestRepositoryBoundaryTest::usesEnginePersistence)
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .collect(TreeSet::new, Set::add, Set::addAll);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean usesEnginePersistence(Path javaFile) {
        try {
            return Files.lines(javaFile).anyMatch(l -> ENGINE_PERSISTENCE.matcher(l).find());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Граница не расползается: ни один файл ВНЕ whitelist не зависит от
     * engine.repository/entity. POF: убери любую запись из whitelist — тест
     * краснеет именно на этом файле.
     */
    @Test
    void restResource_doesNotDependOnEnginePersistence() {
        Set<String> actual = actualViolators();
        List<String> unlisted = actual.stream()
                .filter(name -> !WHITELIST.contains(name))
                .sorted()
                .toList();
        assertThat(unlisted)
                .as("rest/resource classes depending on engine.repository/entity outside the whitelist")
                .isEmpty();
    }

    /**
     * Whitelist не врёт в обе стороны: он в точности равен множеству текущих
     * нарушителей. Починенный файл из whitelist убирает тот же срез, что его
     * чинит (иначе этот тест краснеет на stale-записи); новый нарушитель
     * краснеет здесь же, а не только в тесте выше.
     */
    @Test
    void whitelist_matchesActualViolatorsExactly() {
        assertThat(actualViolators())
                .as("whitelist must equal the actual violator set (fix a file -> drop its entry in the same slice)")
                .isEqualTo(new TreeSet<>(WHITELIST));
    }
}
