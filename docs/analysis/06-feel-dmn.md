# 06 — FEEL & DMN (as-is)

## FEEL
**`ScriptServiceImpl`** — два JSR-223 `ScriptEngine` (Camunda feel-engine, экспонирован как `javax.script.ScriptEngine`):
- `feelScriptEngine` — `evaluateScript` (unary-tests/булевы условия потоков и шлюзов);
- `feelExpressionScriptEngine` — `evaluateExpression` (выражения IO-mapping, script task, correlation-key).

Переменные подаются как `ScriptContext` биндинги; результат конвертируется обратно в `ProcessVariable`
(через Jackson `ObjectMapper` для JSON). Используется в: условных потоках (`processFlow` :2012,
`isFlowActive` :886), IO-mapping (:1226), script task, correlation-key (:477), conditional events (:511).

- **P5.** FEEL — это Camunda-движок (а не самописный), что повышает совместимость семантики.
- **SEC6.** FEEL/script-выражения берутся **из модели процесса**. При недоверенных моделях это поверхность
  исполнения произвольных выражений. Camunda FEEL не Turing-complete и относительно безопасен, но
  inline `<bpmn:script>` и расширения требуют внимания к источнику моделей (кто может деплоить).

## DMN
**`DmnServiceImpl`** [service/impl/DmnServiceImpl.java] — собственный DMN-движок (не Zeebe DMN):
- Парсит DMN-таблицу; input-entries — FEEL unary-tests, input/output-expressions — FEEL.
- Модель C8-нативная: `zeebe:calledDecision decisionId/resultVariable` на business-rule task.
- Версионирование решений (`dmn_definitions.version`, changeset 039).
- REST: `DmnContract` (`GET /dmn`, `GET /dmn/{id}`, `POST /dmn/{id}/evaluate`).

### Hit policies — **M1/T6**
`evaluate` [:94-100]: `hitPolicy` читается, но цикл делает **`break` на первом совпадении для ЛЮБОЙ политики**:
```
String hitPolicy = table.getHitPolicy()==null ? "UNIQUE" : ...;   // :94
... if (matches) { matched = rule; break; }                        // :98-100  «UNIQUE/FIRST/ANY → первого достаточно»
```
- ✅ Корректны: **UNIQUE, FIRST, ANY** (одиночный результат).
- ❌ Не реализованы: **COLLECT** (все совпадения/агрегация SUM/MIN/MAX/COUNT), **PRIORITY**, **RULE ORDER**,
  **OUTPUT ORDER**. Нет валидации UNIQUE (несколько совпадений молча берёт первое) и ANY (должны совпадать выходы).
- **Acceptance для T6:** матрица тестов по политикам против ожидаемого набора правил (оракул — спецификация DMN),
  каждый тест красный до ветвления по `hitPolicy`.

## Сводно
FEEL — сильная сторона (настоящий Camunda-движок). DMN покрывает массовый кейс (UNIQUE/FIRST/ANY), но
**неполон по hit policies** — главный функциональный гэп слоя (T6), плюс вопрос доверия к источнику моделей (SEC6).
