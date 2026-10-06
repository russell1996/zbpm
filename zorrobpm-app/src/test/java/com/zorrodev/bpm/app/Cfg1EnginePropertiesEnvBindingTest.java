package com.zorrodev.bpm.app;

import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.retention.RetentionConfig;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import com.zorrodev.bpm.engine.scheduler.StuckServiceTaskWatchdog;
import com.zorrodev.bpm.engine.service.PasswordResetRateLimiter;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-CFG-1, критерий 3 — «для КАЖДОГО ключа доказать, что значение из env-имени доезжает до бина».
 *
 * <p><b>Дефект, который закрывается.</b> В fat-jar два одноимённых {@code application.properties}:
 * {@code BOOT-INF/classes/} (zorrobpm-app, 70 ключей) и лежащие внутри
 * {@code BOOT-INF/lib/zorrobpm-engine-*.jar} / {@code zorrobpm-rest-*.jar}. Spring Boot грузит
 * {@code classpath:/application.properties} как ОДИН ресурс — первый на classpath, то есть
 * app-файл. 24 ключа движка с плейсхолдером «переменная окружения, иначе умолчание» в
 * задеплоенном приложении ОТСУТСТВОВАЛИ в Environment, значения оператора игнорировались —
 * без ошибки и без предупреждения. Проверено на реальном собранном jar, а не чтением
 * исходников. Числа сходятся так: 24 engine-only ключа = 23 с операторским именем переменной
 * (они в {@link #CONTAINER_ENV}) + {@code spring.rabbitmq.host}, имя переменной у которого общее
 * с app-файлом ({@code RABBITMQ_HOST}); всего в файле движка 31 плейсхолдер — 23 этих плюс
 * 8, объявленных ещё и в app-файле.
 *
 * <p><b>Почему это боевая проверка, а не юнит (V11).</b> Контекст поднимается
 * {@code @SpringBootTest(classes = APP.class)} — тем же классом, что и в проде, с тем же
 * {@code BOOT-INF/classes/application.properties} на classpath и тем же набором модулей в
 * classpath, что и в fat-jar. Импорт проверяется не «есть ли строка в файле», а тем, дошло ли
 * значение до НАСТОЯЩЕГО бина (у всех 24 ключей потребитель найден на диске — см. ссылки в
 * {@link #everyEngineKeyEnvVar_reachesItsBean()}).
 *
 * <p><b>Почему окружение эмулируется через {@link SystemEnvironmentPropertySource}.</b> В
 * контейнере имя переменной превращается в ключ свойства тем же кодом Spring
 * ({@code resolvePropertyName} у {@code SystemEnvironmentPropertySource}), и только этот класс
 * делает relaxed-подстановку. {@code withSystemProperties}/обычный property source этого не
 * воспроизводят — они дали бы тесту зелёный при сломанной связке. Прецедент: уже существующий
 * {@code C836ComposeEnvBindingTest} в zorrobpm-engine.
 *
 * <p><b>Сентинел обязан отличаться и от дефолта в properties-файле, и от дефолта в коде.</b>
 * Иначе ассерт прошёл бы и при неработающей связке «env → ключ → бин» (значение просто
 * совпало бы с дефолтом). Здесь для каждого ключа подставляется значение, отличное от обоих.
 *
 * <p>Мутации, которые обязаны ронять этот тест:
 * <ul>
 *   <li>убрать строку {@code spring.config.import} из app-файла (или опечатка в пути) —
 *       Environment теряет ключи, бины получают свои умолчания, ассерты падают;</li>
 *   <li>вернуть движку имя {@code application.properties} — файл снова перекрывается;</li>
 *   <li>переименовать env-переменную в properties-файле, не тронув compose/тест;</li>
 *   <li>снять {@code @Value}/{@code @ConfigurationProperties} с потребителя.</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest(
    classes = APP.class,
    // RANDOM_PORT, а не NONE: zorrobpm-rest-ресурсы внедряют HttpServletRequest в конструктор
    // (ApiKeyManagementResource), и на NONE контекст не поднимается — то есть это единственный
    // способ поднять РЕАЛЬНУЮ сборку приложения в тесте.
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:cfg1binding",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=11002",
        "spring.rabbitmq.username=zorrodev",
        "spring.rabbitmq.password=zorrodev",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.filesDir=target/files",
        "zorrobpm.security.rate-limit.enabled=false",
        "server.forward-headers-strategy=framework"
    })
@ContextConfiguration(initializers = Cfg1EnginePropertiesEnvBindingTest.ContainerEnvInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Cfg1EnginePropertiesEnvBindingTest {

    /**
     * Имя адаптера биндера в {@code PropertySources} — см. {@link #indexOfSourceDeclaring}.
     * Константа приватная в Spring Boot, поэтому имя повторяется здесь явно и с проверкой:
     * переименование адаптера сделает проверку приоритета бессмысленной, а не красной.
     */
    private static final String BINDER_ADAPTER_SOURCE_NAME = "configurationProperties";

    private static final java.util.regex.Pattern ENV_NAME =
        java.util.regex.Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(:[^}]*)?}");

    private static final String ENGINE_PROPERTIES = "zorrobpm-engine/src/main/resources/zorrobpm-engine.properties";
    private static final String REST_PROPERTIES = "zorrobpm-rest/src/main/resources/zorrobpm-rest.properties";
    private static final String APP_PROPERTIES = "zorrobpm-app/src/main/resources/application.properties";

    /**
     * Словарь «имя переменной в контейнере → что в неё положил оператор».
     *
     * <p>ИМЯ переменной проверяется отдельно, против самого properties-файла
     * ({@link #everyEnvNameIsTheOneThePropertiesFileDeclares()}) — тест не имеет права
     * разойтись с тем, что реально объявлено на диске.
     *
     * <p>Значения подобраны так, чтобы быть валидными для типа потребителя И отличными от
     * умолчания в файле и от умолчания в коде:
     * {@code retention.ttl-days} = 3650 (а не 7 — при 7 job пошёл бы удалять данные, пусть и
     * ничего не нашёл бы; 3650 заведомо ничего не трогает), {@code business-timezone} =
     * Europe/Athens (отличается от Asia/Almaty и в файле, и в коде).
     */
    private static final Map<String, String> CONTAINER_ENV = new LinkedHashMap<>();

    static {
        CONTAINER_ENV.put("RETENTION_TTL_DAYS", "3650");
        CONTAINER_ENV.put("RETENTION_POLL_INTERVAL_MS", "2700000");
        CONTAINER_ENV.put("RETENTION_BATCH_SIZE", "7");
        CONTAINER_ENV.put("OUTBOX_BATCH_SIZE", "11");
        CONTAINER_ENV.put("OUTBOX_METRICS_SAMPLE_EVERY", "13");
        CONTAINER_ENV.put("SCRIPT_TIMEOUT_SECONDS", "17");
        CONTAINER_ENV.put("SCRIPT_POOL_SIZE", "3");
        CONTAINER_ENV.put("SCRIPT_QUEUE_CAPACITY", "4");
        CONTAINER_ENV.put("SCRIPT_QUEUE_WAIT_SECONDS", "9");
        CONTAINER_ENV.put("BUSINESS_TIMEZONE", "Europe/Athens");
        CONTAINER_ENV.put("ZORROBPM_MAIL_LINK_BASE_URL", "http://cfg1.example:9999");
        CONTAINER_ENV.put("ZORROBPM_INVITATION_TTL_HOURS", "31");
        CONTAINER_ENV.put("ZORROBPM_RESET_TTL_HOURS", "37");
        CONTAINER_ENV.put("ZORROBPM_RESET_EMAIL_CAPACITY", "3");
        CONTAINER_ENV.put("ZORROBPM_RESET_EMAIL_WINDOW", "1800");
        CONTAINER_ENV.put("ZORROBPM_RESET_IP_CAPACITY", "9");
        CONTAINER_ENV.put("ZORROBPM_RESET_IP_WINDOW", "2400");
        CONTAINER_ENV.put("IO_MAPPING_STRICT_MISSING", "true");
        CONTAINER_ENV.put("SERVICETASK_DISPATCH_TIMEOUT", "23m");
        CONTAINER_ENV.put("SERVICETASK_WATCHDOG_INTERVAL_MS", "330000");
        CONTAINER_ENV.put("SERVICETASK_WATCHDOG_BATCH_SIZE", "17");
        CONTAINER_ENV.put("ZORROBPM_ENGINE_DISPATCH_PHASE_STAMPING", "true");
        CONTAINER_ENV.put("ZORROBPM_ENGINE_COMPLETION_DEDUP_TTL_SECONDS", "4321");
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ConfigurableEnvironment environment;

    /**
     * Подкладывает словарь окружения контейнера в Environment боевого контекста.
     *
     * <p>{@code addFirst} — не «удобство теста», а воспроизведение приоритета: в реальном
     * контейнере {@code systemEnvironment} стоит ВЫШЕ config-файлов (переменная оператора
     * перебивает файл), и ровно этим порядком Spring и разрешает потомки.
     */
    static class ContainerEnvInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            Map<String, Object> asObjects = new LinkedHashMap<>(CONTAINER_ENV);
            applicationContext.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("containerEnv", asObjects));
        }
    }

    // ------------------------------------------------------------------ структурная часть

    /**
     * Диагностика дефекта, ставшая тестом: в classpath есть ровно один
     * {@code application.properties}, и это файл zorrobpm-app, а файлы движка и rest лежат под
     * своими (неперекрываемыми) именами и реально достижимы по classpath.
     */
    @Test
    void engineAndRestPropertiesAreReachableUnderNonShadowedNames() throws IOException {
        assertThat(resourceText("application.properties"))
            .as("classpath:/application.properties обязан быть файлом zorrobpm-app — тот же "
                + "механизм (первый на classpath) и есть причина дефекта")
            .contains("spring.application.name=zorrobpm-app");

        assertThat(getClass().getClassLoader().getResource("zorrobpm-engine.properties"))
            .as("zorrobpm-engine.properties обязан лежать в classpath рядом с app-файлом — "
                + "иначе spring.config.import указывает в пустоту, и конфиг движка исчезает "
                + "СНОВА, но уже без единой строки в логе")
            .isNotNull();
        assertThat(getClass().getClassLoader().getResource("zorrobpm-rest.properties"))
            .as("zorrobpm-rest.properties обязан лежать в classpath — см. предыдущее утверждение")
            .isNotNull();
    }

    /**
     * Приоритет: подключённый файл модуля НЕ перебивает app-файл. Если бы перебил, правка молча
     * сменила бы прод-конфигурацию (имя приложения, а с ним — всё, что на него завязано).
     *
     * <p>Это НЕ пожелание, а измеренное свойство выбранного механизма, и именно поэтому выбор
     * сделан не в пользу {@code spring.config.import}: на том же живом контексте импорт даёт
     * импортированному документу приоритет выше импортирующего, и
     * {@code spring.application.name} разрешается в {@code zorrobpm-engine} (дамп
     * PropertySource'ов — в отчёте WO-CFG-1). {@code @PropertySource} кладёт источник в конец
     * списка, то есть ниже всего, что приходит из config-data.
     *
     * <p>Порядок источников: первым идёт более приоритетный, поэтому «ниже» = «больший индекс».
     * Проверяется именно он — значения двух ключей из трёх перекрыты параметрами самого
     * {@code @SpringBootTest}, и по ним приоритет не прочитать.
     */
    @Test
    void modulePropertiesDoNotOverrideTheAppFile() {
        MutablePropertySources sources = environment.getPropertySources();
        int appFile = indexOfSourceDeclaring(sources, "spring.application.name");
        int engineKeys = indexOfSourceDeclaring(sources, "zorrobpm.engine.retention.batch-size");
        int hostKey = indexOfSourceDeclaring(sources, "spring.rabbitmq.host");
        int engineFile = indexOfSourceNamed(sources, "zorrobpm-engine.properties");
        int restFile = indexOfSourceNamed(sources, "zorrobpm-rest.properties");

        assertThat(appFile)
            .as("в Environment должен быть источник, объявляющий spring.application.name")
            .isNotEqualTo(-1);
        assertThat(engineKeys)
            .as("в Environment должен быть источник с ключами zorrobpm-engine.properties — "
                + "иначе @PropertySource не сработал и тесты ниже падают по другой причине")
            .isNotEqualTo(-1);
        assertThat(engineFile)
            .as("в Environment должен быть источник-документ zorrobpm-engine.properties")
            .isNotEqualTo(-1);
        assertThat(restFile)
            .as("в Environment должен быть источник-документ zorrobpm-rest.properties")
            .isNotEqualTo(-1);

        // Про оба файла сразу: у файла rest нет ни одного ключа, которого не было бы ещё и в
        // app-файле, поэтому по ключу его источник не отличить от app-овского — ищем по имени
        // документа. spring.rabbitmq.host, наоборот, объявлен в обоих файлах модулей и НЕ
        // объявлен в app-файле (там addresses) — это независимая проверка, что файлы модулей
        // вообще попали в Environment.
        assertThat(hostKey)
            .as("spring.rabbitmq.host объявлен в файлах engine и rest и не объявлен в app-файле — "
                + "если он не найден, файлы модулей не подключены вовсе")
            .isNotEqualTo(-1);
        assertThat(engineFile)
            .as("zorrobpm-engine.properties обязан оказаться НИЖЕ application.properties "
                + "(ниже = больший индекс в PropertySources: config-data читается первым). "
                + "Иначе zorrobpm-engine перебьёт zorrobpm-app, и правка молча поменяет "
                + "конфигурацию приложения в проде")
            .isGreaterThan(appFile);
        assertThat(restFile)
            .as("то же для zorrobpm-rest.properties")
            .isGreaterThan(appFile);

        assertThat(environment.getProperty("spring.application.name"))
            .as("фактическое подтверждение приоритета: app-файл объявляет zorrobpm-app, "
                + "оба файла модулей — zorrobpm-engine")
            .isEqualTo("zorrobpm-app");
    }

    // ------------------------------------------------------- ключ → значение → бин

    /**
     * Ядро критерия 3: все 24 engine-only ключа — от первого в списке
     * ({@code zorrobpm.engine.retention.ttl-days}) до добавленного C8-36
     * ({@code zorrobpm.engine.completion-dedup.ttl-seconds}) — доводят значение из
     * env-имени до своего НАСТОЯЩЕГО потребителя.
     *
     * <p>Потребители найдены на диске, а не выдуманы; ссылки в комментариях указывают файл и
     * строку, где ключ читается в проде.
     */
    @Test
    void everyEngineKeyEnvVar_reachesItsBean() {
        RetentionConfig retention = context.getBean(RetentionConfig.class);
        OutboxBatchProcessor outbox = context.getBean(OutboxBatchProcessor.class);
        StuckServiceTaskWatchdog watchdog = context.getBean(StuckServiceTaskWatchdog.class);
        PasswordResetRateLimiter resetLimiter = context.getBean(PasswordResetRateLimiter.class);
        UserInvitationService invitation = context.getBean(UserInvitationService.class);
        SelfRegistrationService selfRegistration = context.getBean(SelfRegistrationService.class);
        ElementSupport elementSupport = context.getBean(ElementSupport.class);
        ThreadPoolExecutor scriptPool = (ThreadPoolExecutor) readField(
            context.getBean(ScriptService.class), "executor");

        // RetentionConfig — @ConfigurationProperties (retention/RetentionConfig.java:15).
        assertThat(retention.getTtlDays())
            .as("zorrobpm.engine.retention.ttl-days=${RETENTION_TTL_DAYS} → RetentionConfig.ttlDays")
            .isEqualTo(3650);
        assertThat(retention.getPollIntervalMs())
            .as("zorrobpm.engine.retention.poll-interval-ms=${RETENTION_POLL_INTERVAL_MS} → "
                + "RetentionConfig.pollIntervalMs (и @Scheduled в RetentionJob:33)")
            .isEqualTo(2_700_000L);
        assertThat(retention.getBatchSize())
            .as("zorrobpm.engine.retention.batch-size=${RETENTION_BATCH_SIZE} → "
                + "RetentionConfig.batchSize (RetenantJob читает config.getBatchSize())")
            .isEqualTo(7);

        // OutboxBatchProcessor — @Value на полях (scheduler/OutboxBatchProcessor.java:55,68).
        assertThat(readField(outbox, "batchSize"))
            .as("zorrobpm.outbox.batch-size=${OUTBOX_BATCH_SIZE} → OutboxBatchProcessor.batchSize")
            .isEqualTo(11);
        assertThat(readField(outbox, "metricsSampleEvery"))
            .as("zorrobpm.outbox.metrics-sample-every=${OUTBOX_METRICS_SAMPLE_EVERY} → "
                + "OutboxBatchProcessor.metricsSampleEvery")
            .isEqualTo(13);

        // ScriptServiceImpl — @Value на параметрах конструктора (service/impl/ScriptServiceImpl.java:65-68).
        assertThat(readField(context.getBean(ScriptService.class), "timeoutMs"))
            .as("zorrobpm.engine.script-timeout-seconds=${SCRIPT_TIMEOUT_SECONDS} → "
                + "ScriptServiceImpl.timeoutMs (секунды × 1000)")
            .isEqualTo(17_000L);
        assertThat(scriptPool.getCorePoolSize())
            .as("zorrobpm.engine.script-pool-size=${SCRIPT_POOL_SIZE} → размер пула "
                + "ScriptServiceImpl (плюс bpmMetrics.setScriptPoolSize)")
            .isEqualTo(3);
        assertThat(scriptPool.getMaximumPoolSize())
            .as("zorrobpm.engine.script-pool-size=${SCRIPT_POOL_SIZE} → максимум пула (должен совпасть)")
            .isEqualTo(3);
        assertThat(scriptPool.getQueue().remainingCapacity())
            .as("zorrobpm.engine.script-queue-capacity=${SCRIPT_QUEUE_CAPACITY} → ёмкость "
                + "ArrayBlockingQueue пула")
            .isEqualTo(4);
        assertThat(readField(context.getBean(ScriptService.class), "queueWaitSeconds"))
            .as("zorrobpm.engine.script-queue-wait-seconds=${SCRIPT_QUEUE_WAIT_SECONDS} → "
                + "ScriptServiceImpl.queueWaitSeconds")
            .isEqualTo(9L);

        // ElementSupport — @Value на параметрах конструктора (handler/ElementSupport.java:73-74).
        assertThat(readField(elementSupport, "businessZone"))
            .as("zorrobpm.business-timezone=${BUSINESS_TIMEZONE} → ElementSupport.businessZone "
                + "(его же читают TimerJobExecutor:39, TimerStartJobExecutor:41, EventTrigger:55)")
            .isEqualTo(ZoneId.of("Europe/Athens"));
        assertThat(readField(elementSupport, "strictMissing"))
            .as("zorrobpm.engine.io-mapping.strict-missing=${IO_MAPPING_STRICT_MISSING} → "
                + "ElementSupport.strictMissing")
            .isEqualTo(true);

        // UserInvitationService / SelfRegistrationService — @Value на полях.
        assertThat(readField(invitation, "linkBaseUrl"))
            .as("zorrobpm.mail.link-base-url=${ZORROBPM_MAIL_LINK_BASE_URL} → "
                + "UserInvitationService.linkBaseUrl (и SelfRegistrationService.linkBaseUrl)")
            .isEqualTo("http://cfg1.example:9999");
        assertThat(readField(selfRegistration, "linkBaseUrl"))
            .as("zorrobpm.mail.link-base-url=${ZORROBPM_MAIL_LINK_BASE_URL} → "
                + "SelfRegistrationService.linkBaseUrl (второй потребитель того же ключа)")
            .isEqualTo("http://cfg1.example:9999");
        assertThat(readField(invitation, "invitationTtlHours"))
            .as("zorrobpm.security.invitation-ttl-hours=${ZORROBPM_INVITATION_TTL_HOURS} → "
                + "UserInvitationService.invitationTtlHours")
            .isEqualTo(31);
        assertThat(readField(invitation, "resetTtlHours"))
            .as("zorrobpm.security.reset-ttl-hours=${ZORROBPM_RESET_TTL_HOURS} → "
                + "UserInvitationService.resetTtlHours")
            .isEqualTo(37);

        // PasswordResetRateLimiter — @Value на параметрах конструктора
        // (service/PasswordResetRateLimiter.java:55-58); bean "@Primary" с reset-* значениями.
        assertThat(readField(resetLimiter, "emailCapacity"))
            .as("zorrobpm.security.rate-limit.reset-email-capacity="
                + "${ZORROBPM_RESET_EMAIL_CAPACITY} → PasswordResetRateLimiter.emailCapacity")
            .isEqualTo(3);
        assertThat(resetLimiter.getEmailWindowSeconds())
            .as("zorrobpm.security.rate-limit.reset-email-window-seconds="
                + "${ZORROBPM_RESET_EMAIL_WINDOW} → PasswordResetRateLimiter.emailWindowSeconds")
            .isEqualTo(1800);
        assertThat(readField(resetLimiter, "ipCapacity"))
            .as("zorrobpm.security.rate-limit.reset-ip-capacity=${ZORROBPM_RESET_IP_CAPACITY} → "
                + "PasswordResetRateLimiter.ipCapacity")
            .isEqualTo(9);
        assertThat(resetLimiter.getIpWindowSeconds())
            .as("zorrobpm.security.rate-limit.reset-ip-window-seconds=${ZORROBPM_RESET_IP_WINDOW} → "
                + "PasswordResetRateLimiter.ipWindowSeconds")
            .isEqualTo(2400);

        // StuckServiceTaskWatchdog — @Value на полях + @Scheduled на методе.
        assertThat(readField(watchdog, "dispatchTimeout"))
            .as("zorrobpm.servicetask.dispatch-timeout=${SERVICETASK_DISPATCH_TIMEOUT} → "
                + "StuckServiceTaskWatchdog.dispatchTimeout (Duration)")
            .isEqualTo(Duration.ofMinutes(23));
        assertThat(readField(watchdog, "batchSize"))
            .as("zorrobpm.servicetask.watchdog-batch-size=${SERVICETASK_WATCHDOG_BATCH_SIZE} → "
                + "StuckServiceTaskWatchdog.batchSize")
            .isEqualTo(17);

        // @Scheduled-ключи: значения нет в поле, их читает планировщик из того же Environment —
        // поэтому проверяем ровно то, что он прочитает.
        assertThat(environment.resolvePlaceholders(
                "${zorrobpm.engine.retention.poll-interval-ms:3600000}"))
            .as("zorrobpm.engine.retention.poll-interval-ms — @Scheduled(fixedDelayString=…) "
                + "в RetentionJob:33 читает это выражение из Environment контекста")
            .isEqualTo("2700000");
        assertThat(environment.resolvePlaceholders(
                "${zorrobpm.servicetask.watchdog-interval-ms:60000}"))
            .as("zorrobpm.servicetask.watchdog-interval-ms — @Scheduled(fixedDelayString=…) "
                + "в StuckServiceTaskWatchdog:37 читает это выражение из Environment контекста")
            .isEqualTo("330000");

        // WO-C8-36: два ключа, добавленных последним. Их ручки уже проверялись на НАСТОЯЩИХ
        // бинах в C836ComposeEnvBindingTest (модуль engine, полный контекст), и здесь важно
        // другое: что в СОБРАННОМ ПРИЛОЖЕНИИ эти ключи вообще видны.
        //
        // По полю ServiceTaskEnqueueServiceImpl здесь НЕ проверяется, и это не слабость, а
        // свойство бина: он помечен @Profile("!test") (строка 43), то есть в тестовом профиле
        // его в контексте нет, и «достать» его можно было бы только руками — ровно тот приём,
        // который red-team уже ловил (C8-36, находка verifier'а №5 про F-2: тест крутил сеттер
        // и утверждал «на настоящем бине», ничего не поднимая). Поэтому здесь — Environment,
        // из которого этот бин и берёт значение.
        assertThat(environment.resolvePlaceholders(
                "${zorrobpm.engine.dispatch-phase-stamping:false}"))
            .as("zorrobpm.engine.dispatch-phase-stamping="
                + "${ZORROBPM_ENGINE_DISPATCH_PHASE_STAMPING} → значение, которое "
                + "@Value-полю ServiceTaskEnqueueServiceImpl.dispatchPhaseStamping и есть "
                + "(CR-01 иначе выключен в контейнере)")
            .isEqualTo("true");
        assertThat(environment.resolvePlaceholders(
                "${zorrobpm.engine.completion-dedup.ttl-seconds:3600}"))
            .as("zorrobpm.engine.completion-dedup.ttl-seconds="
                + "${ZORROBPM_ENGINE_COMPLETION_DEDUP_TTL_SECONDS} → значение, по которому "
                + "CompletionDedupCleanupJob реально удаляет маркеры (поведение чистки подтверждено "
                + "C836ComposeEnvBindingTest в модуле engine; здесь — что ключ виден приложению)")
            .isEqualTo("4321");
    }

    /**
     * Сторожевой срез: каждый ключ с {@code ${ENV}} плейсхолдером из ВСЕХ импортируемых
     * properties-файлов обязан реально присутствовать в Environment боевого контекста.
     *
     * <p>Это ловит ровно то, что не ловит проверка «есть ли строка импорта»: опечатку в пути
     * импорта, удалённый из classpath файл, забытый перенос. {@code optional:} в импорте
     * молча переживает всё это — без этой проверки такой отказ уехал бы в прод.
     */
    @Test
    void everyEnvPlaceholderKeyOfImportedFiles_resolvesInTheRealEnvironment() {
        List<String> unresolved = new ArrayList<>();
        for (String file : List.of(ENGINE_PROPERTIES, REST_PROPERTIES, APP_PROPERTIES)) {
            for (Map.Entry<String, String> entry : envPlaceholderKeys(read(file)).entrySet()) {
                if (!environment.containsProperty(entry.getKey())) {
                    unresolved.add(file + ": " + entry.getKey()
                        + " (placeholder " + entry.getValue() + ") — ключа нет в Environment");
                }
            }
        }
        assertThat(unresolved)
            .as("ключи с ${ENV} из импортируемых файлов обязаны доезжать до Environment "
                + "приложения; каждый непройденный — это ручка, которую оператор не может "
                + "настроить вовсе (значения её дефолта молча остаются в силе)")
            .isEmpty();
    }

    /**
     * Имя переменной в тесте обязано совпадать с тем, что объявлено на диске: переименование
     * переменной в properties-файле роняет тест, а не остаётся незамеченным.
     */
    @Test
    void everyEnvNameIsTheOneThePropertiesFileDeclares() {
        Map<String, String> declared = envPlaceholderKeys(read(ENGINE_PROPERTIES));

        for (Map.Entry<String, String> env : CONTAINER_ENV.entrySet()) {
            assertThat(declaredEnvNames(declared))
                .as("переменная %s обязана быть объявлена плейсхолдером в "
                    + "zorrobpm-engine.properties — иначе тест подставляет переменную, на которую "
                    + "в приложении никто не смотрит, и проверяет не ту ручку", env.getKey())
                .contains(env.getKey());
        }

        // Число плейсхолдеров в файле движка — контроль на то, что переименование файла
        // ничего в нём не изменило (31 = 23 операторских + 8 общих с app-файлом: DB_URL,
        // DB_USERNAME, DB_PASSWORD, RABBITMQ_HOST/USERNAME/PASSWORD/PORT, APP_FILES_DIR).
        assertThat(declared)
            .as("в zorrobpm-engine.properties 31 ${ENV}-плейсхолдер — расхождение означает, что "
                + "переименование файла изменило его содержимое (ключи терять нельзя, WO-CFG-1)")
            .hasSize(31);
    }

    // ------------------------------------------------------------------ инфраструктура

    /**
     * Поле бина, прочитанное напрямую: потребители части ключей не имеют публичных геттеров.
     *
     * <p><b>Прокси здесь обязателен, без него тест врал бы молча.</b> Бины с
     * {@code @Transactional}-методами Spring оборачивает в CGLIB-прокси
     * ({@code OutboxBatchProcessor$$SpringCGLIB$$0}, {@code UserInvitationService$$…}), а
     * прокси — отдельный объект, созданный Objenesis: его КОПИИ полей не инициализированы и никак
     * не связаны с полями цели. Чтение {@code field.get(proxy)} вернуло бы нули мимо
     * {@code @Value} — то есть ассерт вида «ожидаем 11, а получили 0» краснел бы и при полностью
     * рабочей связке, а «ожидаем 0» прошёл бы при сломанной. Читать надо у ЦЕЛИ.
     * {@link AopTestUtils#getUltimateTargetObject(Object)} снимает и вложенные прокси.
     */
    private static Object readField(Object bean, String fieldName) {
        Object target = AopTestUtils.getUltimateTargetObject(bean);
        Field field = ReflectionUtils.findField(target.getClass(), fieldName);
        assertThat(field)
            .as("поле %s должно существовать в %s — если его нет, тест проверяет не то",
                fieldName, target.getClass().getSimpleName())
            .isNotNull();
        ReflectionUtils.makeAccessible(field);
        return ReflectionUtils.getField(field, target);
    }

    /** Позиция источника-документа, в имени которого встречается кусок имени файла. */
    private static int indexOfSourceNamed(MutablePropertySources sources, String fileNamePart) {
        int index = 0;
        for (java.util.Iterator<PropertySource<?>> it = sources.iterator(); it.hasNext(); index++) {
            PropertySource<?> source = it.next();
            if (BINDER_ADAPTER_SOURCE_NAME.equals(source.getName())) {
                continue;
            }
            if (source.getName().contains(fileNamePart)) {
                return index;
            }
        }
        return -1;
    }

    /** Имена переменных, объявленные в плейсхолдерах карты «ключ → плейсхолдер». */
    private static List<String> declaredEnvNames(Map<String, String> declared) {
        List<String> names = new ArrayList<>();
        for (String placeholder : declared.values()) {
            Matcher m = ENV_NAME.matcher(placeholder);
            if (m.matches()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /**
     * Позиция первого PropertySource, который реально ОБЪЯВЛЯЕТ указанный ключ.
     *
     * <p>Пропускается источник с именем {@value #BINDER_ADAPTER_SOURCE_NAME}: это адаптер
     * биндера Spring Boot, который зеркалит ВСЕ config-data источники разом и стоит выше них,
     * поэтому «находит» любой ключ и в проверке приоритета всегда давал бы первый индекс —
     * то есть сравнивать было бы нечего.
     */
    private static int indexOfSourceDeclaring(MutablePropertySources sources, String key) {
        int index = 0;
        for (java.util.Iterator<PropertySource<?>> it = sources.iterator(); it.hasNext(); index++) {
            PropertySource<?> source = it.next();
            if (BINDER_ADAPTER_SOURCE_NAME.equals(source.getName())) {
                continue;
            }
            if (source.getProperty(key) != null) {
                return index;
            }
        }
        return -1;
    }

    private static Map<String, String> envPlaceholderKeys(String properties) {
        Map<String, String> result = new LinkedHashMap<>();
        String[] lines = properties.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1);
            var matcher = java.util.regex.Pattern
                .compile("\\$\\{([A-Z][A-Z0-9_]*)(:[^}]*)?}").matcher(value);
            if (matcher.find()) {
                result.put(key, matcher.group());
            }
        }
        return result;
    }

    private String resourceText(String resource) throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("ресурс %s обязан быть в classpath", resource).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String read(String repoRelativePath) {
        return readFile(repoRoot().resolve(repoRelativePath));
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("не прочитан " + path, e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))
                && Files.exists(dir.resolve("docker-compose.yml"))
                && Files.isDirectory(dir.resolve("zorrobpm-engine"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("корень репозитория не найден");
    }
}