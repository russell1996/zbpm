package com.zorrodev.bpm.engine.scenario;

/**
 * WO-OPS-28 ФАЗА 2: сценарий не дошёл до точки наблюдения.
 *
 * <p>Это НЕ сбой прогона, а наблюдаемый факт: процесс остановился раньше
 * наблюдательной задачи — инцидент на шлюзе, зависший join (не пришла вторая
 * ветвь) или не взятая ветвь. Отдельный тип — чтобы раннер отличал такой
 * исход от ошибки прогона (в отчёте это отдельный статус, а не «тест упал»).
 */
public final class NoDumpReached extends RuntimeException {

    public NoDumpReached(String message) {
        super(message);
    }
}
