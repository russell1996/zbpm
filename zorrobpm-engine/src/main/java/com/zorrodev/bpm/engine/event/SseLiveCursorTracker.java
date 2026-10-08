package com.zorrodev.bpm.engine.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WO-AUDIT-7: in-memory трекер курсоров живых SSE-подписчиков.
 *
 * <p>Проблема: сервер курсоры подписчиков не хранит (SSE stateless —
 * {@code Last-Event-ID} приходит от клиента на каждый reconnect, см.
 * {@code SseEventStreamController}). Retention, удаляющий старые строки
 * {@code events}, без знания о живых курсорах гоняется с catchup-чтением:
 * проход удаляет строки, которые подписчик с курсором внутри окна удаления
 * ещё не дочитал, — подписчик получает дыру, которую catchup уже не
 * вылечит (строк нет в БД).
 *
 * <p>Решение — транзитный пин: retention не удаляет назначенные
 * ({@code feed_position IS NOT NULL}) строки с позицией ВЫШЕ минимального
 * активного курсора. Пин НЕ вечный: renew/staleness не нужны, потому что время
 * жизни сессии ограничено таймаутом эмиттера
 * ({@code zorrobpm.sse.emitter-timeout-ms}, 30 минут по умолчанию) — запись
 * снимается в {@code removeClientState} при любом закрытии, а F-13
 * (SEC-67) закрывает протухшие credential'ы принудительно. Худший эффект
 * залипшего пина — задержка удаления полосы на время сессии, никогда —
 * удаление чужого.
 *
 * <p>Fail-safe направление: утечка записи (нет remove) = пин держится =
 * удаление откладывается, а не удаляет лишнее. Пустой трекер = пина нет
 * (retention работает как раньше).
 *
 * <p>Потокобезопасность: plain {@link ConcurrentHashMap}, курсор клиента
 * монотонно растёт (merge по max). Чтение минимума — слабо-согласованный
 * обход значений: гонка в пределах одного прохода retention допустима
 * (следующий проход доберёт).
 */
@Slf4j
@Component
public class SseLiveCursorTracker {

    /** clientId → наибольшая позиция, которую клиент точно видел (since/drain/live). */
    private final Map<String, Long> cursors = new ConcurrentHashMap<>();

    /**
     * Зарегистрировать курсор catchup'а клиента (вызывает REST при старте
     * catchup-чтения — ДО чтения, чтобы закрыть окно register→read).
     * {@code since <= 0} (нет заголовка / мусор) — не трекается: такому
     * клиенту нужны только новые строки, старые ему не нужны.
     */
    public void track(String clientId, long since) {
        if (clientId == null || since <= 0) {
            return;
        }
        cursors.merge(clientId, since, Math::max);
    }

    /**
     * Продвинуть курсор клиента (drain-граница catchup'а, live-рассылка).
     * No-op для неизвестного clientId (plain-регистрации без since не
     * трекаются — их нечего отпускать).
     */
    public void advance(String clientId, long cursor) {
        if (clientId == null || cursor <= 0) {
            return;
        }
        cursors.computeIfPresent(clientId, (id, prev) -> Math.max(prev, cursor));
    }

    /** Снять клиента (единая точка снятия SSE-состояния). */
    public void untrack(String clientId) {
        if (clientId != null) {
            cursors.remove(clientId);
        }
    }

    /**
     * Минимальный активный курсор — нижняя граница пина retention.
     * Пусто = живых трекаемых подписчиков нет = пина нет.
     */
    public OptionalLong minActiveCursor() {
        return cursors.values().stream().mapToLong(Long::longValue).min();
    }

    /** Число трекаемых клиентов (наблюдаемость, тесты). */
    public int trackedCount() {
        return cursors.size();
    }
}
