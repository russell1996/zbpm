package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-C8-36 (раунд 4, F-7) — ручки {@code zorrobpm.worker.*} обязаны быть
 * настраиваемы переменной окружения, имя которой оператор берёт из документации.
 *
 * <p><b>Дефект, который закрывается.</b> Обе ручки жили только как
 * {@code @Value}-дефолты внутри стартера. Свойство в контейнере разрешается из
 * окружения по правилам relaxed binding, то есть имя переменной обязано быть
 * точной формой ключа ({@code zorrobpm.worker.ensure-publisher-confirms} →
 * {@code ZORROBPM_WORKER_ENSURE_PUBLISHER_CONFIRMS}); «правдоподобное»
 * укороченное имя не разрешается НИКОГДА и молча оставляет дефолт. Документация
 * (docs/guides/integration-quickstart.md) называла только ключи свойств, поэтому
 * оператор контейнерного воркера не мог настроить их никак.
 *
 * <p><b>Почему имя берётся ИЗ ДОКУМЕНТАЦИИ.</b> В отличие от ручек движка, у
 * воркерских нет compose-файла в этом репозитории (воркер разворачивается
 * отдельно, движок в наш compose не входит) — единственная поверхность, откуда
 * оператор берёт имя, это quickstart. Если тест подставит имя руками, он
 * останется зелёным и при неверно задокументированном имени: утверждение
 * проверяется не «работает ли relaxed binding» (это свойство Spring), а
 * «написано ли в документации то имя, которое работает».
 *
 * <p><b>Что доказывается по существу.</b> Настоящий {@link HandlerAutoConfiguration}
 * в Spring-контексте с окружением контейнера; его {@code @PostConstruct} реально
 * строит {@link JobCompletionListener} и передаёт ему обе ручки. Значение,
 * отличное от {@code @Value}-дефолта, обязано дойти до полей слушателя.
 *
 * <p>Мутации, которые обязаны ронять тест: заменить задокументированное имя на
 * укороченное (без {@code WORKER_}); убрать {@code @Value} с поля; убрать
 * передачу ручки в {@code setConfirmTimeoutMs} / {@code setEnsurePublisherConfirms}.
 */
class C836WorkerConfirmEnvBindingTest {

    private static final String QUICKSTART = "docs/guides/integration-quickstart.md";

    /** Ключ свойства → дефолт {@code @Value} (значение-сентинель обязано отличаться). */
    private static final String CONFIRMS_KEY = "zorrobpm.worker.ensure-publisher-confirms";
    private static final String TIMEOUT_KEY = "zorrobpm.worker.completion-confirm-timeout";

    /**
     * Имя переменной окружения, отличное от дефолта {@code @Value}
     * ({@code true} и {@code 5000} соответственно) и лежащее внутри зажимов
     * confirm-таймаута ([100; 60000]), — иначе ассерт увидел бы зажим, а не связку.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        CONFIRMS_KEY + ", false",
        TIMEOUT_KEY + ", 12345",
    })
    void documentedEnvName_reachesWorkerBean(String propertyKey, String value) {
        String envName = documentedEnvNameFor(propertyKey);

        Map<String, Object> containerEnv = new LinkedHashMap<>();
        containerEnv.put(envName, value);

        runnerWithContainerEnv(containerEnv).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            JobCompletionListener listener = listenerBuiltByRealInit(ctx);
            if (CONFIRMS_KEY.equals(propertyKey)) {
                assertThat(ReflectionTestUtils.getField(listener, "ensurePublisherConfirms"))
                    .as("%s=%s обязан дойти до воркерского слушателя: дефолт @Value — true, "
                        + "и при неработающей связке тест увидел бы именно его", envName, value)
                    .isEqualTo(Boolean.valueOf(value));
            } else {
                assertThat(listener.confirmTimeoutMsForTest())
                    .as("%s=%s обязан дойти до воркерского слушателя: дефолт @Value — 5000, "
                        + "и при неработающей связке тест увидел бы именно его", envName, value)
                    .isEqualTo(Long.parseLong(value));
            }
        });
    }

    /** Задокументированное имя обязано быть relaxed-формой своего ключа. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({CONFIRMS_KEY, TIMEOUT_KEY})
    void documentedEnvName_isRelaxedBindingFormOfItsPropertyKey(String propertyKey) {
        assertThat(documentedEnvNameFor(propertyKey))
            .as("имя переменной — это ровно ключ в верхнем регистре с точками и дефисами "
                + "на подчёркиваниях; укороченное «правдоподобное» имя не разрешается Spring "
                + "никогда (WO-C8-36, раунд 4, F-7)")
            .isEqualTo(relaxedFormOf(propertyKey));
    }

    // ------------------------------------------------------------------ инфраструктура

    /**
     * Слушатель, который построил НАСТОЯЩИЙ {@code @PostConstruct} стартера:
     * берём контейнер, который зарегистрирован в контексте, и вытаскиваем из
     * него то, что стартер туда положил.
     */
    private static JobCompletionListener listenerBuiltByRealInit(
            org.springframework.context.ApplicationContext ctx) {
        SimpleMessageListenerContainer container = ctx.getBean(SimpleMessageListenerContainer.class);
        ArgumentCaptor<org.springframework.amqp.core.MessageListener> captor =
            ArgumentCaptor.forClass(org.springframework.amqp.core.MessageListener.class);
        verify(container).setMessageListener(captor.capture());
        assertThat(captor.getValue())
            .as("стартер обязан отдать контейнеру НАСТОЯЩИЙ JobCompletionListener — "
                + "иначе ручки не доедут до публикации результата")
            .isInstanceOf(JobCompletionListener.class);
        return (JobCompletionListener) captor.getValue();
    }

