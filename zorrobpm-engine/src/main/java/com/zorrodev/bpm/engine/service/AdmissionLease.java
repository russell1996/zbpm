package com.zorrodev.bpm.engine.service;

/**
 * WO-ENG-35 (NEW2-16): лиза на зарезервированное место в script-пуле.
 *
 * <p>Выдаётся {@link ScriptService#admitOutsideTx()} ДО открытия транзакции
 * вызывающего и держится всё время операции. Закрытие ({@link #close()},
 * {@code try}-with-resources) возвращает слот в пул. Двойное закрытие
 * безопасно (второе — no-op). Не-владелец (реентрантный вход того же потока)
 * получает no-op-лизу, чьё закрытие ничего не освобождает.
 */
public interface AdmissionLease extends AutoCloseable {

    /**
     * Возвращает слот в пул. Идемпотентно: повторный вызов — no-op.
     * Не бросает исключений.
     */
    @Override
    void close();

    /** No-op лиза для реентрантного входа (слот держит внешний владелец). */
    static AdmissionLease noop() {
        return () -> {
        };
    }
}
