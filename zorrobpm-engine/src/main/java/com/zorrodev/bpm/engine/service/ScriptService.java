package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.event.model.VariableModel;

import java.util.List;

public interface ScriptService {

    Object evaluateScript(String script, List<ProcessVariable> variables);

    /** Evaluates a full FEEL expression (any return value), unlike {@link #evaluateScript} which runs a unary test. */
    Object evaluateExpression(String expression, List<ProcessVariable> variables);

    /**
     * WO-ENG-20: выполняет произвольный {@code task} в ОБЩЕМ script-пуле под тем же
     * timeout/bulkhead/метриками, что script task'и (механизм WO-A-02 + WO-REL-46).
     * Единственный способ дать лимиты FEEL-вызовам, которые нельзя выразить через
     * {@code evaluateScript}/{@code evaluateExpression} (DMN unary-тесты с явным input,
     * прямые {@code evaluateExpression} с Map-переменными): сам вызов движка остаётся
     * прежним, меняется только envelope исполнения (поток пула + timeout).
     * Второго пула нет — это и есть «тот же механизм», а не второй.
     *
     * @param task задача (обычно прямой вызов общего {@code FeelEngineApi}-синглтона)
     * @param codeRef безопасная ссылка для логов/ошибок (длина, не текст — A-09)
     * @throws com.zorrodev.bpm.contract.exception.EngineException при timeout,
     *         переполнении пула или прерывании; исключение самой задачи (включая
     *         {@code EngineException}) проходит наружу как есть
     */
    Object runWithBudget(java.util.concurrent.Callable<Object> task, String codeRef);

    /**
     * WO-ENG-35 (NEW2-16): резервирует место в script-пуле ДО открытия
     * транзакции вызывающего. Вызывающий (REST-вход, AMQP-слушатель, fire
     * таймера) держит возвращённую лизу всё время операции ({@code try}-with-resources);
     * пока лиза открыта, все {@code evaluate*}/{@code runWithBudget} этого потока
     * идут в пул напрямую, не ожидая admission внутри транзакции.
     *
     * <p>Вызывать ВНЕ транзакции. Вызов внутри транзакции НЕ падает, а
     * деградирует к прежнему поведению (ограниченное ожидание внутри +
     * счётчик {@code zbpm.script.admission.in_tx} + WARN): fail-fast здесь
     * был бы хуже болезни — легитимный путь запроса с
     * {@code Idempotency-Key} идёт через {@code IdempotencyFilter}, который
     * выполняет цепочку (включая контроллер) внутри своей транзакции, и
     * жёсткий отказ превращал бы его в 500. Вынос гейта до фильтра —
     * отдельная эскалация (фильтр в {@code rest/security}, рядом с G-C);
     * до неё такие пути считаются и видны оператору, а не ломаются.
     *
     * <p>Таймаут ожидания — то же окно {@code script-queue-wait-seconds}, отказ —
     * тот же {@link com.zorrodev.bpm.engine.service.ScriptOverloadException}
     * (503), что и у внутритранзакционного пути: видимое поведение при
     * перегрузке не меняется, меняется только то, ЧТО удерживается во время
     * ожидания (ничего вместо транзакции+соединения).
     *
     * @return лиза; закрыть в {@code finally} (иначе слот утечёт —
     *         пермиты конечны, утечка видна как рост отказов до перезапуска)
     * @throws com.zorrodev.bpm.engine.service.ScriptOverloadException если место
     *         не освободилось за окно ожидания
     */
    AdmissionLease admitOutsideTx();
}
