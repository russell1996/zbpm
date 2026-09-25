package com.zorrodev.bpm.handler.boot;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.zorrodev.bpm.rabbitmq.JobQueueDeclarer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WO-REL-51 criterion 3: the operator migration in
 * {@code docs/runbooks/rabbitmq-legacy-queue-dlx-migration.md}, reproduced
 * against a REAL broker (no mocks): a queue in the pre-REL-45 shape (bare
 * durable, no DLX args) holding in-flight messages → migrate (quiesce → drain →
 * delete → redeclare via the REAL {@code JobQueueDeclarer} → republish) →
 * all messages preserved AND the queue now dead-letters to its DLQ.
 *
 * <p>G-N: both the failure (real broker 406 on inequivalent redeclare) and
 * the recovery (real {@code new JobQueueDeclarer(admin).declare(...)}) go
 * through production code — the legacy-shape fixture below imitates broker
 * STATE (what prod looks like), not prod logic.
 *
 * <p>Run: {@code RABBIT_PORT=5675 ci/run-rabbit-tests.sh} (or plain
 * {@code ci/run-rabbit-tests.sh} when 5673 is free).
 */
@Tag("rabbit")
class LegacyQueueDlxMigrationRabbitIT {

    private static final String JOB_TYPE = "rel51legacy" + UUID.randomUUID().toString().substring(0, 8);
    private static final String QUEUE = "zorrobpm.jobs." + JOB_TYPE;
    private static final String DLQ = QUEUE + ".dlq";

    private String host;
    private int port;
    private String user;
    private String password;

    private CachingConnectionFactory cf;
    private RabbitAdmin admin;

    @BeforeEach
    void setup() {
        host = cfg("RABBITMQ_HOST", "localhost");
        port = Integer.parseInt(cfg("RABBITMQ_PORT", "5672"));
        user = cfg("RABBITMQ_USER", "zorrodev");
        password = cfg("RABBITMQ_PASSWORD", "zorrodev");

        cf = new CachingConnectionFactory(host, port);
        cf.setUsername(user);
        cf.setPassword(password);
        admin = new RabbitAdmin(cf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();

        // Pre-REL-45 broker state: the bare durable queue, exactly what prod
        // holds for every job type deployed before REL-45.
        admin.declareQueue(new Queue(QUEUE, true));
    }

    @AfterEach
    void teardown() {
        try {
            admin.deleteQueue(QUEUE);
            admin.deleteQueue(DLQ);
        } catch (Exception ignored) {
        }
        if (cf != null) cf.destroy();
    }

    @Test
    void legacyQueue_migratedWithoutLoss_dlxWorksAfterwards() throws Exception {
        ConnectionFactory raw = new ConnectionFactory();
        raw.setHost(host);
        raw.setPort(port);
        raw.setUsername(user);
        raw.setPassword(password);
        try (Connection conn = raw.newConnection(); Channel ch = conn.createChannel()) {
            // ---- 1. In-flight messages on the legacy queue (before migration).
            List<byte[]> before = List.of(payload("before-1"), payload("before-2"), payload("before-3"));
            for (byte[] b : before) ch.basicPublish("", QUEUE, null, b);

            // ---- 2. The engine hits the legacy queue: REAL declarer, REAL
            // broker 406. declare() must swallow it (deploy/send never fail)
            // and pin the type as legacy — no storm afterwards.
            JobQueueDeclarer declarer = new JobQueueDeclarer(admin);
            assertThatCode(() -> declarer.declare(JOB_TYPE)).doesNotThrowAnyException();
            assertThat(declarer.isLegacyDeclared(JOB_TYPE))
                .as("real broker 406 must pin the type as legacy")
                .isTrue();
            assertThatCode(() -> declarer.declare(JOB_TYPE)).doesNotThrowAnyException();

            // ---- 3. Quiesce + drain: take every ready message with ACK,
            // holding bodies aside (the runbook's drain step). Messages
            // arriving DURING the drain are collected by the same loop.
            List<byte[]> drained = new ArrayList<>();
            drainInto(ch, drained);
            byte[] during1 = payload("during-1");
            byte[] during2 = payload("during-2");
            ch.basicPublish("", QUEUE, null, during1);
            ch.basicPublish("", QUEUE, null, during2);
            drainInto(ch, drained);

            Set<String> expected = bodiesOf(before);
            expected.add(new String(during1, StandardCharsets.UTF_8));
            expected.add(new String(during2, StandardCharsets.UTF_8));
            assertThat(bodiesOf(drained))
                .as("drain must collect every pre/during-migration message")
                .containsExactlyInAnyOrderElementsOf(expected);

            // ---- 4. Delete + redeclare. A FRESH declarer = the app restart
            // the runbook ends with (the legacy pin is per-JVM).
            assertThat(admin.deleteQueue(QUEUE)).as("legacy queue must be deletable").isTrue();
            JobQueueDeclarer fresh = new JobQueueDeclarer(admin);
            fresh.declare(JOB_TYPE);
            assertThat(fresh.isLegacyDeclared(JOB_TYPE))
                .as("redeclared queue must be clean, not legacy")
                .isFalse();

            // ---- 5. Republish the drained bodies, verify zero loss.
            for (byte[] b : drained) ch.basicPublish("", QUEUE, null, b);
            List<byte[]> back = new ArrayList<>();
            drainInto(ch, back);
            assertThat(bodiesOf(back))
                .as("every drained message must be back on the migrated queue")
                .containsExactlyInAnyOrderElementsOf(expected);

            // ---- 6. DLX proof on the migrated queue: reject → parked in DLQ.
            byte[] probe = payload("dlx-probe");
            ch.basicPublish("", QUEUE, null, probe);
            GetResponse got = ch.basicGet(QUEUE, false);
            assertThat(got).as("migrated queue must route").isNotNull();
            ch.basicReject(got.getEnvelope().getDeliveryTag(), false);
            GetResponse parked = awaitDlq(ch);
            assertThat(parked).as("migrated queue must dead-letter into its DLQ").isNotNull();
            assertThat(parked.getBody()).isEqualTo(probe);
            // …and the queue name/routing still match the pre-REL-45 contract.
            assertThat(QUEUE).isEqualTo("zorrobpm.jobs." + JOB_TYPE);
            assertThat(DLQ).isEqualTo(QUEUE + ".dlq");
        }
    }

    /** basicGet-loop until the queue is empty; ACKed bodies land in {@code out}. */
    private static void drainInto(Channel ch, List<byte[]> out) throws Exception {
        while (true) {
            GetResponse got = ch.basicGet(QUEUE, false);
            if (got == null) return;
            out.add(got.getBody());
            ch.basicAck(got.getEnvelope().getDeliveryTag(), false);
        }
    }

    private static Set<String> bodiesOf(List<byte[]> bodies) {
        Set<String> s = new HashSet<>();
        for (byte[] b : bodies) s.add(new String(b, StandardCharsets.UTF_8));
        return s;
    }

    private static byte[] payload(String tag) {
        return (tag + "-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    private GetResponse awaitDlq(Channel ch) {
        final GetResponse[] parked = {null};
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(50))
            .until(() -> {
                try {
                    GetResponse got = ch.basicGet(DLQ, true);
                    if (got != null) {
                        parked[0] = got;
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    return false;
                }
            });
        return parked[0];
    }

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }
}
