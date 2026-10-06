package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.repository.ElementListenerPhaseRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.CompletionDedupCleanupJob;
import com.zorrodev.bpm.engine.service.CompletionDedupStore;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-C8-36 (раунд 4, находка F-7 red-team) — ручки, введённые этим WO, обязаны
 * ДОЕЗЖАТЬ до бина в задеплоенном приложении.
 *
 * <p><b>Дефект, который закрывается.</b> В fat-jar {@code BOOT-INF/classes/}
 * (свойства {@code zorrobpm-app}) идёт первым в classpath и ПЕРЕКРЫВАЕТ
 * {@code zorrobpm-engine/src/main/resources/application.properties}. То есть
 * engine-файл в задеплоенном приложении не читается вообще, и для ключа,
 * объявленного только в нём, единственный рабочий путь — relaxed binding
 * переменной окружения прямо на ключ свойства. Но в обоих compose было
 * {@code ZORROBPM_COMPLETION_DEDUP_TTL_SECONDS}, а это НЕ relaxed-синоним
 * {@code zorrobpm.engine.completion-dedup.ttl-seconds} (потерян префикс
 * {@code ENGINE_}). Оператор ставил «правдоподобное» значение в {@code .env},
 * контейнер стартовал с дефолтом 3600, зажим F-3 был недостижим и WARN не
 * печатался — то есть предохранитель из F-3 в проде не существовал.
 *
 * <p><b>Почему тест берёт имя переменной ИЗ САМОГО compose, а не из константы.</b>
 * Утверждение, которое здесь проверяется, — «значение из env-имени, объявленного
 * в compose, доезжает до бина». Если подставить имя руками, тест останется
 * зелёным и при сломанном compose (relaxed binding работает для ЛЮБОГО
 * корректного имени) — то есть он не поймал бы ровно тот дефект, который
 * поймал red-team. Источник имени здесь тот же, откуда его берёт оператор.
 *
 * <p><b>Почему окружение эмулируется через {@link SystemEnvironmentPropertySource}.</b>
 * V11: тест обязан воспроизводить боевое окружение. Имя переменной в контейнере
 * разрешается в ключ свойства тем же кодом Spring, что и в проде, —
 * {@code checkPropertyName} внутри {@code SystemEnvironmentPropertySource}.
 * Подмена делается на уровне «словаря окружения» целиком (включая
 * {@code containsProperty}, который у класса смотрит в настоящий
 * {@code System.getenv()}, недоступный для подмены из процесса).
 *
 * <p>Мутации, которые обязаны ронять этот тест: вернуть в compose старое имя
 * {@code ZORROBPM_COMPLETION_DEDUP_TTL_SECONDS}; переименовать ключ в
 * {@code @Value}/{@code application.properties}, не тронув compose; убрать
 * аннотацию {@code @Value} с поля.
 */
class C836ComposeEnvBindingTest {

    /** Ключи свойств, введённые этим WO (как в {@code @Value} и в application.properties). */
    private static final String TTL_KEY = "zorrobpm.engine.completion-dedup.ttl-seconds";
    private static final String STAMP_KEY = "zorrobpm.engine.dispatch-phase-stamping";

    /** Соответствие «свойство → имя переменной», как его читает оператор из compose. */
    private static final Map<String, String> KEY_TO_ENV = Map.of(
        TTL_KEY, "ZORROBPM_ENGINE_COMPLETION_DEDUP_TTL_SECONDS",
        STAMP_KEY, "ZORROBPM_ENGINE_DISPATCH_PHASE_STAMPING");

    /**
     * Сентинель для TTL: не может быть ни дефолтом {@code @Value} (3600), ни
     * значением зажима F-3 (1800 / 2592000) — иначе ассерт прошёл бы и при
     * неработающей связке.
     */
    private static final String TTL_SENTINEL = "4321";

    private static final Pattern ENV_LINE =
        Pattern.compile("(?m)^\\s+(ZORROBPM_[A-Z0-9_]+):\\s*\\$\\{\\1:-?([^}]*)}\\s*$");

    private static final String PROD_COMPOSE = "docker-compose.yml";
    private static final String SOAK_COMPOSE = "docker-compose.multi.yml";

