package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OBS-8 criterion 1 (broker half, LIVE broker): a job published with W3C
 * {@code traceparent} + {@code processInstanceId} AMQP headers (exactly as
 * {@code ServiceTaskListener} sends them) comes out of the worker with the SAME
 * trace id — in the completion BODY and in the completion HEADERS — after real
 * AMQP serialization through a real rabbitmq:3.13.
 *
 * <p>Assert is on the CONCRETE trace id from the input (P-67): "a completion
 * arrived" would pass with a freshly-minted trace too and prove nothing about
 * continuity. Run: {@code ci/run-rabbit-tests.sh} (same env plumbing as
 * {@code CompletionTransportRabbitIT}, P-23).
 */
@Tag("rabbit")
class TracePropagationRabbitIT {

    private static final String JOB_QUEUE = "obs8.it.jobs.probe";
    private static final String COMPLETE_QUEUE = "obs8.it.complete.probe";

    private String host;
    private int port;
    private String user;
    private String password;

    private CachingConnectionFactory listenerCf;
    private CachingConnectionFactory senderCf;
    private RabbitAdmin admin;
    private SimpleRabbitListenerContainerFactory containerFactory;
    private RabbitTemplate senderTemplate;
    private ObjectMapper objectMapper;

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @BeforeEach
    void setUp() {
        host = cfg("RABBITMQ_HOST", "localhost");
        port = Integer.parseInt(cfg("RABBITMQ_PORT", "5672"));
        user = cfg("RABBITMQ_USER", "zorrodev");
        password = cfg("RABBITMQ_PASSWORD", "zorrodev");

        listenerCf = new CachingConnectionFactory(host, port);
        listenerCf.setUsername(user);
        listenerCf.setPassword(password);
        senderCf = new CachingConnectionFactory(host, port);
        senderCf.setUsername(user);
        senderCf.setPassword(password);

        admin = new RabbitAdmin(senderCf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();
        admin.declareQueue(new org.springframework.amqp.core.Queue(JOB_QUEUE, true, false, false));
        admin.declareQueue(new org.springframework.amqp.core.Queue(COMPLETE_QUEUE, true, false, false));
        admin.purgeQueue(JOB_QUEUE, false);
        admin.purgeQueue(COMPLETE_QUEUE, false);
        try {
            assertThat(serverMessageCount(JOB_QUEUE)).as("вход пуст на старте").isZero();
            assertThat(serverMessageCount(COMPLETE_QUEUE)).as("completion пуст на старте").isZero();
        } catch (AssertionError e) {
            throw new IllegalStateException("stale messages on broker, purge failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("broker not reachable in setUp", e);
        }

        containerFactory = new SimpleRabbitListenerContainerFactory();
        containerFactory.setConnectionFactory(listenerCf);
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(new ObjectMapper());
        containerFactory.setMessageConverter(converter);

        senderTemplate = new RabbitTemplate(senderCf);
        senderTemplate.setMessageConverter(converter);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        try {
            admin.purgeQueue(JOB_QUEUE, false);
            admin.purgeQueue(COMPLETE_QUEUE, false);
        } catch (Exception ignored) {
        }
        try {
            senderCf.destroy();
        } catch (Exception ignored) {
        }
        try {
            listenerCf.destroy();
        } catch (Exception ignored) {
        }
    }

    @Test
    void tracedJob_completionCarriesSameTraceId_liveBroker() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID pi = UUID.randomUUID();
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String traceParent = "00-" + traceId + "-b7ad6b7169203331-01";

        JobHandler handler = new JobHandler() {
            @Override
            public String getJob() {
                return "probe";
            }

            @Override
            public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(JobDetailModel model) {
                return List.of();
            }
        };
        RabbitTemplate listenerTemplate = new RabbitTemplate(listenerCf);
        listenerTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener jobListener =
            new JobCompletionListener(handler, listenerTemplate, objectMapper, JOB_QUEUE, COMPLETE_QUEUE);

        org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer container =
            containerFactory.createListenerContainer();
        container.setQueueNames(JOB_QUEUE);
        container.setMessageListener(jobListener);
        container.setConcurrentConsumers(1);
        container.start();
        try {
            // Exactly what ServiceTaskListener sends: converted body + headers.
            JobDetailModel detail = new JobDetailModel();
            detail.setServiceTaskId(taskId);
            detail.setProcessInstanceId(pi);
            detail.setProcessDefinitionId(UUID.randomUUID());
            detail.setServiceTaskKey("k");
            detail.setJob("probe");
            detail.setVariables(Map.of());
            senderTemplate.convertAndSend(JOB_QUEUE, detail, m -> {
                m.getMessageProperties().setCorrelationId("obs8-" + taskId);
                m.getMessageProperties().setHeader("traceparent", traceParent);
                m.getMessageProperties().setHeader("processInstanceId", pi.toString());
                return m;
            });

            CompletionObserved observed = awaitCompletion(taskId, Duration.ofSeconds(30));

            // Body forward: the engine-side completion listener continues from this.
            assertThat(observed.body.getTraceParent())
                .as("completion body must verbatim-forward the worker's incoming traceparent")
                .isEqualTo(traceParent);
            assertThat(observed.body.getProcessInstanceId()).isEqualTo(pi.toString());
            // Header forward: the engine-side @RabbitListener reads these first.
            // NOTE: the raw rabbitmq client surfaces long headers as LongString,
            // not java.lang.String — compare by value, not by type.
            assertThat(String.valueOf(observed.headers.get("traceparent")))
                .as("completion AMQP headers must carry the same traceparent")
                .isEqualTo(traceParent);
            assertThat(String.valueOf(observed.headers.get("processInstanceId"))).isEqualTo(pi.toString());
        } finally {
            container.stop();
        }
    }

    private record CompletionObserved(ServiceTaskCompleteData body, Map<String, Object> headers) {
    }

    private CompletionObserved awaitCompletion(UUID taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            while (System.nanoTime() < deadline) {
                com.rabbitmq.client.GetResponse resp = ch.basicGet(COMPLETE_QUEUE, true);
                if (resp != null) {
                    String body = new String(resp.getBody(), StandardCharsets.UTF_8);
                    try {
                        ServiceTaskCompleteData data =
                            objectMapper.readValue(body, ServiceTaskCompleteData.class);
                        if (taskId.equals(data.getServiceTaskId()) && "SUCCESS".equals(data.getStatus())) {
                            return new CompletionObserved(data, resp.getProps().getHeaders());
                        }
                    } catch (Exception ignored) {
                        // Чужой мусор — отброшен ack'ом выше, ждём дальше.
                    }
                } else {
                    Thread.sleep(200);
                }
            }
        }
        throw new IllegalStateException("timed out waiting for completion of task " + taskId);
    }

    private int serverMessageCount(String queue) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            return ch.queueDeclarePassive(queue).getMessageCount();
        }
    }
}
