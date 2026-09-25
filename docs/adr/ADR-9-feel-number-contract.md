# ADR-9 — Числовой контракт FEEL↔Java: decimal = `java.math.BigDecimal` везде внутри рантайма

- **Статус:** Accepted (2026-09-25, WO-ENG-25).
- **Контекст-владелец:** CTO, по находкам повторного аудита NEW-05+NEW-06.

## Контекст

Два пути одного языка давали разные Java-типы для одного числового FEEL-выражения:
script-FEEL (JSR-223, `ScriptServiceImpl`) — `scala.math.BigDecimal`;
DMN/io-mapping (`forJava` из `DmnEngineConfig`) — `Double`. Оба тихо меняли
бизнес-значение (класс N10 «тихо меняет бизнес-значение», закрыт в WO-ENG-21
для одного пути):

- script-путь: `scala.math.BigDecimal` не узнавался `BigDecimal`-спецветкой
  `toProcessVariable` и шёл в double-детур — `12345678901234567.89` сохранялось
  как `12345678901234568`, дробное ≥2⁵³ ложно считалось «целым» (`isIntegral`
  через `double`) и падало с ложным "outside LONG range";
- DMN-путь: `DmnServiceImpl.aggregate` считал в `double` — COLLECT SUM
  `['0.1','0.2']` сохранялось как `0.30000000000000004`.

WO ставил вопрос масштаба: точечный фикс двух мест или «числа = BigDecimal
везде» с широкой правкой value-mapper'а (дефолт — ADR-путь).

## Решение

Принят **принцип ADR с точечной реализацией** (а не широкая правка):

1. **Принцип:** внутри рантайма decimal = `java.math.BigDecimal`, `double` —
   никогда для денег/сумм. Граница FEEL→Java нормализует `scala.math.BigDecimal`
   → `java.math.BigDecimal` (`.bigDecimal()`, точная конвертация) ДО любых
   проверок; `isIntegral` для BigDecimal-подобного — через
   `stripTrailingZeros().scale() <= 0`, не через `double`; агрегаты SUM/MIN/MAX —
   в `BigDecimal` (конвертация через точное десятичное представление
   `Number.toString()`, не binary value).
2. **Почему точечной достаточно** (проверено чтением): «два независимых маппера»
   сходятся в одном `toProcessVariable` — результаты и `dmnService.evaluate`, и
   `scriptService.evaluateExpression` уходят в `elementSupport.toProcessVariable`
   (`SyncTaskHandler`). Второй «маппер» — это `DmnServiceImpl.aggregate`
   (арифметика, не маппинг). Широко править нечего — две точки под одним
   принципом. DMN null-семантика: null-выходы пропускаются, агрегат пустого
   множества — null (не `orElse(0)`); неизвестная агрегация — `EngineException`
   (паттерн WO-ENG-22 для hitPolicy).
3. **Формат хранения не меняется** (п.4 WO): LONG/DOUBLE-строки прежние; DOUBLE
   становится только точнее (`toPlainString` без double-детура). Уже сохранённые
   данные валидны, миграций нет. COUNT хранится LONG тем же путём (раньше через
   `(double) size`, теперь напрямую).

## Честный residual

Одиночные DMN-числа остаются Double-точности — это ограничение самого
forJava-движка (`DmnEngineConfig`), не нашего кода; агрегаты считаются точно
(конвертация через `toString` восстанавливает исходный литерал:
`Double(0.1).toString() == "0.1"`). Полное «BigDecimal из движка» потребовало бы
смены/обёртки feel-движка — вне масштаба этого WO.

## Последствия

- `ElementSupport.normalizeFeelNumber` + scala-ветка `isIntegral` —
  единственная граница нормализации; новые FEEL-пути обязаны идти через
  `toProcessVariable`, а не дублировать конвертацию (P-24).
- `DmnServiceImpl.aggregate` — арифметика только в `BigDecimal`; новые
  агрегации — в тот же switch с явным `EngineException` на неизвестное.
- Тесты: `FeelNumberContractTest` (5) — контракт; `FeelNumericOverflowTest` (N10)
  и `DmnEvaluationIntegrationTests` (включая старый collect-sum с целыми) —
  регресс.
