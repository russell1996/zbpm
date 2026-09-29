package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WO-AUDIT-5 item 3: the resolved map must never be visible half-filled.
 *
 * Deterministic (no sleep-racing): a controllable provider stream blocks the
 * resolving thread MID-FILL (after the 1st put, before the 2nd). While blocked,
 * the test reads the published field directly — the racy read the audit warns
 * about. New code builds locally → field still empty; old code (flag set before
 * the loop, puts straight into the field) would already show 1 entry.
 */
class HandlerRegistryConcurrencyTest {

    private static TypedElementHandler stub(BpmnElementType type) {
        TypedElementHandler h = mock(TypedElementHandler.class);
        when(h.elementType()).thenReturn(type);
        ElementHandler impl = mock(ElementHandler.class);
        when(h.handler()).thenReturn(impl);
        return h;
    }

    @Test
    void resolveBeanHandlers_neverPublishesPartialMap() throws Exception {
        TypedElementHandler first = stub(BpmnElementType.START_EVENT);
        // Second handler's elementType() is evaluated INSIDE the fill loop, after the
        // first put: block there, so the resolving thread is provably mid-fill.
        CountDownLatch midFill = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TypedElementHandler second = mock(TypedElementHandler.class);
        ElementHandler secondImpl = mock(ElementHandler.class);
        when(second.handler()).thenReturn(secondImpl);
        when(second.elementType()).thenAnswer(inv -> {
            midFill.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
            return BpmnElementType.END_EVENT;
        });
        ObjectProvider<TypedElementHandler> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(java.util.stream.Stream.of(first, second));

        HandlerRegistry registry = new HandlerRegistry(provider);
        AtomicBoolean getReturned = new AtomicBoolean(false);
        Thread resolver = new Thread(() -> {
            registry.get(BpmnElementType.START_EVENT);
            getReturned.set(true);
        });
        resolver.start();

        assertThat(midFill.await(10, TimeUnit.SECONDS))
            .as("resolver must reach mid-fill").isTrue();
        try {
            // The racy read: field MUST still be the pristine empty map, never partial.
            assertThat(registry.getHandlers())
                .as("no half-filled map may ever be published")
                .isEmpty();
        } finally {
            release.countDown();
        }
        resolver.join(10_000);

        // After release, full resolution happened exactly once.
        assertThat(getReturned).as("resolver thread finished").isTrue();
        assertThat(registry.get(BpmnElementType.START_EVENT)).isSameAs(first.handler());
        assertThat(registry.get(BpmnElementType.END_EVENT)).isSameAs(secondImpl);
    }

    @Test
    void concurrentGet_resolvesOnceAndServesAll() throws Exception {
        TypedElementHandler first = stub(BpmnElementType.START_EVENT);
        TypedElementHandler second = stub(BpmnElementType.END_EVENT);
        ObjectProvider<TypedElementHandler> provider = mock(ObjectProvider.class);
        // Fresh stream per call — 8 threads each trigger resolution (only the first
        // one fills; the rest short-circuit on the flag).
        when(provider.orderedStream()).thenAnswer(inv -> java.util.stream.Stream.of(first, second));

        HandlerRegistry registry = new HandlerRegistry(provider);
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    assertThat(registry.get(BpmnElementType.START_EVENT)).isSameAs(first.handler());
                    assertThat(registry.get(BpmnElementType.END_EVENT)).isSameAs(second.handler());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("all readers finished").isTrue();
        assertThat(errors).as("no reader saw a broken registry: %s", errors).isEmpty();
    }

    /**
     * WO-QW-1 C-2: concurrent register() during lazy resolution must not corrupt
     * the map (the old EnumMap.put raced a concurrent get — torn iterator state
     * or lost entries). 4 writers × 4 readers hammering one registry, then every
     * key still resolves to the last-written handler.
     */
    @Test
    void concurrentRegisterAndGet_neverCorruptsMap() throws Exception {
        TypedElementHandler base = stub(BpmnElementType.START_EVENT);
        ObjectProvider<TypedElementHandler> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> java.util.stream.Stream.of(base));

        HandlerRegistry registry = new HandlerRegistry(provider);
        ElementHandler w1 = mock(ElementHandler.class);
        ElementHandler w2 = mock(ElementHandler.class);
        int writers = 4;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + 4);
        List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < writers; i++) {
            final ElementHandler w = (i % 2 == 0) ? w1 : w2;
            new Thread(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    for (int j = 0; j < 500; j++) {
                        registry.register(BpmnElementType.USER_TASK, w);
                        registry.get(BpmnElementType.USER_TASK);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        for (int i = 0; i < 4; i++) {
            new Thread(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    for (int j = 0; j < 500; j++) {
                        registry.get(BpmnElementType.START_EVENT);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        assertThat(errors).as("no thread failed: %s", errors).isEmpty();
        assertThat(registry.get(BpmnElementType.USER_TASK)).isIn(w1, w2);
    }

    /**
     * WO-QW-1 C-2 structural pin: the published map must never be an EnumMap
     * again (its put/get race is the defect) — neither at construction nor after
     * lazy resolution.
     */
    @Test
    void publishedMap_isNeverEnumMap() {
        TypedElementHandler base = stub(BpmnElementType.START_EVENT);
        ObjectProvider<TypedElementHandler> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> java.util.stream.Stream.of(base));

        HandlerRegistry registry = new HandlerRegistry(provider);
        assertThat(registry.getHandlers()).isNotInstanceOf(java.util.EnumMap.class);
        registry.get(BpmnElementType.START_EVENT);
        assertThat(registry.getHandlers()).isNotInstanceOf(java.util.EnumMap.class);
    }
}
