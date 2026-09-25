package com.zorrodev.bpm.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import com.zorrodev.bpm.exchange.JobQueuesRequested;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WO-REL-16: the declarer is what turns the engine's announcement into an actual queue on the
 * broker, and what keeps the send path from paying a broker round-trip per message.
 */
class JobQueueDeclarerTest {

    private AmqpAdmin amqpAdmin;
    private JobQueueDeclarer declarer;

    @BeforeEach
    void setUp() {
        amqpAdmin = mock(AmqpAdmin.class);
        declarer = new JobQueueDeclarer(amqpAdmin);
    }

    @Test
    void announcementDeclaresDurableQueuePerJobType() {
        declarer.on(new JobQueuesRequested(new LinkedHashSet<>(Set.of("billing", "notify"))));

        // WO-REL-45: each job type now declares TWO queues (its DLQ + the work
        // queue itself) — 2 declareQueue calls per type.
        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin, times(4)).declareQueue(captor.capture());
        assertThat(captor.getAllValues()).extracting(Queue::getName)
            .containsExactlyInAnyOrder(
                "zorrobpm.jobs.billing", "zorrobpm.jobs.billing.dlq",
                "zorrobpm.jobs.notify", "zorrobpm.jobs.notify.dlq");
        assertThat(captor.getAllValues()).allMatch(Queue::isDurable);
    }

    /**
     * WO-REL-45 criterion 2 (wiring half): the work queue carries the DLX args
     * (shared DLX + own DLQ as routing key) — the broker half is proven by
     * {@code JobQueueDlqRabbitIT} against a real broker. POF: revert
     * {@code declare} to a bare durable queue and this goes RED.
     */
    @Test
    void workQueueCarriesDlxArgsPointingAtOwnDlq() {
        declarer.declare("billing");

        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin, times(2)).declareQueue(captor.capture());
        Queue work = captor.getAllValues().stream()
            .filter(q -> q.getName().equals("zorrobpm.jobs.billing"))
            .findFirst().orElseThrow();
        assertThat(work.getArguments())
            .containsEntry("x-dead-letter-exchange", JobQueueDeclarer.JOBS_DLX)
            .containsEntry("x-dead-letter-routing-key", "zorrobpm.jobs.billing.dlq");
        assertThat(JobQueueDeclarer.dlqNameFor("billing"))
            .isEqualTo("zorrobpm.jobs.billing.dlq");
    }

    /**
     * Criterion #4: the send path calls declare() on every message. It must not turn into a broker
     * round-trip per message — that is exactly what the old getQueueInfo() check cost.
     */
    @Test
    void repeatedDeclarationHitsBrokerOnlyOnce() {
        declarer.declare("billing");
        declarer.declare("billing");
        declarer.declare("billing");

        // WO-REL-45: one declare() = DLQ + work queue (2 declareQueue calls);
        // the cache still suppresses the 2nd/3rd declare() entirely.
        verify(amqpAdmin, times(2)).declareQueue(any(Queue.class));
        verify(amqpAdmin, times(1)).declareExchange(
            any(org.springframework.amqp.core.Exchange.class));
    }

    /**
     * Criterion #5/#6: a broker that is down must not propagate, and must not poison the cache —
     * the next attempt (announcement or lazy declare on send) has to retry.
     */
    @Test
    void declarationFailureIsSwallowedAndRetriedNextTime() {
        doThrow(new RuntimeException("broker down")).when(amqpAdmin).declareQueue(any(Queue.class));

        assertThatCode(() -> declarer.declare("billing")).doesNotThrowAnyException();
        verify(amqpAdmin, times(1)).declareQueue(any(Queue.class));

        // broker recovers — the previously failed name must be attempted again, not cached as done.
        // WO-REL-45: a full declare is DLQ + work queue (2 declareQueue calls).
        reset(amqpAdmin);
        declarer.declare("billing");
        verify(amqpAdmin, times(2)).declareQueue(any(Queue.class));
    }

    @Test
    void ignoresNullAndBlankJobTypes() {
        declarer.declare(null);
        declarer.declare("");
        declarer.declare("   ");
        declarer.on(new JobQueuesRequested(null));

        verify(amqpAdmin, never()).declareQueue(any(Queue.class));
    }

    @Test
    void queueNameForUsesJobPrefix() {
        assertThat(JobQueueDeclarer.queueNameFor("billing")).isEqualTo("zorrobpm.jobs.billing");
    }

    // ------------------------------------------------------------------
    // WO-REL-51: 406-storm. A queue that exists with the OLD (pre-REL-45,
    // no-DLX) definition makes the broker 406-close the channel on every
    // redeclare-with-args. Retrying that on every message = 4 wasted RPCs +
    // an error-with-stack per message, forever. The 406 is terminal for this
    // JVM: warn ONCE, never attempt again. G-N: every test below calls the
    // REAL JobQueueDeclarer.declare — no copies of its logic.
    // ------------------------------------------------------------------

    /**
     * Criterion 1: after a 406, the next messages of the same type cause NO
     * further broker round-trips — the work-queue declare was attempted
     * exactly once, not once per message.
     *
     * <p>POF pair (with {@link #legacy406_warnsExactlyOnce_neverErrors}): revert
     * {@code declare} to unconditionally {@code declared.remove} on any
     * exception and this goes RED (work queue declared 3× instead of 1×).
     */
    @Test
    void legacy406_secondAndThirdMessageCauseNoBrokerRoundTrips() {
        failWorkQueueWith406();

        declarer.declare("billing");
        declarer.declare("billing");
        declarer.declare("billing");

        // First attempt: DLQ + work queue (2 declareQueue), then silence.
        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin, times(2)).declareQueue(captor.capture());
        assertThat(captor.getAllValues()).extracting(Queue::getName)
            .containsExactlyInAnyOrder("zorrobpm.jobs.billing", "zorrobpm.jobs.billing.dlq");
        verify(amqpAdmin, times(1)).declareExchange(
            any(org.springframework.amqp.core.Exchange.class));
        verify(amqpAdmin, times(1)).declareBinding(
            any(org.springframework.amqp.core.Binding.class));
    }

    /**
     * Criterion 2: the 406 transition logs exactly ONE warn for the job type
     * and NEVER an error — no per-message log flood.
     *
     * <p>POF pair (with the test above): on the old code every attempt logs
     * {@code error} with a stack trace, so the ERROR list is non-empty → RED.
     */
    @Test
    void legacy406_warnsExactlyOnce_neverErrors() {
        failWorkQueueWith406();
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(JobQueueDeclarer.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            declarer.declare("billing");
            declarer.declare("billing");
            declarer.declare("billing");

            assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .hasSize(1);
            assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .isEmpty();
            assertThat(appender.list.get(0).getFormattedMessage())
                .contains("zorrobpm.jobs.billing");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * Guard: a NON-406 failure keeps the old behaviour — dropped from the
     * cache, retried on the next message, still an error log. Only the 406 is
     * terminal; a broker outage must heal by itself.
     */
    @Test
    void non406Failure_stillRetriesAndStillErrors() {
        doThrow(new RuntimeException("broker down")).when(amqpAdmin).declareQueue(any(Queue.class));

        assertThatCode(() -> declarer.declare("billing")).doesNotThrowAnyException();
        assertThatCode(() -> declarer.declare("billing")).doesNotThrowAnyException();

        verify(amqpAdmin, times(2)).declareQueue(any(Queue.class));
        assertThat(declarer.isLegacyDeclared("billing")).isFalse();
    }

    /**
     * Guard: a bare "406" WITHOUT the precondition keywords is NOT treated as
     * legacy — it may be a different, healable failure, so it retries.
     */
    @Test
    void bare406WithoutKeywords_stillRetries() {
        doThrow(new RuntimeException("channel error 406")).when(amqpAdmin)
            .declareQueue(argThat(queueNamed("zorrobpm.jobs.billing")));

        declarer.declare("billing");
        declarer.declare("billing");

        verify(amqpAdmin, times(2))
            .declareQueue(argThat(queueNamed("zorrobpm.jobs.billing")));
        assertThat(declarer.isLegacyDeclared("billing")).isFalse();
    }

    /**
     * Criterion 1/2 (visibility half): the legacy pin is observable — the
     * gauge {@code zbpm.jobs.legacy_queue} counts pinned job types.
     */
    @Test
    void legacyPin_isCountedInMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        declarer.setMeterRegistry(registry);
        failWorkQueueWith406();

        declarer.declare("billing");

        assertThat(declarer.isLegacyDeclared("billing")).isTrue();
        assertThat(declarer.legacyDeclaredQueueNames())
            .containsExactly("zorrobpm.jobs.billing");
        assertThat(registry.get(JobQueueDeclarer.LEGACY_QUEUE_METRIC).gauge().value())
            .isEqualTo(1.0);
    }

    /**
     * Detection table for {@code isPreconditionFailed}: structured 406
     * (reply-code, neutral text), textual PRECONDITION_FAILED, textual
     * "406 … inequivalent" → true; anything else → false.
     */
    @Test
    void preconditionDetection_table() {
        assertThat(JobQueueDeclarer.isPreconditionFailed(preconditionFailed("neutral channel close")))
            .as("structured 406 reply-code, no keywords in text").isTrue();
        assertThat(JobQueueDeclarer.isPreconditionFailed(
                new AmqpIOException("wrap", new java.io.IOException("PRECONDITION_FAILED - inequivalent arg"))))
            .as("textual PRECONDITION_FAILED").isTrue();
        assertThat(JobQueueDeclarer.isPreconditionFailed(
                new RuntimeException("channel error 406, inequivalent arg 'x-dead-letter-exchange'")))
            .as("textual 406 + inequivalent").isTrue();

        assertThat(JobQueueDeclarer.isPreconditionFailed(new RuntimeException("broker down")))
            .isFalse();
        assertThat(JobQueueDeclarer.isPreconditionFailed(new RuntimeException("channel error 406")))
            .as("bare 406 is not enough").isFalse();
        assertThat(JobQueueDeclarer.isPreconditionFailed(new RuntimeException()))
            .as("null message").isFalse();
    }

    /**
     * Criterion 4: queue names, DLQ names and routing keys are UNCHANGED —
     * already-deployed external workers keep working.
     */
    @Test
    void legacy406_namesAndRoutingKeysUnchanged() {
        assertThat(JobQueueDeclarer.queueNameFor("billing")).isEqualTo("zorrobpm.jobs.billing");
        assertThat(JobQueueDeclarer.dlqNameFor("billing")).isEqualTo("zorrobpm.jobs.billing.dlq");
        assertThat(JobQueueDeclarer.JOBS_DLX).isEqualTo("zorrobpm.jobs.dlx");
        assertThat(JobQueueDeclarer.LEGACY_QUEUE_METRIC).isEqualTo("zbpm.jobs.legacy_queue");
    }

    // -- helpers ----------------------------------------------------------

    /** The DLX/DLQ declares succeed, only the WORK queue redeclare 406s —
     *  exactly the pre-REL-45 broker state (DLX + DLQ are new names, the work
     *  queue name predates them). Structured 406 with deliberately neutral
     *  message text, so the test proves the reply-code path, not text grep. */
    private void failWorkQueueWith406() {
        doThrow(preconditionFailed("neutral channel close")).when(amqpAdmin)
            .declareQueue(argThat(queueNamed("zorrobpm.jobs.billing")));
    }

    private static ArgumentMatcher<Queue> queueNamed(String name) {
        return q -> q != null && name.equals(q.getName());
    }

    private static AmqpIOException preconditionFailed(String neutralText) {
        AMQP.Channel.Close close = new AMQP.Channel.Close() {
            @Override public int getReplyCode() { return 406; }
            @Override public String getReplyText() { return "PRECONDITION_FAILED"; }
            @Override public int getClassId() { return 50; }
            @Override public int getMethodId() { return 10; }
            @Override public int protocolClassId() { return 50; }
            @Override public int protocolMethodId() { return 10; }
            @Override public String protocolMethodName() { return "queue.declare"; }
        };
        ShutdownSignalException sse = new ShutdownSignalException(false, false, close, null);
        return new AmqpIOException(neutralText, sse);
    }
}
