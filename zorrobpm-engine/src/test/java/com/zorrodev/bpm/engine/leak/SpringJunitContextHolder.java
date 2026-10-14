package com.zorrodev.bpm.engine.leak;

import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * WO-OPS-26: мост «Spring TestContext → текущий JUnit ExtensionContext».
 *
 * <p>Проблема: {@code TestExecutionListener.afterTestExecution} получает
 * Spring-{@code TestContext} (метод-класс), а проверка живёт на JUnit
 * {@code ExtensionContext} (там baseline в Store и test-id). Мост: JUnit-сторона
 * расширения ({@code BeforeEachCallback}, выполняется в той же фазе и том же
 * потоке, что весь тест) кладёт текущий контекст в ThreadLocal; listener
 * забирает его в {@code afterTestExecution} того же потока.
 */
public final class SpringJunitContextHolder {

    private static final ThreadLocal<ExtensionContext> CURRENT = new ThreadLocal<>();

    private SpringJunitContextHolder() {
    }

    public static void set(ExtensionContext context) {
        CURRENT.set(context);
    }

    public static ExtensionContext current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
