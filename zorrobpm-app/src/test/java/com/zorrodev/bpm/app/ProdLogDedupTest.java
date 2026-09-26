package com.zorrodev.bpm.app;

import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * WO-OPS-20 (NEW2-04): prod-логи дублировались — каждая строка шла и pattern,
 * и JSON. Logback root — один логический элемент: appender-ref'ы глобального
 * root и root внутри {@code <springProfile name="prod">} СУММИРУЮТСЯ.
 *
 * <p>Тест — реальный прогон {@code logback-spring.xml} через Boot'овый
 * {@link LogbackLoggingSystem} (тот же Joran с {@code <springProfile>},
 * что грузит прод, не парсинг XML): перехват stdout → каждая строка обязана
 * парситься как JSON при профиле prod и оставаться pattern при остальных.
 *
 * <p>Порядок-независимость: тестовый класс делит общий LoggerContext
 * с @SpringBootTest-классами модуля (их контекст оставляет appenders и статус,
 * а Boot'овый {@code initialize()} делает early-return на уже
 * инициализированном контексте — наш XML тогда молча НЕ парсится и probe
 * уходит в чужой pattern-CONSOLE). Поэтому каждый прогон начинает с ПОЛНОГО
 * сброса общего контекста ({@code reset()} + чистка статуса) — конфигурация
 * читается из XML заново, а не переиспользуется чужая. Точечного сноса
 * отдельных ключей ({@code SAFE_JORAN_CONFIGURATION} и т.п.) недостаточно —
 * проверено падением в полном прогоне модуля при зелёных изолированных.
 *
 * <p>POF-мутация: убрать {@code <springProfile name="!prod">} вокруг
 * глобального root — prod-тест КРАСНЫЙ (смешанный поток: pattern-строки не
 * парсятся как JSON).
 */
class ProdLogDedupTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LOGBACK_XML =
        Path.of("src/main/resources/logback-spring.xml").toAbsolutePath().toUri().toString();

    private PrintStream savedOut;

    private LogbackLoggingSystem loggingSystem;

    @AfterEach
    void restoreLogging() {
        if (savedOut != null) {
            System.setOut(savedOut);
            savedOut = null;
        }
        if (loggingSystem != null) {
            loggingSystem.cleanUp();
            loggingSystem = null;
        }
        // Возвращаем глобальный контекст в нейтральное состояние: тест
        // перенастраивал общий LoggerContext, следующим тестам в том же JVM
        // молчание безопаснее чужого prod-JSON в консоли.
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.reset();
        ctx.getStatusManager().clear();
    }

    @Test
    void prodProfile_everyStdoutLineIsValidJson() {
        List<String> lines = captureLogLines("prod");
        assertThat(lines)
            .as("prod-профиль обязан хоть что-то напечатать в stdout")
            .isNotEmpty();
        for (String line : lines) {
            try {
                assertThat(MAPPER.readTree(line))
                    .as("каждая prod-строка — валидный JSON")
                    .isNotNull();
            } catch (Exception e) {
                fail("prod-строка обязана быть валидным JSON, получено: " + line);
            }
        }
    }

    @Test
    void nonProdProfiles_stdoutStaysPatternFormat() {
        for (String profile : new String[]{"test", "dev"}) {
            assertPatternLines(profile, captureLogLines(profile));
        }
    }

    @Test
    void noProfile_stdoutStaysPatternFormat() {
        assertPatternLines("(без профиля)", captureLogLines());
    }

    private void assertPatternLines(String profile, List<String> lines) {
        assertThat(lines)
            .as(profile + ": обязан хоть что-то напечатать в stdout")
            .isNotEmpty();
        for (String line : lines) {
            assertThat(line)
                .as(profile + ": pattern-формат начинается с даты, а не с '{'")
                .matches("^\\d{4}-\\d{2}-\\d{2} .*");
            assertThatThrownBy(() -> MAPPER.readTree(line))
                .as(profile + ": pattern-строка — не JSON")
                .isInstanceOf(Exception.class);
        }
    }

    /**
     * Реальный прогон конфига: Boot'овый Joran с активными профилями из
     * окружения (как на проде), перехват System.out (ConsoleAppender
     * биндится на текущий System.out при старте), одна строка через логгер.
     */
    private List<String> captureLogLines(String... activeProfiles) {
        // Порядок-независимость (см. javadoc класса): ПОЛНЫЙ сброс общего
        // контекста ДО парсинга. Boot'овый initialize() делает early-return,
        // если контекст выглядит уже инициализированным (чужой полный
        // Spring-контекст из соседнего тестового класса оставляет appenders
        // и статус), — тогда наш XML молча НЕ парсится и probe уходит в чужой
        // pattern-CONSOLE. Точечный снос отдельных ключей недостаточен.
        LoggerContext shared = (LoggerContext) LoggerFactory.getILoggerFactory();
        shared.reset();
        shared.getStatusManager().clear();
        MockEnvironment env = new MockEnvironment();
        if (activeProfiles.length > 0) {
            env.setActiveProfiles(activeProfiles);
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        savedOut = System.out;
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            loggingSystem = new LogbackLoggingSystem(getClass().getClassLoader());
            loggingSystem.beforeInitialize();
            loggingSystem.initialize(new LoggingInitializationContext(env), LOGBACK_XML, null);

            LoggerFactory.getLogger("WoOps20Probe").info("wo-ops-20-probe-line");
        } finally {
            System.out.flush();
            System.setOut(savedOut);
            savedOut = null;
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        List<String> lines = Arrays.stream(out.split("\\R"))
            .filter(l -> !l.isBlank())
            .toList();
        // Отлов stdout обязан видеть именно нашу строку — иначе тест
        // проверяет пустоту, а не формат (ложная зелень).
        assertThat(lines)
            .as("перехват stdout обязан содержать probe-строку")
            .anyMatch(l -> l.contains("wo-ops-20-probe-line"));
        return lines;
    }
}
