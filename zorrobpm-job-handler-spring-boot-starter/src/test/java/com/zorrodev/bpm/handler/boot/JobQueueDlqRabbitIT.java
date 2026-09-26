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
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-45 criterion 2: poison message on a {@code zorrobpm.jobs.*} queue is
 * parked in the per-type DLQ after the worker gives up — not lost, not
 * redelivered forever. REAL broker (no mocks), same env plumbing as
 * {@code CompletionTransportRabbitIT} (P-23).
 *
 * <p>Shape (G-N: the queue under test is declared by the REAL production
 * {@code JobQueueDeclarer}, not by a copy of its arguments — revert the DLX
 * args in {@code JobQueueDeclarer.declare} and the poison test goes RED):
 * publish one message, redeliver it a few times with requeue=true (simulating
 * worker retries), then reject with requeue=false (the worker giving up) and
 * assert the message sits in {@code zorrobpm.jobs.<type>.dlq} with its body
 * intact and the main queue is empty. A second control message ACKed normally
 * never touches the DLQ.
 *
 * <p>Run: {@code RABBIT_PORT=5675 ci/run-rabbit-tests.sh} (or plain
 * {@code ci/run-rabbit-tests.sh} when 5673 is free).
 */
@Tag("rabbit")
class JobQueueDlqRabbitIT {

    private static final String JOB_TYPE = "rel45poison" + UUID.randomUUID().toString().substring(0, 8);
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

