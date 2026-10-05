package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.handler.JobHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HandlerAutoConfiguration {

    private final ApplicationContext applicationContext;
    private final SimpleRabbitListenerContainerFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    private ObjectMapper objectMapper;  // shared instance (CRIT-5)

    /**
     * WO-REL-62: с какой каденцией контейнер перепроверяет missing queue
     * (ms). Дефолт Spring AMQP — 60с: воркер, поднявшийся раньше своей очереди,
     * ждал бы минуту даже при живом брокере; 2с — достаточно быстро для
     * reconnect-сценария и достаточно редко, чтобы не шуметь в лог.
     */
    static final long RETRY_DECLARATION_INTERVAL_MS = 2_000L;

    /** WO-QW-1 S-7: package-visible for the unit test (same mechanism as RabbitConfiguration). */
    static void setExchangeOnlyTrustedPackages(
            org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper mapper) {
        try {
            java.lang.reflect.Field f =
                org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper.class
                    .getDeclaredField("trustedPackages");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<String> trusted = (java.util.Set<String>) f.get(mapper);
            trusted.clear();
            trusted.add("com.zorrodev.bpm.exchange");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WO-QW-1 S-7: cannot narrow AMQP trusted packages", e);
        }
    }

    @PostConstruct
    public void init() {
        this.objectMapper = new ObjectMapper();
        Map<String, JobHandler> handlersMap = applicationContext.getBeansOfType(JobHandler.class);
        log.info("Found {} handlers", handlersMap.size());

        // One-time converter config (CRIT-3: not in loop, CRIT-4: Jackson2 variant).
        // WO-QW-1 S-7: narrow the deserialization allowlist to our own exchange
        // package (clear-then-add: the setter only adds, the constructor seeds
        // java.util/java.lang — we never pass a literal "*"). Sibling path:
        // RabbitConfiguration.messageConverter().
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        setExchangeOnlyTrustedPackages(
            (org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper)
                converter.getJavaTypeMapper());
        connectionFactory.setMessageConverter(converter);
        rabbitTemplate.setMessageConverter(converter);

        for (Map.Entry<String, JobHandler> entry : handlersMap.entrySet()) {
            JobHandler handler = entry.getValue();
            SimpleMessageListenerContainer container = connectionFactory.createListenerContainer();
            String queueName = "zorrobpm.jobs." + handler.getJob();
            container.setQueueNames(queueName);
            // WO-REL-62: очередь может появиться позже, чем стартует контейнер
            // (брокер лежал на старте и declare ушёл на reconnect; движок тоже
            // объявляет очередь лениво на send-пути). Умирать на missing queue
            // нельзя — это и была постоянная глухота; ждём и цепляемся ретраями.
            container.setMissingQueuesFatal(false);
            container.setFailedDeclarationRetryInterval(RETRY_DECLARATION_INTERVAL_MS);
            // WO-REL-62 (ex WO-ENG-31): declare-блок обязан переживать недоступный
            // брокер на старте — но НЕ пропуском подписки (та была постоянной
            // глухотой до ручного рестарта, NEW5-03), а переносом declare на
            // переподключение. Контейнер стартует ВСЕГДА: у него собственный
            // reconnect-цикл, который подхватит брокер, когда тот вернётся.
            try {
                if (amqpAdmin.getQueueInfo(queueName) == null) {
                    declareQueue(handler, queueName);
                }
            } catch (RuntimeException e) {
                log.warn("RabbitMQ unreachable at startup, subscription to {} "
                    + "starts without declare (will declare on reconnect): {}",
                    queueName, e.getMessage());
                scheduleDeclareOnReconnect(handler, queueName, container);
            }
            log.info("Subscribing to {}", queueName);
            // WO-REL-36: вся логика — в JobCompletionListener (разделение ошибок +
            // идемпотентная переотправка); здесь только wiring.
            container.setMessageListener(
                new JobCompletionListener(handler, rabbitTemplate, objectMapper, queueName));
            container.start();
        }

    }

    /**
     * WO-REL-45: зеркало engine-side {@code JobQueueDeclarer} — DLX/DLQ-аргументы
     * byte-identical, кто первым объявил — выиграл, второй declare — no-op
     * (те же аргументы, нет 406). Воркер, стартовавший раньше любого engine-
     * анонса, всё равно получает DLQ. Вынесено из {@link #init()} без изменений —
     * вызывается и на старте, и на reconnect.
     */
    private void declareQueue(JobHandler handler, String queueName) {
        String dlqName = "zorrobpm.jobs." + handler.getJob() + ".dlq";
        amqpAdmin.declareExchange(
            new org.springframework.amqp.core.DirectExchange("zorrobpm.jobs.dlx", true, false));
        amqpAdmin.declareQueue(
            org.springframework.amqp.core.QueueBuilder.durable(dlqName).build());
        amqpAdmin.declareBinding(new org.springframework.amqp.core.Binding(
            dlqName, org.springframework.amqp.core.Binding.DestinationType.QUEUE,
            "zorrobpm.jobs.dlx", dlqName, null));
        Queue queue = org.springframework.amqp.core.QueueBuilder.durable(queueName)
            .deadLetterExchange("zorrobpm.jobs.dlx")
            .deadLetterRoutingKey(dlqName)
            .build();
        amqpAdmin.declareQueue(queue);
        log.info("Queue {} created (DLQ {})", queueName, dlqName);
    }

    /**
     * WO-REL-62: одноразовый declare на первом переподключении после стартового
     * провала. Стандартный Spring AMQP паттерн ({@code ConnectionListener.onCreate}):
     * брокер вернулся → новое физическое соединение → объявляем очередь, которую
     * не смогли объявить на старте → снимаемся (declare идемпотентен, повторные
     * соединения его не требуют). Неудача и здесь — НЕ снимаемся: следующее
     * переподключение попробует снова (брокер может флапать).
     *
     * <p>Reentrancy-защита: {@code RabbitAdmin} сам открывает соединения на той
     * же фабрике, и чужой {@code onCreate} может прийти рекурсивно/параллельно —
     * {@code inFlight} сериализует попытки, чтобы два declare не шли внахлёст.
     */
    private void scheduleDeclareOnReconnect(JobHandler handler, String queueName,
            SimpleMessageListenerContainer container) {
        ConnectionFactory cf = container.getConnectionFactory();
        if (!(cf instanceof CachingConnectionFactory cachingCf)) {
            log.warn("Cannot schedule redeclare for {}: connection factory is {} "
                + "(not caching) — queue will be declared lazily by the engine "
                + "on the send path", queueName,
                cf == null ? "null" : cf.getClass().getSimpleName());
            return;
        }
        AtomicBoolean inFlight = new AtomicBoolean(false);
        ConnectionListener[] self = new ConnectionListener[1];
        self[0] = new ConnectionListener() {
            @Override
            public void onCreate(Connection connection) {
                if (!inFlight.compareAndSet(false, true)) {
                    return;
                }
                try {
                    declareQueue(handler, queueName);
                    if (cachingCf.removeConnectionListener(self[0])) {
                        log.info("Queue {} declared on reconnect, redeclare listener removed",
                            queueName);
                    }
                } catch (RuntimeException e) {
                    if (isPreconditionFailed(e)) {
                        // WO-REL-51 (зеркало engine-side): очередь существует с
                        // legacy-определением (pre-REL-45, без DLX) — повторный
                        // declare с DLX-аргументами не преуспеет никогда, только
                        // операторская миграция поможет. Терминально для этой JVM:
                        // снимаемся, warn ровно один раз (дальше — как engine:
                        // очередь без DLQ, см. runbook миграции).
                        if (cachingCf.removeConnectionListener(self[0])) {
                            log.warn("Queue {} exists with legacy arguments (no DLX — "
                                + "DLQ inactive for this job type); not retrying. "
                                + "Migrate per docs/runbooks/rabbitmq-legacy-queue-dlx-migration.md",
                                queueName);
                        }
                    } else {
                        log.warn("Declare of {} on reconnect failed (will retry on next "
                            + "reconnect): {}", queueName, e.getMessage());
                    }
                } finally {
                    inFlight.set(false);
                }
            }
        };
        cachingCf.addConnectionListener(self[0]);
        log.info("Scheduled redeclare of {} on broker reconnect", queueName);
    }

    /**
     * WO-REL-51, локальная копия engine-side {@code JobQueueDeclarer.isPreconditionFailed}:
     * стартер не зависит от {@code zorrobpm-rabbitmq} в compile-scope (там только
     * test-scope, см. pom) — тащить модуль ради одного предиката тяжелее, чем
     * держать зеркало с явной ссылкой. Сигналы те же, оба независимых:
     * структурированный (ShutdownSignalException с reply-code 406) и текстовый
     * фолбэк (PRECONDITION_FAILED / 406+inequivalent). Голый 406 без ключевых слов —
     * НЕ terminal (ретрай может вылечить), как в engine.
     */
    static boolean isPreconditionFailed(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof com.rabbitmq.client.ShutdownSignalException sse) {
                Object reason = sse.getReason();
                if (reason instanceof com.rabbitmq.client.AMQP.Channel.Close channelClose
                    && channelClose.getReplyCode() == 406) {
                    return true;
                }
                if (reason instanceof com.rabbitmq.client.AMQP.Connection.Close connectionClose
                    && connectionClose.getReplyCode() == 406) {
                    return true;
                }
            }
            String message = c.getMessage();
            if (message != null) {
                String upper = message.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("PRECONDITION_FAILED")) {
                    return true;
                }
                if (upper.contains("406") && upper.contains("INEQUIVALENT")) {
                    return true;
                }
            }
        }
        return false;
    }
}
