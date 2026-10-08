package com.zorrodev.bpm.rabbitmq.configuration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-68: табличный тест на КЛАСС последовательностей кадров return/ack,
 * а не штучные кейсы. Гоняет реальный прод-класс
 * {@link UnroutableConfirmSuppressor} (G-N), брокер не нужен — кадры подаются
 * вызовами методов в тех же порядках, в каких их шлёт брокер.
 *
 * <p>Инвариант каждого кейса: число suppressed == числу return'ов (каждый
 * return съедает ровно один будущий {@code ack=true}), лишний {@code ack=true}
 * без долга — публикуется (suppress=false), карта в конце пуста (нет утечки).
 */
class UnroutableConfirmSuppressorTest {

    /** Одна последовательность кадров: 'R' = return, 'A' = confirm ack=true, 'N' = nack. */
    private record Frames(String frames, int expectedSuppressions) {}

    @Test
    void frameSequences_eachReturnSuppressesExactlyOneAck() {
        List<Frames> cases = List.of(
            // Одиночная недоставленная (паритет с дофиксным Set): R,A → 1 suppress.
            new Frames("RA", 1),
            // Чередование (красная команда REL-66-fix доказала: утечки нет и на Set).
            new Frames("RARA", 2),
            // Наложение WO-REL-68: R,R,A,A — Set давал 1 suppress + 1 ложный ACK.
            new Frames("RRAA", 2),
            // Тройное наложение.
            new Frames("RRRAAA", 3),
            // Настоящий ACK без return'а — публикуется (0 suppress).
            new Frames("A", 0),
            // Два настоящих ACK подряд.
            new Frames("AA", 0),
            // Наложение + настоящий ACK после (долг съеден — публикуется).
            new Frames("RRAAA", 2),
            // Настоящий ACK, затем недоставленная.
            new Frames("ARA", 1),
            // Nack снимает долг: R,N,A → ack публикуется.
            new Frames("RNA", 0),
            // Nack без долга — no-op, ack публикуется.
            new Frames("NA", 0),
            // Два долга, один nack сносит всё (консервативно, паритет со Set.remove).
            new Frames("RRNA", 0),
            // Длинное наложение 10×.
            new Frames("RRRRRRRRRRAAAAAAAAAA", 10)
        );

        for (Frames c : cases) {
            UnroutableConfirmSuppressor suppressor = new UnroutableConfirmSuppressor();
            int suppressed = 0;
            String id = "probe-" + c.frames();
            for (char frame : c.frames().toCharArray()) {
                switch (frame) {
                    case 'R' -> suppressor.noteReturn(id, 1000L);
                    case 'A' -> {
                        if (suppressor.shouldSuppressAck(id, 1000L)) {
                            suppressed++;
                        }
                    }
                    case 'N' -> suppressor.noteNack(id);
                    default -> throw new IllegalStateException("frame " + frame);
                }
            }
            assertThat(suppressed)
                .as("кадры %s: подавлено обязано равняться числу return'ов", c.frames())
                .isEqualTo(c.expectedSuppressions());
            assertThat(suppressor.trackedIdsForTest())
                .as("кадры %s: долгов не осталось (нет утечки)", c.frames())
                .isZero();
        }
    }

    @Test
    void differentIds_areIndependent() {
        UnroutableConfirmSuppressor suppressor = new UnroutableConfirmSuppressor();
        suppressor.noteReturn("id-a", 1000L);
        suppressor.noteReturn("id-a", 1000L);
        suppressor.noteReturn("id-b", 1000L);

        assertThat(suppressor.shouldSuppressAck("id-a", 1000L)).isTrue();
        assertThat(suppressor.shouldSuppressAck("id-a", 1000L)).isTrue();
        assertThat(suppressor.shouldSuppressAck("id-a", 1000L)).isFalse();
        assertThat(suppressor.shouldSuppressAck("id-b", 1000L)).isTrue();
        assertThat(suppressor.shouldSuppressAck("id-b", 1000L)).isFalse();
        assertThat(suppressor.trackedIdsForTest()).isZero();
    }

    @Test
    void staleDebt_isEvictedAndLateAckIsPublished() {
        UnroutableConfirmSuppressor suppressor = new UnroutableConfirmSuppressor();
        suppressor.noteReturn("id", 1000L);

        // Свежий долг — подавляет.
        assertThat(suppressor.shouldSuppressAck("id",
            1000L + UnroutableConfirmSuppressor.STALE_AFTER_MILLIS - 1)).isTrue();

        // Новый долг того же id, confirm опаздывает за TTL — долг мёртв,
        // ack публикуется (остаточный риск из матрицы: мёртвый connection
        // confirm'ов не шлёт вовсе).
        suppressor.noteReturn("id", 2000L);
        assertThat(suppressor.shouldSuppressAck("id",
            2000L + UnroutableConfirmSuppressor.STALE_AFTER_MILLIS + 1)).isFalse();
        assertThat(suppressor.trackedIdsForTest()).isZero();
    }

    @Test
    void staleDebt_isEvictedEvenWithoutAck() {
        UnroutableConfirmSuppressor suppressor = new UnroutableConfirmSuppressor();
        suppressor.noteReturn("orphan", 1000L);
        assertThat(suppressor.trackedIdsForTest()).isEqualTo(1);

        // Любой следующий вызов чистит протухшее (ленивый evict — отдельного
        // потока/таймера нет); свежая запись при этом живёт и подавляет.
        suppressor.noteReturn("other", 1000L + UnroutableConfirmSuppressor.STALE_AFTER_MILLIS + 1);
        assertThat(suppressor.trackedIdsForTest()).isEqualTo(1);
        assertThat(suppressor.shouldSuppressAck("other",
            1000L + UnroutableConfirmSuppressor.STALE_AFTER_MILLIS + 1)).isTrue();
        assertThat(suppressor.trackedIdsForTest()).isZero();
    }

    @Test
    void concurrentReturnAckPairs_allSuppressedAndNothingLeaks() throws Exception {
        UnroutableConfirmSuppressor suppressor = new UnroutableConfirmSuppressor();
        int threads = 8;
        int pairsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger suppressed = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < pairsPerThread; i++) {
                        suppressor.noteReturn("shared-id", System.currentTimeMillis());
                        if (suppressor.shouldSuppressAck("shared-id", System.currentTimeMillis())) {
                            suppressed.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(errors).isEmpty();
        // Каждый return спарен с ack: суммарно suppressed == числу return'ов,
        // карта пуста. (Перемешивание может временно подавлять чужой ack —
        // инвариант суммарный, fail-closed: потери нет ни при каком порядке.)
        assertThat(suppressed.get()).isEqualTo(threads * pairsPerThread);
        assertThat(suppressor.trackedIdsForTest()).isZero();
    }
}
