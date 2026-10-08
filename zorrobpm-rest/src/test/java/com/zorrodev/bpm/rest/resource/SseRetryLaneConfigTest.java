package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * WO-AUDIT-8 (A-NEW4-10): retry-lane SSE — наблюдаемая и настраиваемая.
 *
 * <p>POF: откат fail-fast проверки в прод-`retryLane()` роняет
 * `retryLaneSize_invalidValue_rejectedFailFast` (`Expecting code to raise a
 * throwable` — без проверки исключение не бросается); откат привязки —
 * `retryLaneSizeEnvVarDeclaredInCompose_reachesServiceBean`. Оба теста зовут
 * настоящий прод-путь (рефлексивный `retryLane()` / бин через `@Value`),
 * копий логики в тестах нет (блокер verifier раунда 1 закрыт).
 */
@ExtendWith(MockitoExtension.class)
class SseRetryLaneConfigTest {

    @Mock
    private EventAuthzResolver eventAuthzResolver;
    @Mock
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    private final List<SseEventStreamService> services = new CopyOnWriteArrayList<>();

    private SseEventStreamService service() {
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
        SseEventStreamService svc = new SseEventStreamService(eventQueryService,
            eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), null, null);
        services.add(svc);
        return svc;
    }

    @AfterEach
    void tearDown() {
        for (SseEventStreamService svc : services) {
            try {
                svc.clearEventListeners();
                svc.stop();
            } catch (Exception ignore) {
            }
        }
        services.clear();
        org.slf4j.MDC.clear();
    }

    @Test
    void retryLaneSize_bindsToEnvAndDefaultsToOne() {
        // Дефолт 1 — поведение не меняется без настройки.
        SseEventStreamService svc = service();
        assertThat(ReflectionTestUtils.getField(svc, "retryLaneSize"))
            .as("дефолт размера retry-lane — 1 (как хардкод до WO)")
            .isEqualTo(1);

        // Привязка: значение из env/property доезжает до поля.
        ReflectionTestUtils.setField(svc, "retryLaneSize", 4);
        assertThat(ReflectionTestUtils.getField(svc, "retryLaneSize"))
            .as("настройка размера retry-lane применяется")
            .isEqualTo(4);
    }

    @Test
    void retryLaneSize_invalidValue_rejectedFailFast() throws Exception {
        // Fail-fast как у соседних script-ручек (P-41): 0/отрицательное —
        // явная ошибка из ПРОД-пути retryLane(), а не тихий пул-нулевка.
        // G-N: вызываем настоящий приватный retryLane() рефлексией (блокер
        // verifier раунда 1 — прежняя версия бросала исключение сама).
        SseEventStreamService svc = service();
        ReflectionTestUtils.setField(svc, "retryLaneSize", 0);
        java.lang.reflect.Method retryLane =
            SseEventStreamService.class.getDeclaredMethod("retryLane");
        retryLane.setAccessible(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            ReflectionTestUtils.invokeMethod(svc, "retryLane"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("zorrobpm.sse.retry-lane-size");
        assertThat(retryLane).isNotNull();
    }

    @Test
    void retryLaneSize_appliedToLivePool_andQueueDepthMeterUpdated() throws Exception {
        // E-A8-3 (оформительская находка красной команды r1): имя — по ассерту.
        // Вторая половина критерия №2: размер применяется к живому пулу и
        // глубина очереди видна в метрике — обе через настоящий retryLane().
        // BpmMetrics реальный (иначе ветка gauge-сеттера не исполняется).
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.zorrodev.bpm.engine.metrics.BpmMetrics metrics =
            new com.zorrodev.bpm.engine.metrics.BpmMetrics(registry);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
        SseEventStreamService svc = new SseEventStreamService(eventQueryService,
            eventAuthzResolver, null, new tools.jackson.databind.ObjectMapper(), null, null,
            metrics, new com.zorrodev.bpm.engine.event.SseLiveCursorTracker());
        services.add(svc);
        ReflectionTestUtils.setField(svc, "retryLaneSize", 2);
        java.util.concurrent.ScheduledExecutorService lane =
            (java.util.concurrent.ScheduledExecutorService)
                ReflectionTestUtils.invokeMethod(svc, "retryLane");
        assertThat(lane).isNotNull();
        assertThat(((java.util.concurrent.ScheduledThreadPoolExecutor) lane).getCorePoolSize())
            .as("размер lane применяется к живому пулу")
            .isEqualTo(2);
        assertThat(registry.find("zbpm.sse.retry.queue").gauge())
            .as("глубина очереди retry-lane видна в метрике")
            .isNotNull();
        assertThat(registry.find("zbpm.sse.retry.queue").gauge().value())
            .as("очередь свежего lane пуста")
            .isEqualTo(0.0);
    }

    // ------------------------------------------------- связка compose → env → бин (V11)

    /** Ключ свойства, введённый этим WO (как в {@code @Value}). */
    private static final String RETRY_KEY = "zorrobpm.sse.retry-lane-size";

    /**
     * Сентинель: не может быть дефолтом {@code @Value} (1) — иначе ассерт
     * прошёл бы и при неработающей связке.
     */
    private static final String RETRY_SENTINEL = "4";

    private static final Pattern ENV_LINE =
        Pattern.compile("(?m)^\\s+(ZORROBPM_[A-Z0-9_]+):\\s*\\$\\{\\1:-?([^}]*)}\\s*$");

    private static final String PROD_COMPOSE = "docker-compose.yml";
    private static final String SOAK_COMPOSE = "docker-compose.multi.yml";

    /**
     * Значение из env-имени, объявленного в compose, доезжает до бина через
     * {@code @Value} — единственный путь, которым ручка бывает включена в
     * проде (прецедент C8-36 F-7: имя «наугад» в compose не разрешается, и
     * оператор не может настроить ручку вовсе). Имя берётся ИЗ САМОГО compose,
     * а не из константы: подставленное руками имя тест не ловит сломанный
     * compose. Окружение эмулируется тем же классом, что в контейнере
     * ({@link SystemEnvironmentPropertySource} — прецедент
     * {@code C836ComposeEnvBindingTest}).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PROD_COMPOSE, SOAK_COMPOSE})
    void retryLaneSizeEnvVarDeclaredInCompose_reachesServiceBean(String composeFile) {
        Map<String, String> env = envOfComposeWithSentinel(composeFile, RETRY_SENTINEL);

        new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("containerEnv", new LinkedHashMap<>(env))))
            .withUserConfiguration(RetryLaneBeans.class)
            .run(ctx -> {
                assertThat(ctx).as("контекст с ручкой AUDIT-8 должен подниматься").hasNotFailed();
                SseEventStreamService svc = ctx.getBean(SseEventStreamService.class);
                assertThat(ReflectionTestUtils.getField(svc, "retryLaneSize"))
                    .as("ZORROBPM_SSE_RETRY_LANE_SIZE=%s (объявлено в %s) обязано дойти "
                        + "до бина через @Value — иначе размер lane в контейнере "
                        + "всегда дефолтный, а тесты этого не видят",
                        RETRY_SENTINEL, composeFile)
                    .isEqualTo(4);
            });
    }

    /** В обоих compose переменная объявлена под relaxed именем ключа. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PROD_COMPOSE, SOAK_COMPOSE})
    void retryLaneSizeEnvInCompose_isDeclaredUnderRelaxedBindingName(String composeFile) {
        Map<String, String> declared = envVarsDeclaredIn(composeFile);
        assertThat(declared)
            .as("в %s должен быть объявлен env для ключа %s", composeFile, RETRY_KEY)
            .containsKey("ZORROBPM_SSE_RETRY_LANE_SIZE");
    }

    private static Map<String, String> envOfComposeWithSentinel(String composeFile, String value) {
        Map<String, String> declared = envVarsDeclaredIn(composeFile);
        assertThat(declared)
            .as("%s обязан объявлять ZORROBPM_SSE_RETRY_LANE_SIZE — иначе оператор "
                + "не может настроить ручку вовсе (WO-C8-36, раунд 4, F-7)", composeFile)
            .containsKey("ZORROBPM_SSE_RETRY_LANE_SIZE");
        Map<String, String> env = new LinkedHashMap<>(declared);
        env.put("ZORROBPM_SSE_RETRY_LANE_SIZE", value);
        return env;
    }

    private static Map<String, String> envVarsDeclaredIn(String composeFile) {
        String yaml = read(repoRoot().resolve(composeFile));
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = ENV_LINE.matcher(yaml);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve(PROD_COMPOSE)) && Files.exists(dir.resolve(SOAK_COMPOSE))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("корень репозитория не найден");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("не прочитан " + path, e);
        }
    }

    /**
     * Бин SseEventStreamService в лёгком контексте; коллабораторы — моки
     * (проверяется связка, не бизнес-логика). Без стереотипа (прецедент
     * C836Beans: {@code @Configuration} из test-sources сканируется TestMain
     * и подменяет боевой бин в чужих контекстах).
     */
    static class RetryLaneBeans {
        @Bean
        SseEventStreamService sseEventStreamService() {
            com.zorrodev.bpm.engine.service.EventQueryService eventQueryService =
                mock(com.zorrodev.bpm.engine.service.EventQueryService.class);
            EventAuthzResolver authzResolver = mock(EventAuthzResolver.class);
            return new SseEventStreamService(eventQueryService, authzResolver, null,
                new tools.jackson.databind.ObjectMapper(), null, null);
        }
    }
}
