package com.zorrodev.bpm.engine.scenario;

import java.util.Arrays;
import java.util.List;

/**
 * WO-OPS-28 ФАЗА 2: одно действие сценария «потоки» (F2).
 *
 * <p>Сценарий среза F1 (WO-OPS-27) водится по MI-инстансам (сверка по значению
 * {@code item} в скоупе инстанса). Для ФАЗЫ 2 этого мало: там ветвление задаёт
 * НЕ коллекция инстансов, а маршрут по шлюзу — и сценарий должен уметь
 * (а) завершить задачу ВЫБРАННОЙ ветви, (б) опубликовать сообщение, чтобы
 * сработал event-based gateway, (в) НЕ завершить вторую ветвь (проверка
 * join-семантики «жди остальные»).
 *
 * <p>Действия — единый для обоих движков сценарий: и наш раннер, и оракул Zeebe
 * выполняют ОДИН И ТОТ ЖЕ список в ОДИНОМ И ТОТ ЖЕ порядке, поэтому сравнение
 * остаётся честным (та же схема, те же значения, тот же порядок действий).
 *
 * @param kind     вид действия: {@link #COMPLETE} или {@link #PUBLISH}
 * @param target   для {@link #COMPLETE} — список id элементов (через запятую),
 *                 любая из живых задач с таким id завершается; для {@link #PUBLISH} —
 *                 имя сообщения
 * @param varsJson JSON-объект переменных, которые записываются при действии
 *                 (для {@link #COMPLETE} — результат «воркера», для
 *                 {@link #PUBLISH} — переменные сообщения)
 * @param correlationKey ключ корреляции публикуемого сообщения (только для
 *                 {@link #PUBLISH}). В Zeebe 8.5 определение сообщения обязано
 *                 нести {@code zeebe:subscription correlationKey=...}, и публикация
 *                 подписывается на ЭТОТ ключ — поэтому ключ задаётся в действии и
 *                 у нас, и в оракуле.
 */
public record ScenarioAction(String kind, String target, String varsJson, String correlationKey) {

    /** Завершить живую задачу с указанным id (все её экземпляры). */
    public static final String COMPLETE = "complete";
    /** Опубликовать сообщение (будит подписку, в т.ч. event-based gateway). */
    public static final String PUBLISH = "publish";

    /** Действие без ключа корреляции (завершение задачи). */
    public ScenarioAction(String kind, String target, String varsJson) {
        this(kind, target, varsJson, null);
    }

    /** id элементов действия (пусто — действие не нацелено на элементы). */
    public List<String> elementIds() {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        return Arrays.stream(target.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