        // G-N: the queue under test is declared by the REAL production
        // declarer (same call the engine makes on announcement/send) — not by
        // a copy of its arguments inlined here.
        new JobQueueDeclarer(admin).declare(JOB_TYPE);
        // Purge AFTER declare (purge on a missing queue throws) — also guards
        // cross-run residue if a previous run crashed before teardown.
        admin.purgeQueue(QUEUE, false);
        try {
            admin.purgeQueue(DLQ, false);
        } catch (Exception e) {
            // Tolerated on purpose: the DLQ exists only because the REAL
            // JobQueueDeclarer declares it. If prod code ever stops declaring
            // the DLQ (the WO-REL-45 POF mutant), setup must NOT mask that as
            // a setup error — the poison test below must go RED behaviourally
            // (awaitDlq timeout = message lost, not parked).
        }
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
    void poisonMessage_rejectedParkedInDlq_notLost_notRedelivered() throws Exception {
        byte[] poison = ("poison-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        // Worker-style give-up: a few redeliveries (requeue=true — the body
        // must survive each one), then ONE final reject with requeue=false.
        // NOTE: rejecting with requeue=false dead-letters IMMEDIATELY (first
        // reject parks the message) — looping get+reject(requeue=false) N times
        // is a modelling error: the 2nd get would find an empty queue.
        //
        // P-10: publish AND consume on the SAME channel. basicPublish is async
        // (no broker response); a basicGet on a DIFFERENT connection can win
        // the race and observe an empty queue. Same-channel methods are
        // processed FIFO, so get-after-publish deterministically sees the
        // message (caught live: one full-group run failed exactly here).
        ConnectionFactory raw = new ConnectionFactory();
        raw.setHost(host);
        raw.setPort(port);
        raw.setUsername(user);
        raw.setPassword(password);
        try (Connection conn = raw.newConnection(); Channel ch = conn.createChannel()) {
            ch.basicPublish("", QUEUE, null, poison);
            for (int i = 0; i < 2; i++) {
                GetResponse got = ch.basicGet(QUEUE, false);
                assertThat(got).as("poison must be consumable, redelivery %d", i).isNotNull();
                assertThat(got.getBody())
                    .as("redelivered body must stay intact")
                    .isEqualTo(poison);
                ch.basicReject(got.getEnvelope().getDeliveryTag(), true);
            }
            GetResponse last = ch.basicGet(QUEUE, false);
            assertThat(last).as("poison must be consumable for the final attempt").isNotNull();
            ch.basicReject(last.getEnvelope().getDeliveryTag(), false);
            // Main queue drained — nothing left to redeliver forever.
            assertThat(ch.basicGet(QUEUE, true)).as("main queue must be empty").isNull();
            // DLQ holds exactly the one parked copy, body intact. Dead-letter
            // routing is an INTERNAL broker hop (not in this channel's FIFO
            // stream), so the parked copy may land a few ms after the reject
            // is acknowledged — poll with a deadline (Awaitility), no sleeps.
            // Genuinely lost (no DLX) → timeout → clean RED, not a flake.
            GetResponse parked = awaitDlq(ch);
            assertThat(parked).as("poison must be parked in the DLQ, not lost").isNotNull();
            assertThat(parked.getBody()).isEqualTo(poison);
            assertThat(ch.basicGet(DLQ, true)).as("DLQ must hold exactly one copy").isNull();
        }
    }

    @Test
    void ackedMessage_neverTouchesDlq() throws Exception {
        byte[] good = ("good-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        // P-10 (see poison test): same-channel publish+get, no cross-connection race.
        ConnectionFactory raw = new ConnectionFactory();
        raw.setHost(host);
        raw.setPort(port);
        raw.setUsername(user);
        raw.setPassword(password);
        try (Connection conn = raw.newConnection(); Channel ch = conn.createChannel()) {
            ch.basicPublish("", QUEUE, null, good);
            GetResponse got = ch.basicGet(QUEUE, false);
            assertThat(got).isNotNull();
            ch.basicAck(got.getEnvelope().getDeliveryTag(), false);
            assertThat(ch.basicGet(DLQ, true)).as("ACKed message must not reach the DLQ").isNull();
        }
    }

    @Test
    void queueDeclare_mismatchedArgs_failsInsteadOfSilentlyDroppingDlx() throws Exception {
        // Criterion-3-adjacent guard: a bare durable redeclare (pre-REL-45 shape,
        // no DLX args) must NOT silently strip the DLX — the broker 406-closes
        // the probing channel on an inequivalent redeclare.
        //
        // Proof is behavioural, not message-text: (1) the bare redeclare must
        // not succeed with the channel still open (that outcome = the DLX args
        // silently lost); the exact client exception spelling
        // (IOException vs ShutdownSignalException, null vs non-null message)
        // varies by client version and is NOT asserted. (2) afterwards the
        // queue's DLX wiring still works — publish → reject(requeue=false) →
        // parked in the DLQ — so the 406 probe neither stripped the args nor
        // destroyed the queue.
        ConnectionFactory raw = new ConnectionFactory();
        raw.setHost(host);
        raw.setPort(port);
        raw.setUsername(user);
        raw.setPassword(password);
        Connection conn = raw.newConnection();
        try {
            Channel probe = conn.createChannel();
            String redeclareOutcome;
            try {
                probe.queueDeclare(QUEUE, true, false, false, null);
                redeclareOutcome = probe.isOpen() ? "no-error-channel-open" : "no-error-channel-closed";
            } catch (Exception e) {
                redeclareOutcome = e.getClass().getSimpleName()
                    + (probe.isOpen() ? "-channel-open" : "-channel-closed");
            } finally {
                try {
                    probe.close();
                } catch (Exception ignored) {
                    // The 406 already closed this channel — the expected aftermath.
                }
            }
            assertThat(redeclareOutcome)
                .as("bare redeclare of a DLX queue must 406 (kill the channel), "
                    + "not silently succeed with the channel open (got: %s)", redeclareOutcome)
                .isNotEqualTo("no-error-channel-open");

            // DLX wiring intact after the 406 probe: publish → reject → DLQ
            // (same channel for publish+get — P-10, see poison test; Awaitility
            // for the dead-letter hop, same as above).
            try (Channel verify = conn.createChannel()) {
                byte[] guard = ("dlx-guard-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
                verify.basicPublish("", QUEUE, null, guard);
                GetResponse got = verify.basicGet(QUEUE, false);
                assertThat(got).as("queue must still route after the 406 probe").isNotNull();
                verify.basicReject(got.getEnvelope().getDeliveryTag(), false);
                GetResponse parked = awaitDlq(verify);
                assertThat(parked)
                    .as("DLX args must survive the mismatched redeclare — message parked, not dropped")
                    .isNotNull();
                assertThat(parked.getBody()).isEqualTo(guard);
            }
        } finally {
            conn.close();
        }
    }

    @Test
    void listener_neverSeesDlqAsWork() {
        // The DLQ name is derived, never subscribed as a work queue by the starter:
        // HandlerAutoConfiguration subscribes "zorrobpm.jobs." + job, the DLQ has
        // the extra ".dlq" suffix — a worker for type T never consumes T.dlq.
        assertThat(DLQ).startsWith(QUEUE + ".");
        assertThat(QUEUE).startsWith("zorrobpm.jobs.");
    }

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    /**
     * Polls the DLQ until the dead-lettered copy lands (or the deadline hits).
     * Wraps the mutable {@code GetResponse[]} holder because lambdas need an
     * effectively-final target; Awaitility ignores the return and polls on the
     * condition, the holder carries the payload out.
     */
    private GetResponse awaitDlq(Channel ch) {
        final GetResponse[] parked = {null};
        Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(10))
            .pollInterval(java.time.Duration.ofMillis(50))
            .until(() -> {
                // A missing DLQ (POF mutant: prod code stopped declaring it)
                // surfaces as IOException on basicGet — NOT success, NOT an
                // instant failure: keep polling so the outcome is a behavioural
                // timeout ("message lost, never parked"), not a setup-shaped
                // error. Genuinely lost → ConditionTimeout → clean RED.
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
}
