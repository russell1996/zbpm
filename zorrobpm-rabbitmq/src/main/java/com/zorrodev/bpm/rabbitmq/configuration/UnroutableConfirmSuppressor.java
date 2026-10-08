package com.zorrodev.bpm.rabbitmq.configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WO-REL-68: подавляет ложный {@code ack=true} после unroutable-возврата
 * СЧЁТЧИКОМ, а не множеством.
 *
 * <p>Предпосылка брокера (mandatory + correlated confirms): каждая отправка
 * даёт ровно один confirm и 0..1 return. Недоставленная (NO_ROUTE) отправка
 * даёт пару {@code (return, confirm ack=true)} — return приходит первым, а
 * {@code ack=true} означает лишь «брокер принял сообщение на exchange», НЕ
 * «доставил». Без подавления такой confirm помечает недоставленную строку
 * outbox {@code published} (тихая потеря, класс P-15 — CI job 560691).
 *
 * <p>Прежний механизм ({@code Set<String> returnedIds} в
 * {@code RabbitConfiguration}) ключевал подавление одним лишь outbox-id: при
 * наложившихся отправках одного id (два publish'а до прихода delivery-result)
 * кадры идут {@code return,return,confirm,confirm} — второй {@code add} в Set
 * схлопывается, первый confirm снимает id, второй {@code ack=true} уходит без
 * подавления. Счётчик держит число возвратов без парного confirm'а: каждый
 * return — +1, каждый {@code ack=true} при долге &gt; 0 — suppress и −1.
 * K наложенных пар дают K suppress'ов и ноль опубликованных ACK — независимо
 * от порядка кадров.
 *
 * <p>Остальные кадры (паритет с прежним поведением):
 * <ul>
 *   <li>{@code ack=true} без долга — публикуется как раньше (настоящий ACK);
 *       долга нет, декремента ниже нуля нет (записи нет — вычитать нечего).</li>
 *   <li>nack — долг снимается полностью (как прежний {@code remove} в
 *       {@code !ack}-ветке). nack с предшествующим return не бывает: пара
 *       unroutable — это {@code (return, ack=true)}, а не nack.</li>
 * </ul>
 *
 * <p>Память: запись удаляется при нуле (тот же вызов, что съел последний
 * долг). Долг без парного confirm'а (confirm потерян — connection/канал умер
 * раньше) сносится ленивым TTL ({@link #STALE_AFTER_MILLIS}): живой confirm
 * опаздывает на миллисекунды, не на минуты. Остаточный риск — confirm,
 * опоздавший больше чем на TTL, не подавится; мёртвый connection confirm'ов
 * не шлёт вовсе, так что практически это недостижимо (см. матрицу в отчёте
 * WO-REL-68). Методы принимают {@code nowMillis} явно — TTL детерминированно
 * тестируется без sleep.
 *
 * <p>Fail-closed отклонения (потеря невозможна, возможна лишняя доставка —
 * at-least-once это допускает, консьюмеры идемпотентны по messageId=outboxId):
 * настоящий ACK без return'а, пришедший при живом чужом долге (парный confirm
 * долга потерян), съест чужой долг вместо публикации — строка уйдёт на
 * повторную доставку, но не потеряется.
 *
 * <p>Потокобезопасность: return/confirm приходят с разных потоков connection
 * factory; все мутации одного id — через атомарный {@code compute} на ключе.
 */
class UnroutableConfirmSuppressor {

    /**
     * Возраст долга, после которого он считается мёртвым (парный confirm не
     * придёт — connection умер) и сносится при следующем вызове. Живые долги
     * поглощаются за миллисекунды; 10 минут — консервативно выше любого живого
     * окна (outbox poll — секунды, ретрай-цикл — минуты).
     */
    static final long STALE_AFTER_MILLIS = 10 * 60 * 1000L;

    private static final class Debt {
        final AtomicInteger outstanding = new AtomicInteger(1);
        volatile long lastUpdateMillis;

        Debt(long nowMillis) {
            this.lastUpdateMillis = nowMillis;
        }
    }

    private final ConcurrentHashMap<String, Debt> debts = new ConcurrentHashMap<>();

    /**
     * Брокер вернул сообщение как unroutable: +1 долг и запомнить время.
     * Вызывается ДО публикации failure-события (порядок как у прежнего
     * {@code add} — долг виден confirm'у сразу).
     */
    void noteReturn(String id, long nowMillis) {
        evictStale(nowMillis);
        debts.compute(id, (key, debt) -> {
            if (debt == null) {
                return new Debt(nowMillis);
            }
            debt.outstanding.incrementAndGet();
            debt.lastUpdateMillis = nowMillis;
            return debt;
        });
    }

    /**
     * Confirm {@code ack=true}: {@code true} — подавить (ложный, парный к
     * ранее учтённому return; долг −1, при нуле запись удаляется), {@code false}
     * — публиковать как настоящий ACK (долга по id нет).
     */
    boolean shouldSuppressAck(String id, long nowMillis) {
        evictStale(nowMillis);
        AtomicBoolean suppressed = new AtomicBoolean(false);
        debts.compute(id, (key, debt) -> {
            if (debt == null) {
                return null;
            }
            suppressed.set(true);
            debt.lastUpdateMillis = nowMillis;
            return debt.outstanding.decrementAndGet() > 0 ? debt : null;
        });
        return suppressed.get();
    }

    /**
     * Nack: снять долг полностью (паритет с прежним {@code remove} в
     * {@code !ack}-ветке). Nack без предшествующего return — no-op.
     */
    void noteNack(String id) {
        debts.remove(id);
    }

    /** Число id с живым долгом — для тестов отсутствия утечек. */
    int trackedIdsForTest() {
        return debts.size();
    }

    private void evictStale(long nowMillis) {
        debts.entrySet().removeIf(entry -> nowMillis - entry.getValue().lastUpdateMillis > STALE_AFTER_MILLIS);
    }
}
