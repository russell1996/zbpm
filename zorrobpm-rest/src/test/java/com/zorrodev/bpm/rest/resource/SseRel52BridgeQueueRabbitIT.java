package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-52, A4 (брокерная половина): очередь с x-max-length + drop-head
 * реально держит лимит на живом брокере — переполнение роняет СТАРЫЕ
 * сообщения, свежие доходят. @Tag("rabbit") — гоняется через
 * ci/run-rabbit-tests.sh, как RabbitOutboxConfirmIT.
 *
 * <p>Механическая половина (declare несёт аргументы) — SseRel52BridgeQueuePolicyTest.
 * POF-мутация: x-overflow drop-head → reject-publish — этот тест КРАСНЫЙ
 * (старые сообщения НЕ роняются: очередь стоит на лимите, свежее не входит).
 */
@Tag("rabbit")
@ActiveProfiles("test")
@SpringBootTest(classes = TestMain.class, properties = {
    "spring.rabbitmq.host=${RABBITMQ_HOST:localhost}",
    "spring.rabbitmq.port=${RABBITMQ_PORT:5672}",
    "spring.rabbitmq.username=${RABBITMQ_USER:zorrodev}",
    "spring.rabbitmq.password=${RABBITMQ_PASSWORD:zorrodev}"
})
class SseRel52BridgeQueueRabbitIT {

    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    void maxLengthDropHead_evictsOldestKeepsNewest() throws Exception {
        String q = "zorrobpm.sse-bridge.probe-" + java.util.UUID.randomUUID();
        Map<String, Object> args = Map.of(
            "x-max-length", 10,
            "x-overflow", "drop-head");
        rabbitAdmin.declareQueue(new Queue(q, false, true, true, args));
        try {
            // Льём 30 сообщений в очередь с лимитом 10 — head-drop обязан
            // ронять старые, свежие входят.
            for (int i = 0; i < 30; i++) {
                rabbitAdmin.getRabbitTemplate().convertAndSend("", q, "msg-" + i);
            }
            Properties info = rabbitAdmin.getQueueProperties(q);
            assertThat(info).as("queue info readable").isNotNull();
            Object count = info.get("QUEUE_MESSAGE_COUNT");
            assertThat(count).as("queue holds at most max-length").isNotNull();
            assertThat(((Number) count).longValue())
                .as("x-max-length=10 enforced by the broker")
                .isLessThanOrEqualTo(10);
            // Свежайшее сообщение — на месте (хвост не отброшен).
            // basicGet по одному (receiveAndConvert открывает consumer и
            // падает IOException на auto-delete очереди без потребителей в
            // этой версии клиента — поймано живым прогоном).
            com.rabbitmq.client.Channel raw =
                connectionFactory.createConnection().createChannel(false);
            java.util.List<String> rest = new java.util.ArrayList<>();
            String latest = null;
            try {
                for (int i = 0; i < 11; i++) {
                    com.rabbitmq.client.GetResponse resp = raw.basicGet(q, true);
                    if (resp == null) {
                        break;
                    }
                    latest = new String(resp.getBody());
                    rest.add(latest);
                }
            } finally {
                raw.close();
            }
            assertThat(latest).as("newest batch reachable").isNotNull();
            assertThat(rest).as("oldest messages evicted first").doesNotContain("msg-0", "msg-1");
            assertThat(rest).as("newest message kept").contains("msg-29");
        } finally {
            rabbitAdmin.deleteQueue(q);
        }
    }
}
