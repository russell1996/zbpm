package com.zorrodev.bpm.handler.boot;

/**
 * WO-INT-10: топология публикации completion-результатов воркера.
 *
 * <p>До WO воркер публиковал через default exchange («голый»
 * {@code convertAndSend(queue, …)}): брокер проверяет write-право против имени
 * exchange ({@code amq.default}), и выдать per-system воркеру write на
 * {@code amq.default} означало бы право писать в ЛЮБУЮ очередь тем же путём
 * (write матчит exchange, не routing key — доказано живым прогоном: воркер с
 * write на {@code amq.default} доставил поддельное сообщение в чужую
 * job-очередь). Поэтому completion'ы идут через ВЫДЕЛЕННЫЙ direct exchange —
 * write-право на него даёт ровно публикацию, а выбор очереди остаётся за
 * биндингами, которые объявляет движок.
 *
 * <p>Маршрутизация — identity: routing key равен имени очереди
 * ({@code JobCompletionListener.COMPLETE_QUEUE},
 * {@code CompletionPoisonRetryListener.POISON_QUEUE/RETRY_DELAY_QUEUE}).
 * Имя exchange дублируется строкой в engine-side
 * {@code RabbitConfiguration.COMPLETIONS_EXCHANGE} (стартер не зависит от
 * {@code zorrobpm-rabbitmq} в compile-scope) — расхождение ловят пин-тесты с
 * обеих сторон.
 */
final class CompletionTopology {

    /** Выделенный exchange публикаций completion (direct, durable). */
    static final String COMPLETION_EXCHANGE = "zorrobpm.completions";

    private CompletionTopology() {
    }
}