    /**
     * Окружение контейнера подменяется тем же классом, которым контейнер доезжает
     * до ключа свойства: «имя переменной → ключ» делает его
     * {@code resolvePropertyName} (spring-core 7.0.9, сверено {@code javap}: обращения
     * к настоящему {@code System.getenv()}, который из процесса не подменяется, нет).
     */
    private static ApplicationContextRunner runnerWithContainerEnv(Map<String, Object> containerEnv) {
        return new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("containerEnv", containerEnv)))
            .withUserConfiguration(WorkerBeans.class);
    }

    /**
     * Имя переменной окружения, задокументированное для ручки — ровно то, откуда
     * оператор контейнерного воркера его и берёт (у воркерских ручек нет compose
     * в этом репозитории: стартер подключается только в zorrobpm-http-connector,
     * а движок в наш compose не входит).
     */
    private static String documentedEnvNameFor(String propertyKey) {
        String doc = read(repoRoot().resolve(QUICKSTART));
        Matcher m = Pattern
            .compile("`" + Pattern.quote(propertyKey) + "`[^\\n]*`(ZORROBPM_[A-Z0-9_]+)`")
            .matcher(doc);
        if (!m.find()) {
            throw new AssertionError(QUICKSTART + ": для ручки " + propertyKey
                + " не задокументировано имя переменной окружения — оператор воркерского "
                + "контейнера не сможет её настроить, а дефолт останется молча "
                + "(WO-C8-36, раунд 4, F-7)");
        }
        return m.group(1);
    }

    private static String relaxedFormOf(String propertyKey) {
        return propertyKey.toUpperCase().replace('.', '_').replace('-', '_');
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve(QUICKSTART)) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("корень репозитория не найден (не найден " + QUICKSTART + ")");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("не прочитан " + path, e);
        }
    }

    /**
     * Бины стартера для контекста теста. <b>Без {@code @Configuration}</b> — по
     * той же причине, что и в engine-тесте этого WO: стереотипный класс из
     * test-sources виден component scan'у любого {@code @SpringBootApplication}
     * в своём пакете и становится бином в чужих контекстах (здесь — с моками
     * вместо AMQP). «Лайтовый» кандидат, зарегистрированный явно через
     * {@code withUserConfiguration}, даёт рабочие {@code @Bean}-методы и при этом
     * неотличим для сканирования.
     */
    static class WorkerBeans {

        @Bean
        JobHandler jobHandler() {
            JobHandler handler = mock(JobHandler.class);
            when(handler.getJob()).thenReturn("c836Task");
            return handler;
        }

        @Bean
        ApplicationContext applicationContext(JobHandler jobHandler) {
            ApplicationContext ctx = mock(ApplicationContext.class);
            // Карта собирается ДО thenReturn: создание мока внутри незавершённого
            // стаббинга — это ровно та ошибка, на которой Mockito спотыкается
            // («Unfinished stubbing detected»).
            Map<String, JobHandler> handlers = Map.of("jobHandler", jobHandler);
            when(ctx.getBeansOfType(JobHandler.class)).thenReturn(handlers);
            return ctx;
        }

        @Bean
        SimpleMessageListenerContainer listenerContainer() {
            return mock(SimpleMessageListenerContainer.class);
        }

        @Bean
        SimpleRabbitListenerContainerFactory listenerContainerFactory(
                SimpleMessageListenerContainer listenerContainer) {
            SimpleRabbitListenerContainerFactory factory =
                mock(SimpleRabbitListenerContainerFactory.class);
            when(factory.createListenerContainer()).thenReturn(listenerContainer);
            return factory;
        }

        @Bean
        RabbitTemplate rabbitTemplate() {
            return mock(RabbitTemplate.class);
        }

        @Bean
        AmqpAdmin amqpAdmin() {
            AmqpAdmin admin = mock(AmqpAdmin.class);
            // Очередь существует — init() не объявляет её (декларация не предмет теста).
            when(admin.getQueueInfo(anyString())).thenReturn(mock(QueueInformation.class));
            return admin;
        }

        @Bean
        HandlerAutoConfiguration handlerAutoConfiguration(
                ApplicationContext applicationContext,
                SimpleRabbitListenerContainerFactory listenerContainerFactory,
                RabbitTemplate rabbitTemplate,
                AmqpAdmin amqpAdmin) {
            return new HandlerAutoConfiguration(
                applicationContext, listenerContainerFactory, rabbitTemplate, amqpAdmin);
        }
    }
}