    // ---------------------------------------------------------------- TTL: prod-compose

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PROD_COMPOSE, SOAK_COMPOSE})
    void ttlEnvVarDeclaredInCompose_reachesCleanupJobAsRealCutoff(String composeFile) {
        Map<String, String> env = envOfComposeWithSentinel(composeFile, TTL_KEY, TTL_SENTINEL);

        runnerWithContainerEnv(env).run(ctx -> {
            assertThat(ctx).as("контекст с ручками C8-36 должен подниматься").hasNotFailed();

            ctx.getBean(CompletionDedupCleanupJob.class).cleanExpired();

            Instant cutoff = cutoffReachingStore(ctx.getBean(CompletionDedupStore.class));
            long appliedTtl = Duration.between(cutoff, Instant.now()).getSeconds();
            assertThat(appliedTtl)
                .as("что оператор поставил в .env под именем, объявленным в %s, обязано быть "
                    + "TTL, по которому чистка реально удаляет маркеры (связка "
                    + "compose → env → %s)", composeFile, TTL_KEY)
                .isBetween(4_291L, 4_351L);
        });
    }

    // ------------------------------------------------------- штамп фазы: verifier №5

    /**
     * Связка property→поле для F-2 (флаг штампа), проверенная НАСТОЯЩИМ бинoм в
     * Spring-контексте. Собственная претензия F-2 была ровно в том, что прежняя
     * проверка крутила сеттер и утверждала «на настоящем бине», ничего не
     * поднимая; здесь значение приходит из окружения контейнера и попадает в
     * поле через {@code @Value} — единственный путь, которым флаг бывает
     * включён в проде.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PROD_COMPOSE, SOAK_COMPOSE})
    void stampFlagEnvVarDeclaredInCompose_reachesEnqueueServiceBean(String composeFile) {
        Map<String, String> env = envOfComposeWithSentinel(composeFile, STAMP_KEY, "true");

        runnerWithContainerEnv(env).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ServiceTaskEnqueueServiceImpl.class).isDispatchPhaseStampingEnabled())
                .as("%s=%s (объявлено в %s) обязан дойти до бина через @Value — иначе CR-01 "
                    + "выключен в контейнере, а тесты этого не видят (собственная претензия F-2)",
                    KEY_TO_ENV.get(STAMP_KEY), env.get(relaxedFormOf(STAMP_KEY)), composeFile)
                .isTrue();
        });
    }

    // ------------------------------------------ структурная проверка обоих compose

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PROD_COMPOSE, SOAK_COMPOSE})
    void everyC836EngineKeyInCompose_isDeclaredUnderItsRelaxedBindingEnvName(String composeFile) {
        Map<String, String> declared = envVarsDeclaredIn(composeFile);

        for (Map.Entry<String, String> key : KEY_TO_ENV.entrySet()) {
            String propertyKey = key.getKey();
            String expectedEnv = key.getValue();
            String relaxedForm = relaxedFormOf(propertyKey);

            assertThat(declared)
                .as("в %s должен быть объявлен env для ключа %s", composeFile, propertyKey)
                .containsKey(relaxedForm);
            assertThat(declared.keySet())
                .as("имя переменной обязано быть relaxed-binding формой КЛЮЧА свойства "
                    + "(«%s»): префикс «ENGINE_» — часть ключа, без него переменная "
                    + "не разрешается ни в контейнере, ни в properties-файле движка",
                    relaxedForm)
                .doesNotContain("ZORROBPM_COMPLETION_DEDUP_TTL_SECONDS");
        }
    }

    // ------------------------------------------------------------------ инфраструктура

    /**
     * Словарь окружения контейнера, каким его видит оператор: {@link #envOfComposeWithSentinel}
     * читает объявленное в compose имя и подставляет в него ЗНАЧЕНИЕ оператора.
     */
    private static Map<String, String> envOfComposeWithSentinel(
            String composeFile, String propertyKey, String value) {
        Map<String, String> declared = envVarsDeclaredIn(composeFile);
        String envName = relaxedFormOf(propertyKey);
        assertThat(declared)
            .as("%s обязан объявлять переменную %s для ключа %s — иначе оператор "
                + "не может настроить ручку вовсе, и её дефолт молча остаётся в силе "
                + "(WO-C8-36, раунд 4, F-7)", composeFile, envName, propertyKey)
            .containsKey(envName);
        Map<String, String> env = new LinkedHashMap<>(declared);
        env.put(envName, value);
        return env;
    }

    /** Все `ZORROBPM_*: ${…}` из compose-файла: имя переменной → значение по умолчанию. */
    private static Map<String, String> envVarsDeclaredIn(String composeFile) {
        String yaml = read(repoRoot().resolve(composeFile));
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = ENV_LINE.matcher(yaml);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    /** «a.b-c.d» → «A_B_C_D»: ровно то преобразование, которым Spring ищет env-ключ. */
    private static String relaxedFormOf(String propertyKey) {
        return propertyKey.toUpperCase().replace('.', '_').replace('-', '_');
    }

    /**
     * Тестовая замена «системного» окружения — того самого класса, которым
     * контейнер доезжает до ключа свойства: он и делает «имя переменной → ключ»
     * через {@code resolvePropertyName} (проверено на spring-core 7.0.9
     * {@code javap}: {@code getProperty} = {@code MapPropertySource.getProperty
     * (resolvePropertyName(name))}, без обращения к настоящему
     * {@code System.getenv()}, который из процесса не подменяется).
     */
    private static ApplicationContextRunner runnerWithContainerEnv(Map<String, String> containerEnv) {
        Map<String, Object> asObjectMap = new LinkedHashMap<>(containerEnv);
        return new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("containerEnv", asObjectMap)))
            .withUserConfiguration(C836Beans.class);
    }

    /** Отсечка, дошедшая до стора — единственное наблюдаемое поведение TTL. */
    private static Instant cutoffReachingStore(CompletionDedupStore store) {
        ArgumentCaptor<Timestamp> captor = ArgumentCaptor.forClass(Timestamp.class);
        verify(store).deleteExpiredBefore(captor.capture());
        return captor.getValue().toInstant();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve(PROD_COMPOSE)) && Files.exists(dir.resolve(SOAK_COMPOSE))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
            "корень репозитория не найден (не поднялись вверх до каталога с "
                + PROD_COMPOSE + " и " + SOAK_COMPOSE + ")");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("не прочитан " + path, e);
        }
    }

    /**
     * Бины C8-36 в контексте; коллабораторы — моки (проверяется связка, не бизнес-логика).
     *
     * <p><b>Здесь НЕТ {@code @Configuration}, и это не небрежность.</b> В модуле
     * лежит {@code TestMain} с {@code @SpringBootApplication}, а он сканирует
     * компоненты по {@code com.zorrodev.bpm.engine.**} — то есть ВКЛЮЧАЯ
     * test-classes. Аннотированный стереотипом класс из тестовых исходников
     * поэтому становится бином в каждом {@code @SpringBootTest(classes = TestMain.class)}
     * модуля. Проверено на себе: с {@code @Configuration} подключённый здесь МОК
     * {@code CompletionDedupStore} подменял боевой во всех чужих контекстах, и
     * {@code claim()} мока (дефолт Mockito для boolean — false) делал КАЖДЫЙ дедуп
     * «дубликатом»: два теста {@code ListenerDuplicateCompletionTests} падали с
     * «expected: 2 but was: 3» (бюджет не декрементился). Нашёл это полный
     * {@code clean verify}, а не целевой прогон.
     *
     * <p>Без стереотипа класс остаётся «лайтовым» кандидатом конфигурации:
     * {@code @Bean}-методы работают (их регистрирует {@code withUserConfiguration}),
     * но component scan его не видит.
     */
    static class C836Beans {

        @Bean
        CompletionDedupStore completionDedupStore() {
            CompletionDedupStore store = mock(CompletionDedupStore.class);
            when(store.deleteExpiredBefore(any(Timestamp.class))).thenReturn(0);
            return store;
        }

        @Bean
        CompletionDedupCleanupJob completionDedupCleanupJob(CompletionDedupStore store) {
            return new CompletionDedupCleanupJob(store);
        }

        @Bean
        ServiceTaskEnqueueServiceImpl serviceTaskEnqueueServiceImpl() {
            return new ServiceTaskEnqueueServiceImpl(
                mock(DBService.class),
                mock(BpmnService.class),
                mock(OutboxRepository.class),
                new tools.jackson.databind.ObjectMapper(),
                new ElementSupport(
                    mock(DBService.class),
                    mock(ScriptService.class),
                    mock(FeelBudget.class),
                    new tools.jackson.databind.ObjectMapper(), ZoneId.of("Asia/Almaty"), false),
                mock(ElementListenerPhaseRepository.class),
                TracingSupport.noop());
        }
    }
}
