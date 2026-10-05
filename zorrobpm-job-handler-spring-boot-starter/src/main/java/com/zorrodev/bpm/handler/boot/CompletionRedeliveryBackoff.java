package com.zorrodev.bpm.handler.boot;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * WO-C8-36 (M-1): ограниченный экспоненциальный backoff перед повторной
 * обработкой задания, результат которого не удалось опубликовать.
 *
 * <p>Что было: контейнер при исключении из listener'а делает NACK+requeue
 * немедленно, то есть при неминуемой проблеме доставки (completion уходит в
 * несуществующий routing key, или confirm не приходит вовсе) воркер крутит цикл
 * {@code send → confirm/return → throw → requeue} без единой задержки. До
 * CR-13 этот режим просто молча терял результат; после CR-13 (результат
 * возвращается, вход не ACK'ается) он стал бесконечной горячей переотправкой —
 * тот же цикл, но уже с двумя WARN на итерацию и с двумя счётчиками, растущими
 * без ограничения.
 *
 * <p>Почему backoff здесь, а не в контейнере: штатный механизм
 * {@code RetryInterceptorBuilder} требует {@code spring-retry}, которого нет в
 * classpath стартера (и тянуть новую зависимость в воркерский стартер —
 * заметно больше, чем здесь нужно). Задержка перед пробросом исключения
 * семантически РОВНО та же: контейнер не получает отказ сразу, а через
 * интервал, поэтому и requeue происходит с задержкой.
 *
 * <p>Пределы: старт 1с, шаг ×2, потолок 30с, повторов НЕ ограничено и
 * сообщение НЕ отбрасывается — это осознанно: единственная причина redelivery
 * здесь — не доставленный результат, а он сам по себе разбирается на стороне
 * движка (ретраи/инцидент), и «тихо выбросить задание» означало бы вернуть
 * ровно ту потерю, которую CR-13 закрыл.
 *
 * <p>Ключ счётчика попыток — {@code correlationId} входящего задания (он же
 * ключ идемпотентности результата): разные задания не замедляют друг друга.
 * Успешная публикация сбрасывает счётчик, иначе одно задание, у которого сначала
 * был немаршрутизируемый маршрут (долгий потолок), а потом всё починилось,
 * осталось бы навсегда на 30с.
 */
@Slf4j
class CompletionRedeliveryBackoff {

    /** Первая задержка перед повторной обработкой. */
    static final long INITIAL_DELAY_MS = 1_000L;
    /** Потолок: дальше растить бессмысленно — оператор всё равно чинит маршрут. */
    static final long MAX_DELAY_MS = 30_000L;
    /** Множитель экспоненты. */
    static final double MULTIPLIER = 2.0d;

    /** Задержка перед N-й попыткой (N=1 — первая). */
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final LongConsumer sleeper;

    CompletionRedeliveryBackoff(LongConsumer sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * Задерживает поток перед тем, как отказ уйдёт наружу (и воркер получит
     * requeue). Возвращает применённую задержку — для лога и тестов.
     */
    long awaitBeforeRedelivery(String key, int nthAttempt) {
        long delay = delayFor(nthAttempt);
        if (delay > 0) {
            sleeper.accept(delay);
        }
        return delay;
    }

    /** Регистрирует ещё одну неудачную попытку публикации для ключа. */
    int recordFailedAttempt(String key) {
        return attempts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /** Публикация удалась — ключ больше не задерживается. */
    void reset(String key) {
        attempts.remove(key);
    }

    /** Сколько неудачных попыток публикации было у ключа (0 — ни одной/сброшено). */
    int attemptsFor(String key) {
        AtomicInteger n = attempts.get(key);
        return n == null ? 0 : n.get();
    }

    /** Размер счётчика (тест-хук: не должен расти без ограничения). */
    int trackedKeysForTest() {
        return attempts.size();
    }

    /** Задержка для N-й попытки: 1с, 2с, 4с, 8с, 16с, 30с, 30с… */
    static long delayFor(int nthAttempt) {
        if (nthAttempt <= 1) {
            return INITIAL_DELAY_MS;
        }
        double delay = INITIAL_DELAY_MS * Math.pow(MULTIPLIER, nthAttempt - 1);
        return (long) Math.min(MAX_DELAY_MS, delay);
    }

    static Duration delayDurationFor(int nthAttempt) {
        return Duration.ofMillis(delayFor(nthAttempt));
    }
}
