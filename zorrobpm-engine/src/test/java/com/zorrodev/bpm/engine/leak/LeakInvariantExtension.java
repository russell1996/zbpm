package com.zorrodev.bpm.engine.leak;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.AssertionFailedError;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * WO-OPS-26: глобальный инвариант утечек BPMN во всех тестах движка.
 *
 * <p>Автоподключение — через {@code junit-platform.properties}
 * ({@code junit.platform.extensions.autodetection.enabled=true}) +
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension}; правок
 * {@code pom.xml}/{@code ci/}/{@code .gitlab-ci.yml} нет.
 *
 * <p>Как работает: в {@code beforeEach} снимает baseline завершённых инстансов;
 * в {@code afterEach} проверяет {@link LeakInvariantChecker}'ом инстансы,
 * завершённые за время теста. Порядок относительно отката {@code @Transactional}
 * критичен: проверка обязана выполняться ДО отката транзакции теста (иначе
 * видит пустую БД и всегда зелёная). JUnit-callback'и расширения гарантированно
 * идут ДО {@code afterTestMethod}-слушателей Spring (в т.ч.
 * {@code TransactionalTestExecutionListener}, который делает откат): Spring
 * выполняет {@code afterTestExecution}-callback'и перед
 * {@code afterTestMethod}-слушателями, а JUnit вызывает {@code AfterEachCallback}
 * внутри фазы {@code afterTestExecution}. Это доказывает
 * {@code LeakInvariantExtensionTests#orderProbe_*}: мутация порядка
 * (проверка после отката) роняет orderProbe.
 *
 * <p>Режимы ({@code -Dzbpm.leakInvariant=report|enforce}, по умолчанию
 * {@code report}): {@code report} — только строка на нарушение в
 * {@code target/leak-invariant-report.txt}; {@code enforce} — падение теста
 * (кроме allowlist). Allowlist — {@code leak-invariant-allowlist.txt} в
 * test-resources, построчно {@code Класс[#метод] :: причина}.
 */
public class LeakInvariantExtension implements BeforeEachCallback, AfterEachCallback {

    public static final String MODE_PROPERTY = "zbpm.leakInvariant";
    public static final String REPORT_PROPERTY = "zbpm.leakInvariant.report";
    public static final String ALLOWLIST_RESOURCE = "leak-invariant-allowlist.txt";

    /**
     * Spring-слушатель-двойник: регистрируется АВТОМАТИЧЕСКИ для каждого теста
     * через {@code META-INF/spring.factories} ({@code TestExecutionListener}),
     * выполняется в фазе {@code afterTestExecution} — СТРОГО ДО
     * {@code TransactionalTestExecutionListener.afterTestMethod} (order 4000),
     * который делает откат {@code @Transactional}. Здесь и живёт проверка:
     * та же транзакция теста, данные ещё не откачены.
     *
     * <p>JUnit-сторона ({@code BeforeEachCallback}) только снимает baseline
     * (ему транзакция не нужна); {@code AfterEachCallback} — страховочная
     * проверка для НЕ-транзакционных тестов (там listener уже отработал и
     * пометил контекст — повтор не выполняется; для тестов вовсе без Spring
     * jdbcOrNull вернёт null и обе стороны тихо выйдут).
     */

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(LeakInvariantExtension.class);
    private static final String BASELINE_KEY = "baseline";

    /** Маркер «listener уже проверил этот тест» (защита от двойной проверки). */
    private static final String LISTENER_DONE_KEY = "listenerDone";

    /**
     * Baseline по Spring-ключу «класс#метод»: мост JUnit→Spring (один поток,
     * beforeEach раньше afterTestExecution — порядок документирован).
     */
    static final ConcurrentMap<String, Set<UUID>> BASELINES = new ConcurrentHashMap<>();

    /** Сколько проверок выполнено (хук для тестов расширения). */
    static final AtomicLong CHECKS_PERFORMED = new AtomicLong();

    /**
     * Нарушения последней проверки по test-id (хук для тестов расширения;
     * включает allowlisted — тест сам решает, что assert'ить).
     */
    static final ConcurrentMap<String, List<LeakInvariantChecker.Violation>> LAST_VIOLATIONS =
        new ConcurrentHashMap<>();

    @Override
    public void beforeEach(ExtensionContext context) {
        JdbcTemplate jdbc = jdbcOrNull(context);
        if (jdbc == null) {
            return;
        }
        Set<UUID> baseline = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbc);
        context.getStore(NAMESPACE).put(context.getUniqueId() + BASELINE_KEY, baseline);
        context.getStore(NAMESPACE).remove(context.getUniqueId() + LISTENER_DONE_KEY);
        BASELINES.put(springKey(context), baseline);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        try {
            // Транзакционный путь уже проверен listener'ом в afterTestExecution
            // (до отката) — здесь только не-транзакционные тесты.
            if (context.getStore(NAMESPACE).get(context.getUniqueId() + LISTENER_DONE_KEY)
                != null) {
                return;
            }
            checkAndReport(context);
        } finally {
            BASELINES.remove(springKeyOrNull(context));
        }
    }

    private static String springKey(ExtensionContext context) {
        return context.getRequiredTestClass().getName() + "#"
            + context.getRequiredTestMethod().getName();
    }

    private static String springKeyOrNull(ExtensionContext context) {
        try {
            return springKey(context);
        } catch (RuntimeException e) {
            return "<unknown>";
        }
    }

    /**
     * Spring-сторона проверки: вызывается listener'ом в
     * {@code afterTestExecution} — до отката {@code @Transactional}.
     */
    static void checkAndReport(ExtensionContext context) {
        JdbcTemplate jdbc = jdbcOrNull(context);
        if (jdbc == null) {
            return;
        }
        Object baseline = context.getStore(NAMESPACE).get(context.getUniqueId() + BASELINE_KEY);
        if (!(baseline instanceof Set<?> baselineSet)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Set<UUID> baselineIds = (Set<UUID>) baselineSet;
        List<LeakInvariantChecker.Violation> violations =
            LeakInvariantChecker.check(jdbc, baselineIds);
        CHECKS_PERFORMED.incrementAndGet();
        LAST_VIOLATIONS.put(springKeyOrNull(context), List.copyOf(violations));

        String testId = testId(context);
        List<AllowEntry> allowlist = loadAllowlist();
        List<LeakInvariantChecker.Violation> hard = new ArrayList<>();
        List<LeakInvariantChecker.Violation> allowed = new ArrayList<>();
        for (LeakInvariantChecker.Violation v : violations) {
            if (isAllowlisted(allowlist, context, v)) {
                allowed.add(v);
            } else {
                hard.add(v);
            }
        }
        appendReport(testId, hard, allowed);
        if ("enforce".equalsIgnoreCase(mode()) && !hard.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                "WO-OPS-26 leak invariant: completed instance(s) left live residue:\n");
            for (LeakInvariantChecker.Violation v : hard) {
                sb.append("  ").append(testId).append(" ").append(v).append('\n');
            }
            throw new AssertionFailedError(sb.toString());
        }
    }

    static String mode() {
        return System.getProperty(MODE_PROPERTY, "report").trim().toLowerCase();
    }

    static String testId(ExtensionContext context) {
        String method;
        try {
            method = context.getRequiredTestMethod().getName();
        } catch (RuntimeException e) {
            method = "<no-method>";
        }
        String cls;
        try {
            cls = context.getRequiredTestClass().getName();
        } catch (RuntimeException e) {
            cls = "<no-class>";
        }
        return cls + "#" + method;
    }

    private static JdbcTemplate jdbcOrNull(ExtensionContext context) {
        ApplicationContext spring;
        try {
            spring =
                org.springframework.test.context.junit.jupiter.SpringExtension
                    .getApplicationContext(context);
        } catch (IllegalStateException e) {
            // Нет Spring-контекста (чистый unit-тест) — нечего проверять.
            return null;
        }
        try {
            return spring.getBean(JdbcTemplate.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    record AllowEntry(String pattern, String reason) {
    }

    static List<AllowEntry> loadAllowlist() {
        List<AllowEntry> out = new ArrayList<>();
        try (var in = LeakInvariantExtension.class.getClassLoader()
            .getResourceAsStream(ALLOWLIST_RESOURCE)) {
            if (in == null) {
                return out;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int sep = line.indexOf("::");
                if (sep <= 0) {
                    continue;
                }
                String pattern = line.substring(0, sep).trim();
                String reason = line.substring(sep + 2).trim();
                if (!pattern.isEmpty() && !reason.isEmpty()) {
                    out.add(new AllowEntry(pattern, reason));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static boolean isAllowlistedPattern(List<AllowEntry> allowlist, String key) {
        int hash = key.lastIndexOf('#');
        String fqn = hash < 0 ? key : key.substring(0, hash);
        String method = hash < 0 ? "" : key.substring(hash + 1);
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        for (AllowEntry e : allowlist) {
            String p = e.pattern();
            if (p.equals(fqn) || p.equals(simple)
                || p.equals(fqn + "#" + method) || p.equals(simple + "#" + method)) {
                return true;
            }
        }
        return false;
    }

    static boolean isAllowlisted(List<AllowEntry> allowlist, ExtensionContext context,
        LeakInvariantChecker.Violation violation) {
        String fqn;
        String simple;
        String method;
        try {
            fqn = context.getRequiredTestClass().getName();
            simple = context.getRequiredTestClass().getSimpleName();
            method = context.getRequiredTestMethod().getName();
        } catch (RuntimeException e) {
            return false;
        }
        for (AllowEntry e : allowlist) {
            String p = e.pattern();
            if (p.equals(fqn) || p.equals(simple)
                || p.equals(fqn + "#" + method) || p.equals(simple + "#" + method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spring TestExecutionListener-двойник: выполняет проверку в фазе
     * {@code afterTestExecution} КАЖДОГО теста — строго ДО
     * {@code afterTestMethod} {@code TransactionalTestExecutionListener}
     * (order 4000), где происходит откат {@code @Transactional}. Та же
     * транзакция теста, данные ещё не откачены — checker видит живые строки.
     *
     * <p>Как listener получает baseline: Spring выполняет listener'ы в потоке
     * теста, а JUnit-сторона ({@code BeforeEachCallback}, тот же поток)
     * кладёт baseline в static-карту по Spring-ключу
     * «класс#метод» ({@code TestContext.getTestClass/getTestMethod}).
     * ThreadLocal-мост НЕ используется: порядок listener vs callback
     * документирован байткодом Spring, а не надеждой на поток.
     * Fallback без baseline: пустой baseline = проверяются ВСЕ завершённые
     * инстансы (консервативно).
     *
     * <p>Регистрируется через {@code META-INF/spring.factories} (дефолтные
     * слушатели) — отдельным классам ничего вешать не надо.
     */
    public static class LeakInvariantTestListener extends AbstractTestExecutionListener {

        @Override
        public int getOrder() {
            return 3000;
        }

        @Override
        public void afterTestExecution(TestContext testContext) {
            ApplicationContext spring = testContext.getApplicationContext();
            JdbcTemplate jdbc;
            try {
                jdbc = spring.getBean(JdbcTemplate.class);
            } catch (RuntimeException e) {
                return;
            }
            if (jdbc == null) {
                return;
            }
            String key = testContext.getTestClass().getName() + "#"
                + testContext.getTestMethod().getName();
            Set<UUID> baseline = BASELINES.getOrDefault(key, Set.of());
            List<LeakInvariantChecker.Violation> violations =
                LeakInvariantChecker.check(jdbc, baseline);
            CHECKS_PERFORMED.incrementAndGet();
            LAST_VIOLATIONS.put(key, List.copyOf(violations));
            List<AllowEntry> allowlist = loadAllowlist();
            List<LeakInvariantChecker.Violation> hard = new ArrayList<>();
            List<LeakInvariantChecker.Violation> allowed = new ArrayList<>();
            for (LeakInvariantChecker.Violation v : violations) {
                if (isAllowlistedPattern(allowlist, key)) {
                    allowed.add(v);
                } else {
                    hard.add(v);
                }
            }
            appendReport(key, hard, allowed);
            if ("enforce".equalsIgnoreCase(mode()) && !hard.isEmpty()) {
                StringBuilder sb = new StringBuilder(
                    "WO-OPS-26 leak invariant: completed instance(s) left live residue:\n");
                for (LeakInvariantChecker.Violation v : hard) {
                    sb.append("  ").append(key).append(" ").append(v).append('\n');
                }
                throw new AssertionFailedError(sb.toString());
            }
        }
    }

    private static void appendReport(String testId,
        List<LeakInvariantChecker.Violation> hard,
        List<LeakInvariantChecker.Violation> allowed) {
        if (hard.isEmpty() && allowed.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (LeakInvariantChecker.Violation v : hard) {
            sb.append("violation | ").append(testId).append(" | ").append(v).append('\n');
        }
        for (LeakInvariantChecker.Violation v : allowed) {
            sb.append("allowed | ").append(testId).append(" | ").append(v).append('\n');
        }
        Path report = Paths.get(
            System.getProperty(REPORT_PROPERTY, "target/leak-invariant-report.txt"));
        try {
            if (report.getParent() != null) {
                Files.createDirectories(report.getParent());
            }
            // Один write-вызов на тест: при forkCount=2 два форка дописывают
            // конкурентно, O_APPEND держит построчную атомарность.
            Files.writeString(report, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
