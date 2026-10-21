package com.zorrodev.bpm.engine.service.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-38: СТРАЖ ПОРЯДКА — зачистка подписок терминального инстанса обязана
 * идти ПОСЛЕ терминальной пометки.
 *
 * <p><b>Что это закрывает.</b> Правило «терминальный инстанс не держит подписок»
 * живёт в {@code FinishedInstanceSubscriptionCleaner}, а зовётся из терминального
 * перехода. Если вызов уедет ВЫШЕ терминальной пометки, инстанс ещё «жив» в тот
 * момент, когда его подписки уже сняты, — и доставка, успевшая забрать подписку
 * миллисекундой раньше, запустит handler (неинтерруптирующий event sub-process)
 * в умирающем инстансе: guard «instance already completed»
 * ({@code EventTrigger.triggerEventSubprocess}) его уже не поймает, потому что
 * пометки ещё нет.
 *
 * <p><b>Почему страж, а не поведенческий тест.</b> Разница между «до» и «после»
 * наблюдаема ТОЛЬКО в гонке доставки с отменой (окно в миллисекунды между
 * чтением подписки и её снятием) — такой тест был бы флейковым по построению
 * (P-10) и ничего не доказывал бы в 99% прогонов. Поэтому порядок зафиксирован
 * стражем по ИСХОДНИКУ на диске — тем же приёмом, что
 * {@code DeployCopyListGuardTest} и {@code ModuleConfigImportGuardTest}:
 * читаем НАСТОЯЩИЙ файл (G-N: никакой копии логики в теле теста) и проверяем
 * порядок вызовов в нём.
 *
 * <p><b>Что ещё проверяет страж.</b> Что ручного удаления подписок в kill-пути
 * движка не осталось ({@code InstanceCancellationService.kill}): правило — одно,
 * иначе следующий читатель найдёт «два правила» и будет править не то.
 */
class TerminalSubscriptionCleanupOrderGuardTest {

    /** Терминальный переход ОТМЕНЫ — зачистка обязана быть ПОСЛЕ пометки. */
    private static final String CANCEL_METHOD = "public void cancelProcessInstance(UUID processInstanceId)";

    /** Терминальный переход НОРМАЛЬНОГО завершения — то же правило (WO-OPS-34). */
    private static final String COMPLETE_METHOD = "public void completeProcessInstance(UUID processInstanceId)";

    private static final String CLEANER_CALL = "finishedInstanceSubscriptionCleaner.clear(";
    private static final String SET_CANCELLED = "setCancelled(";
    private static final String SET_COMPLETED_AT = "setCompletedAt(";

    @Test
    void cancelTransition_clearsSubscriptions_afterTheTerminalMarking() throws IOException {
        String body = methodBody(sourceOf("com/zorrodev/bpm/engine/service/db/ProcessInstanceDbOperationsImpl.java"),
            CANCEL_METHOD);

        int clear = body.indexOf(CLEANER_CALL);
        int cancelled = body.indexOf(SET_CANCELLED);
        int completedAt = body.indexOf(SET_COMPLETED_AT);

        assertThat(clear).as("зачистка подписок есть в терминальном переходе отмены").isGreaterThanOrEqualTo(0);
        assertThat(cancelled).as("терминальная пометка cancelled есть").isGreaterThanOrEqualTo(0);
        assertThat(completedAt).as("терминальная пометка completedAt есть").isGreaterThanOrEqualTo(0);
        assertThat(clear)
            .as("зачистка подписок ПОСЛЕ пометки cancelled (иначе доставка успеет в мёртвый инстанс)")
            .isGreaterThan(cancelled);
        assertThat(clear)
            .as("зачистка подписок ПОСЛЕ пометки completedAt")
            .isGreaterThan(completedAt);
    }

    @Test
    void completeTransition_clearsSubscriptions_afterTheTerminalMarking() throws IOException {
        String body = methodBody(sourceOf("com/zorrodev/bpm/engine/service/db/ProcessInstanceDbOperationsImpl.java"),
            COMPLETE_METHOD);

        int clear = body.indexOf(CLEANER_CALL);
        int completedAt = body.indexOf(SET_COMPLETED_AT);

        assertThat(clear).as("зачистка подписок есть в терминальном переходе завершения").isGreaterThanOrEqualTo(0);
        assertThat(completedAt).as("терминальная пометка completedAt есть").isGreaterThanOrEqualTo(0);
        assertThat(clear).as("зачистка подписок ПОСЛЕ пометки completedAt").isGreaterThan(completedAt);
    }

    @Test
    void killPath_doesNotDeleteSubscriptionsByHand() throws IOException {
        String source = sourceOf("com/zorrodev/bpm/engine/service/InstanceCancellationService.java");
        String body = methodBody(source, "private void kill(UUID processInstanceId)");

        assertThat(body)
            .as("kill-путь не удаляет подписи руками — это делает терминальный переход (одно правило)")
            .doesNotContain("deleteMessageSubscriptionsByProcessInstanceId")
            .doesNotContain("deleteSignalSubscriptionsByProcessInstanceId");
        assertThat(body)
            .as("kill-путь ведёт в терминальный переход отмены")
            .contains("processInstanceDbOperations.cancelProcessInstance(");
    }

    // ── помощники ───────────────────────────────────────────────────────────────────────

    /** Исходник файла относительно корня репозитория (по МАРКЕРАМ, как в app-guard'ах). */
    private static String sourceOf(String relativeToModuleSources) throws IOException {
        Path path = repoRoot().resolve("zorrobpm-engine/src/main/java").resolve(relativeToModuleSources);
        assertThat(Files.exists(path)).as("исходник на диске: %s", path).isTrue();
        return Files.readString(path);
    }

    /**
     * Тело метода по его сигнатуре: от первой фигурной скобки сигнатуры до
     * парной закрывающей (скобки считаются — в теле метода есть и вложенные).
     */
    private static String methodBody(String source, String signature) {
        int sig = source.indexOf(signature);
        assertThat(sig).as("метод найден в исходнике: %s", signature).isGreaterThanOrEqualTo(0);
        int open = source.indexOf('{', sig + signature.length());
        assertThat(open).as("тело метода открывается: %s", signature).isGreaterThanOrEqualTo(0);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("тело метода не закрылось: " + signature);
    }

    /** Корень репозитория — по МАРКЕРАМ (тест гоняется и из каталога модуля, и из корня). */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("zorrobpm-engine"))
                && Files.isDirectory(dir.resolve("ci"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("корень репозитория не найден (cwd=" + Path.of("") + ")");
    }
}
