# WO-SEC-4 — Rate-limit на /auth/login     [P2 / HIGH]

## Цель
Предотвратить brute-force атаки на эндпоинт логина.

## Проблема (проверено по диску)
`AuthResource.java:24` — `login(@RequestBody LoginDTO dto)` без throttle/lockout.
Неограниченные попытки логина с одного IP.

---

## ⚠️ САНКЦИОНИРОВАННЫЕ ИСКЛЮЧЕНИЯ (pre-approved CTO)

1. **pom:** добавить Bucket4j (`com.bucket4j:bucket4j-core`) в `zorrobpm-rest/pom.xml`. Только эту.
2. Новый фильтр/интерцептор в `zorrobpm-rest` (напр. `LoginRateLimitFilter.java`).

Всё прочее из G-C (contract, схема, crypto, сигнатуры) — по-прежнему стоп.

---

## 🔴 КРИТИЧНО — НЕ сломать существующие тесты (проверено: 5 IT-классов логинятся!)

Эти классы делают `POST /auth/login` (часто в `@BeforeAll`, некоторые несколько раз):
`JwtAuthFilterIntegrationTest`, `ProcessDefinitionResourceIntegrationTests`,
`SecurityHardeningIntegrationTest`, `ValidationIntegrationTest`,
`GlobalExceptionHandlerProofOfFailureTest`.

Все идут с **одного IP** (MockMvc = 127.0.0.1) в **одном JVM**. Если rate-limit
включён глобально в тестах — суммарно >5 логинов → 429 → массовый провал.

**Обязательное решение:**
- Rate-limit управляется свойством `zorrobpm.security.login-rate-limit.enabled` (default **true**).
- В `application-test` (или `src/test/resources/application-test.yml`) → **false** (выключен для всех существующих IT).
- Тест WO-SEC-4 включает лимит **точечно** через `@TestPropertySource(properties = {
  "zorrobpm.security.login-rate-limit.enabled=true", "...capacity=5", "...window-seconds=1" })`
  — отдельный контекст, не влияет на остальные.

Проверь запрет из раздела «Запреты»: existing IT должны остаться зелёными.

---

## Решение
Bucket4j (in-memory): **N** запросов / окно **W** с одного IP → 429 Too Many Requests.
Обе величины — из свойств (`...capacity`, `...window-seconds`), НЕ хардкод (нужно для теста #2).
Дефолт прод: 5 / 60s. Пропускать `OPTIONS` (preflight — не попытка).

**Формат ответа при 429 — консистентно с error-контрактом WO-SEC-3 (`{code,message}`):**
```json
{ "code": "RATE_LIMITED", "message": "Too many attempts, retry after 60s" }
```
+ заголовок `Retry-After`.

## Критерии приёмки — каждый с тестом (G-A/G-B)

| # | Критерий | Тест | Ожидаемый факт |
|---|---|---|---|
| 1 | (N+1)-я попытка → 429 | IT (limit on, capacity=5) | 429 + `Retry-After` + `{"code":"RATE_LIMITED"}` |
| 2 | После окна → снова работает | IT с **коротким окном** (window=1s), не sleep(60s) | после окна → 200/401 (не 429) |
| 3 | 1-я попытка (верные данные) не блокируется | IT | 200 |
| 4 | **Существующие IT не сломаны** (limit off в test-профиле) | `mvn verify` весь reactor | все прежние IT зелёные |
| 5 | **proof-of-failure (V3)** | IT #1 на текущем коде (без фильтра) | **КРАСНЫЙ** (200 вместо 429) |
| 6 | Регресс | `mvn clean verify` | BUILD SUCCESS |

> Критерий #2: окно берётся из свойства → в тесте `window-seconds=1`, ждать чуть больше секунды
> (Awaitility или `Thread.sleep(1100)`), НЕ 60 секунд. Тест должен быть быстрым и детерминированным.

## Запреты
Не блокировать `/auth/login` по Bearer. **Не ломать 5 существующих IT-классов** (limit off в test-профиле).
Без хардкода лимита/окна (иначе критерий #2 не проверить). Без `@Disabled`.

## Гейты
G6 (security) + HARD GATES G-A…G-G. CTO review + CI green.

## DoD / отчёт
Полная таблица #1–#6 → команда → факт. Proof-of-failure #5. Отчёт в `mimo-to-cto.md`.
