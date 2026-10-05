package com.zorrodev.bpm.exchange;

/**
 * WO-C8-36 (CR-01): словарь фаз отправки service-task задания — идентификатор
 * конкретного вызова, который движок штампует в {@code JobDetailModel} при постановке
 * ({@code dispatchPhase} + {@code dispatchIndex}) и который воркер возвращает В
 * сообщении о завершении. Движок принимает результат ТОЛЬКО ожидаемого вызова
 * (exact-match с текущей фазой под row-локом), дубликаты/устаревшие игнорит.
 *
 * <p>Строки, а не enum: неизвестная будущей версии фаза обязана глохнуть
 * fail-closed (игнор), а не ронять десериализацию всего сообщения.
 */
public final class ServiceTaskDispatchPhase {

    private ServiceTaskDispatchPhase() {
    }

    /** Реальное задание (ни одна listener-фаза не открыта). Индекс всегда null. */
    public static final String REAL = "real";
    /** Start-listener; индекс — позиция в {@code startListeners} элемента. */
    public static final String START = "start";
    /** End-listener; индекс — позиция в {@code endListeners} элемента. */
    public static final String END = "end";
    /** Creating-listener user task; индекс — позиция в creating-слушателях. */
    public static final String CREATING = "creating";
    /** Completing-listener user task; индекс — позиция в completing-слушателях. */
    public static final String COMPLETING = "completing";
    /** Assigning-listener user task; индекс — позиция в assigning-слушателях. */
    public static final String ASSIGNING = "assigning";
    /** Updating-listener user task; индекс — позиция в updating-слушателях. */
    public static final String UPDATING = "updating";
    /** Canceling-listener user task; индекс — позиция в canceling-слушателях. */
    public static final String CANCELING = "canceling";
    /** Element-listener фаза (шлюзы/события, без activity-строки); индекс — позиция в фазе. */
    public static final String ELEMENT_START = "element_start";
}
