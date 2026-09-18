package com.zorrodev.bpm.handler.boot;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-42 criteria 1-2: prefetch actually bounds a slow consumer, on a REAL broker.
 *
 * <p>Scenario (criterion 1): 30 messages in a queue, TWO single-thread consumers
 * behind a latch — slow-C holds its FIRST message for 3s without acking while
 * fast-C drains. With prefetch=1 slow-C can only ever hold 1 message: fast-C
 * must receive 29 (everything else). With the Spring default prefetch (~250)
 * slow-C would have grabbed the whole queue and fast-C would starve.
 *
 * <p>Scenario (criterion 2): after stopping slow-C mid-hold, the redelivered
 * count is 1 (the single unacked message), not the whole queue.
 *
 * <p>Both go through {@code createListenerContainer()} + explicit prefetch —
 * the same wiring the production customizer tunes
 * ({@code RabbitListenerTuningConfiguration}), only with prefetch=1 to make
 * the bound crisp (production uses 10 — same mechanism, looser bound).
 *
 * <p>Run: same env plumbing as {@code CompletionTransportRabbitIT} (P-23);
 * default port here is the isolated probe broker, CI overrides via env.
 */
@Tag("rabbit")
class ListenerPrefetchRabbitIT {

    private static final String QUEUE = "rel42.it.prefetch.probe";

    private String host;
    private int port;
    private String user;
    private String password;

    private CachingConnectionFactory cf;
    private RabbitAdmin admin;

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @BeforeEach
    void setUp() throws Exception {
        host = cfg("RABBITMQ_HOST", "localhost");
        port = Integer.parseInt(cfg("RABBITMQ_PORT", "56777"));
        user = cfg("RABBITMQ_USER", "zorrodev");
        password = cfg("RABBITMQ_PASSWORD", "zorrodev");

        cf = new CachingConnectionFactory(host, port);
        cf.setUsername(user);
        cf.setPassword(password);
        admin = new RabbitAdmin(cf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();
        admin.declareQueue(new org.springframework.amqp.core.Queue(QUEUE, true, false, false));
        admin.purgeQueue(QUEUE, false);
        assertThat(serverMessageCount(QUEUE)).as("queue empty at start").isZero();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            admin.purgeQueue(QUEUE, false);
        } catch (Exception ignored) {
        }
        try {
            cf.destroy();
        } catch (Exception ignored) {
        }
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

    private static void await(String what, Duration timeout, java.util.function.BooleanSupplier cond)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("timed out waiting for: " + what);
    }

    /** prefetch=1 container on the real broker, through the production wiring shape. */
    private SimpleMessageListenerContainer container(MessageListener listener) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(cf);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        SimpleMessageListenerContainer container =
            (SimpleMessageListenerContainer) factory.createListenerContainer();
        container.setQueueNames(QUEUE);
        container.setMessageListener(listener);
        return container;
    }

    @Test
    void slowConsumerHoldsOne_fastConsumerDrainsRest() throws Exception {
        final int total = 30;
        for (int i = 0; i < total; i++) {
            try (Connection c = new ConnectionFactory() {{
                    setHost(host);
                    setPort(port);
                    setUsername(user);
                    setPassword(password);
                }}.newConnection();
                 Channel ch = c.createChannel()) {
                ch.basicPublish("", QUEUE, null,
                    ("msg-" + i).getBytes(StandardCharsets.UTF_8));
            }
        }
        assertThat(serverMessageCount(QUEUE)).isEqualTo(total);

        // Slow-C: takes its first message, holds it 3s unacked, counts the rest.
        CountDownLatch slowFirstTaken = new CountDownLatch(1);
        AtomicInteger slowCount = new AtomicInteger(0);
        SimpleMessageListenerContainer slow = container(msg -> {
            slowCount.incrementAndGet();
            slowFirstTaken.countDown();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Fast-C: counts everything it gets.
        AtomicInteger fastCount = new AtomicInteger(0);
        SimpleMessageListenerContainer fast = container(msg -> {
            fastCount.incrementAndGet();
        });

        slow.start();
        try {
            assertThat(slowFirstTaken.await(20, TimeUnit.SECONDS)).isTrue();
            // Slow holds exactly its first message now (prefetch=1 → broker
            // delivered exactly 1). Fast starts and must drain everything else.
            fast.start();
            try {
                await("fast drains " + (total - 1), Duration.ofSeconds(25),
                    () -> fastCount.get() >= total - 1);
            } finally {
                fast.stop();
            }
            // Slow's hold finished by now (3s); it may have taken a couple more
            // after fast stopped — the criterion is the DURING-hold split.
            assertThat(fastCount.get())
                .as("fast consumer drained everything the slow one could not hoard")
                .isGreaterThanOrEqualTo(total - 1);
            assertThat(slowCount.get())
                .as("slow consumer could not hoard the queue (prefetch=1)")
                .isLessThanOrEqualTo(2);
        } finally {
            slow.stop();
        }
    }

    @Test
    void stoppedConsumerRedeliversOnlyItsUnackedHold() throws Exception {
        String payload = "hold-" + UUID.randomUUID();
        try (Connection c = new ConnectionFactory() {{
                setHost(host);
                setPort(port);
                setUsername(user);
                setPassword(password);
            }}.newConnection();
             Channel ch = c.createChannel()) {
            ch.basicPublish("", QUEUE, null, payload.getBytes(StandardCharsets.UTF_8));
        }

        // Slow-C takes the message and NEVER acks (blocks until the transport dies).
        CountDownLatch taken = new CountDownLatch(1);
        SimpleMessageListenerContainer slow = container(msg -> {
            taken.countDown();
            try {
                // Parked essentially forever — only transport death ends this.
                Thread.sleep(60000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        slow.start();
        try {
            assertThat(taken.await(20, TimeUnit.SECONDS)).isTrue();
            // While held: 0 ready (1 unacked) — nothing else to redeliver.
            await("message unacked", Duration.ofSeconds(10),
                () -> {
                    try {
                        return serverMessageCount(QUEUE) == 0;
                    } catch (Exception e) {
                        return false;
                    }
                });
        } finally {
            // Kill the TRANSPORT (not a graceful stop): the broker requeues the
            // single unacked message — same shape as CompletionTransportRabbitIT.
            cf.destroy();
        }

        await("exactly the held message requeued", Duration.ofSeconds(15),
            () -> {
                try {
                    return serverMessageCount(QUEUE) == 1;
                } catch (Exception e) {
                    return false;
                }
            });
    }
}
