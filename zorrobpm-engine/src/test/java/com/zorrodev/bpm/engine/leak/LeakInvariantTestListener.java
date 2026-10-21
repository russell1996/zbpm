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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.opentest4j.AssertionFailedError;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * WO-OPS-26: глобальный инвариант утечек BPMN во всех тестах движка.
 * WO-OPS-32: режим по умолчанию переключён {@code report} → {@code enforce},
 * атрибуция нарушений исправлена (свой инстанс теста / чужой инстанс общей БД).
 *
 * <p><b>Где живёт проверка.</b> Один механизм — Spring
 * {@link TestExecutionListener} (регистрация: {@code META-INF/spring.factories},
 * {@code org.springframework.test.context.TestExecutionListener}), фазы:
 * <ul>
 *   <li>{@code beforeTestMethod} (order 3000) — снимок baseline: id всех
 *       инстансов и id завершённых. Порядок 3000 ВЫШЕ, чем у
 *       {@code TransactionalTestExecutionListener} (4000), поэтому снимок
 *       снимается ДО открытия транзакции теста и видит только committed-состояние
 *       БД — то есть ровно «до теста»;</li>
 *   <li>{@code afterTestExecution} (order 3000) — проверка. Эта фаза идёт
 *       ДО {@code afterTestMethod}, где транзакционный слушатель делает откат
 *       ({@code @Transactional}), поэтому проверка видит НЕЗАКОММИЧЕННЫЕ данные
 *       теста. Именно это доказывает
 *       {@code LeakInvariantListenerTests#orderProbe_*}: мутация «проверка после
 *       отката» роняет orderProbe.</li>
 * </ul>
 *
 * <p><b>Почему не JUnit-расширение.</b> В WO-OPS-26 проверка была продублирована
 * в JUnit-расширении, подключавшемся через
 * {@code junit-platform.properties: junit.platform.extensions.autodetection.enabled=true}.
 * Это свойство — для Jupiter-расширений нужно
 * {@code junit.jupiter.extensions.autodetection.enabled=true} в
 * {@code junit-jupiter.properties}; в указанном файле такого свойства нет, поэтому
 * JUnit-половина НИКОГДА не запускалась (доказано: в прогоне нет ни одного
 * вызова её {@code beforeEach}). Из-за этого baseline в
 * {@code LeakInvariantTestListener} всегда был ПУСТЫМ, и проверка рапортовала
 * остаток ВСЕХ завершённых инстансов общей БД под тестом, который их не
 * создавал: полный прогон давал 1077 «нарушений», из которых 1067 были чужими
 * (проверено изоляционным прогоном: {@code DmnEvaluationIntegrationTests} и
 * {@code FeelIoMappingConformanceIntegrationTests} дают 0 нарушений поодиночке и
 * 51/282 — в общем наборе). Половина, которая не работала, удалена (P-14:
 * мёртвый механизм в тестах — ловушка для следующего читателя), а пустой
 * baseline теперь означает «проверять нечего», а не «проверяй всё подряд».
 *
 * <p><b>Режимы</b> ({@code -Dzbpm.leakInvariant=report|enforce}, по умолчанию
 * {@value #DEFAULT_MODE}): {@code report} — только строка на нарушение в
 * {@code target/leak-invariant-report.txt}; {@code enforce} — падение теста
 * (кроме allowlist). Чтобы СОБРАТЬ отчёт вместо падения, режим переключается
 * системным свойством В ФОРКЕ тестов: {@code -DargLine="-Dzbpm.leakInvariant=report"}
 * (свойство из командной строки Maven в форк surefire/failsafe не доезжает —
 * то же ограничение, что и для пути отчёта).
 * Allowlist — {@code leak-invariant-allowlist.txt} в test-resources, построчно
 * {@code Класс[#метод] :: причина} (причина обязана называть WO-id).
 *
 * <p><b>Атрибуция (WO-OPS-32).</b> Принуждаются только нарушения инстансов,
 * СОЗДАННЫХ самим тестом («свои»): инстанс, начатый чужим тестом, может
 * завершиться в окне текущего — его остаток записывался в отчёт за текущим
 * тестом. Такие («чужие») идут в отдельный файл
 * {@code target/leak-invariant-foreign.txt} и НИКОГДА не роняют сборку: это
 * артефакт общей БД, а не свойство теста.
 */
public class LeakInvariantTestListener extends AbstractTestExecutionListener {

    public static final String MODE_PROPERTY = "zbpm.leakInvariant";
    public static final String REPORT_PROPERTY = "zbpm.leakInvariant.report";
    public static final String FOREIGN_REPORT_PROPERTY = "zbpm.leakInvariant.foreignReport";
    public static final String ALLOWLIST_RESOURCE = "leak-invariant-allowlist.txt";

    /** Режим по умолчанию. WO-OPS-32: переключён с {@code report} на enforce. */
    public static final String DEFAULT_MODE = "enforce";

    /** Имя файла отчёта (нарушения СВОИХ инстансов — принуждаемые). */
    public static final String REPORT_FILE = "leak-invariant-report.txt";

    /** Имя файла отчёта (чужие инстансы общей БД — только для разбора). */
    public static final String FOREIGN_REPORT_FILE = "leak-invariant-foreign.txt";

    /**
     * Baseline по ключу «класс#метод» (Spring-ключ теста). Снимается в
     * {@code beforeTestMethod} и снимается в {@code afterTestExecution}.
     */
    static final ConcurrentMap<String, LeakInvariantChecker.Baseline> BASELINES =
        new ConcurrentHashMap<>();

    /** Сколько проверок выполнено (хук для тестов расширения). */
    static final AtomicLong CHECKS_PERFORMED = new AtomicLong();

    /** Нарушения последней проверки по test-id (хук для тестов расширения). */
    static final ConcurrentMap<String, List<LeakInvariantChecker.Violation>> LAST_VIOLATIONS =
        new ConcurrentHashMap<>();

    @Override
    public int getOrder() {
        return 3000;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        JdbcTemplate jdbc = jdbcOrNull(testContext);
        if (jdbc == null) {
            return;
        }
        BASELINES.put(key(testContext), LeakInvariantChecker.snapshot(jdbc));
    }

    @Override
    public void afterTestExecution(TestContext testContext) {
        JdbcTemplate jdbc = jdbcOrNull(testContext);
        if (jdbc == null) {
            return;
        }
        String testId = key(testContext);
        LeakInvariantChecker.Baseline baseline = BASELINES.remove(testId);
        if (baseline == null) {
            // Снимка нет — проверять нечего. Раньше здесь был пустой baseline,
            // и проверка рапортовала остаток ВСЕЙ общей БД под этим тестом
            // (см. javadoc класса).
            return;
        }
        Evaluation evaluation = evaluate(jdbc, baseline, testId, isTransactional(testContext));
        CHECKS_PERFORMED.incrementAndGet();
        LAST_VIOLATIONS.put(testId, List.copyOf(evaluation.allOwn()));

        appendReport(testId, evaluation.hard(), evaluation.allowed(), REPORT_FILE,
            REPORT_PROPERTY);
        appendReport(testId, evaluation.commitPhase(), List.of(), "commit-phase",
            REPORT_FILE, REPORT_PROPERTY);
        appendReport(testId, evaluation.foreign(), List.of(), FOREIGN_REPORT_FILE,
            FOREIGN_REPORT_PROPERTY);
        enforce(testId, evaluation.hard());
    }

    private static String key(TestContext testContext) {
        return testContext.getTestClass().getName() + "#"
            + testContext.getTestMethod().getName();
    }

    private static JdbcTemplate jdbcOrNull(TestContext testContext) {
        try {
            return testContext.getApplicationContext().getBean(JdbcTemplate.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Результат одной проверки, разложенный по категориям. */
    record Evaluation(List<LeakInvariantChecker.Violation> hard,
        List<LeakInvariantChecker.Violation> allowed,
        List<LeakInvariantChecker.Violation> commitPhase,
        List<LeakInvariantChecker.Violation> foreign) {

        /** Все нарушения СВОИХ инстансов (allowlisted + не allowlisted). */
        List<LeakInvariantChecker.Violation> allOwn() {
            List<LeakInvariantChecker.Violation> all = new ArrayList<>(hard);
            all.addAll(allowed);
            all.addAll(commitPhase);
            return all;
        }
    }

    /**
     * Проверяет и раскладывает нарушения по категориям. Общая точка для
     * принуждения и отчёта — чтобы «что принуждается» и «что только в отчёт»
     * было одно и то же место, а не две копии правил.
     */
    static Evaluation evaluate(JdbcTemplate jdbc, LeakInvariantChecker.Baseline baseline,
        String testId, boolean transactional) {
        LeakInvariantChecker.CheckResult result = LeakInvariantChecker.check(jdbc, baseline);
        List<AllowEntry> allowlist = loadAllowlist();
        List<LeakInvariantChecker.Violation> hard = new ArrayList<>();
        List<LeakInvariantChecker.Violation> allowed = new ArrayList<>();
        List<LeakInvariantChecker.Violation> commitPhase = new ArrayList<>();
        for (LeakInvariantChecker.Violation v : result.own()) {
            if (isAllowlistedPattern(allowlist, testId)) {
                allowed.add(v);
            } else if (transactional && COMMIT_PHASE_TYPES.contains(v.type())) {
                // Строки, которые движок чистит В ФАЗЕ КОМИТА
                // (clearInstanceBookkeepingAtCommit → beforeCommit): в
                // @Transactional-тесте коммита не будет (откат), поэтому в
                // прод-состоянии их не было бы. Это НЕ утечка — это снимок
                // ДО коммита. Только отчёт, никогда не принуждается.
                commitPhase.add(v);
            } else {
                hard.add(v);
            }
        }
        // Чужие инстансы (общая БД) — только отчёт: принуждать их нельзя,
        // это не свойство теста (см. javadoc класса).
        return new Evaluation(List.copyOf(hard), List.copyOf(allowed), List.copyOf(commitPhase),
            List.copyOf(result.foreign()));
    }

    /** Типы остатка, которые движок чистит в фазе коммита (см. evaluate). */
    static final Set<String> COMMIT_PHASE_TYPES = Set.of("arrival");

    /** Тест транзакционный? Тогда фазы коммита не будет — см. evaluate. */
    static boolean isTransactional(TestContext testContext) {
        return isTransactional(testContext.getTestMethod(), testContext.getTestClass());
    }

    /**
     * Тест транзакционный (метод или класс помечены {@code @Transactional},
     * в т.ч. мета-аннотацией)? Тогда фазы коммита не будет — движок не выполнит
     * зачистку в {@code beforeCommit}, и «до-коммитный» снимок остатка нельзя
     * читать как прод-состояние (см. {@link #COMMIT_PHASE_TYPES}).
     */
    static boolean isTransactional(java.lang.reflect.Method testMethod, Class<?> testClass) {
        return (testMethod != null
                && AnnotationUtils.findAnnotation(testMethod, Transactional.class) != null)
            || (testClass != null
                && AnnotationUtils.findAnnotation(testClass, Transactional.class) != null);
    }

    /**
     * Принуждение (режим enforce): не allowlisted нарушения СВОЕГО инстанса
     * роняют тест. Вынесено отдельно, чтобы «красная точка» принуждения была
     * одна (и её можно отключить мутацией, которая обязана ронять тест).
     */
    static void enforce(String testId, List<LeakInvariantChecker.Violation> hard) {
        if (!"enforce".equalsIgnoreCase(mode()) || hard.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(
            "WO-OPS-26 leak invariant: completed instance(s) left live residue:\n");
        for (LeakInvariantChecker.Violation v : hard) {
            sb.append("  ").append(testId).append(" ").append(v).append('\n');
        }
        throw new AssertionFailedError(sb.toString());
    }

    static String mode() {
        return System.getProperty(MODE_PROPERTY, DEFAULT_MODE).trim().toLowerCase();
    }

    record AllowEntry(String pattern, String reason) {
    }

    static List<AllowEntry> loadAllowlist() {
        List<AllowEntry> out = new ArrayList<>();
        try (var in = LeakInvariantTestListener.class.getClassLoader()
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

    private static void appendReport(String testId,
        List<LeakInvariantChecker.Violation> hard,
        List<LeakInvariantChecker.Violation> allowed,
        String fileName,
        String property) {
        appendReport(testId, hard, allowed, "violation", fileName, property);
    }

    private static void appendReport(String testId,
        List<LeakInvariantChecker.Violation> hard,
        List<LeakInvariantChecker.Violation> allowed,
        String prefix,
        String fileName,
        String property) {
        if (hard.isEmpty() && allowed.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (LeakInvariantChecker.Violation v : hard) {
            sb.append(prefix).append(" | ").append(testId).append(" | ").append(v).append('\n');
        }
        for (LeakInvariantChecker.Violation v : allowed) {
            sb.append("allowed | ").append(testId).append(" | ").append(v).append('\n');
        }
        // Путь отчёта: surefire-форк стартует с workingDirectory = basedir
        // МОДУЛЯ (zorrobpm-engine), а failsafe — с basedir КОРНЯ; свойство
        // -Dzbpm.leakInvariant.report НЕ доходит до surefire/failsafe-форков
        // как system-свойство во всех конфигурациях (доказано: файл не
        // появлялся ни в одном из трёх прогонов с -D...=target/...).
        // Поэтому: если свойство не задано — пишем в <user.dir>/target/<file>.
        // Один write-вызов на тест: при forkCount=2 два форка дописывают
        // конкурентно, O_APPEND держит построчную атомарность.
        Path report = reportPath(property, fileName);
        try {
            if (report.getParent() != null) {
                Files.createDirectories(report.getParent());
            }
            Files.writeString(report, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path reportPath(String property, String fileName) {
        String configured = System.getProperty(property);
        if (configured != null) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.dir"), "target", fileName);
    }

    /** Пустой baseline — «проверять нечего» (используется в тестах). */
    static LeakInvariantChecker.Baseline emptyBaseline() {
        return new LeakInvariantChecker.Baseline(Set.of(), Set.of());
    }
}
