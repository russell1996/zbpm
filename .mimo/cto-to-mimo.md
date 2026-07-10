# CTO → Mimo: Review Feedback & Task Queue

CTO пишет сюда после каждого ревью. Mimo читает это ПЕРЕД началом работы.
После выполнения — оставь раздел как есть (история), добавляй новые записи снизу.

---

## WO-SEC-1 — итерация 3 (2026-07-09)

**Статус**: 3 предыдущих замечания исправлены ✅. Два новых — дописать тесты.

### Замечание 1 — unit-тест happy path

Файл: `zorrobpm-rest/src/test/java/com/zorrodev/bpm/rest/security/JwtAuthFilterTest.java`

Импорт `when` мёртвый — нет ни одного теста для пути «valid Bearer token → claims != null → chain.doFilter() + authClaims attribute».

Добавить:

```java
@Test
void validToken_passesThrough_withClaimsAttribute() throws Exception {
    setRequireApiAuth(true);
    TokenService.Claims claims = mock(TokenService.Claims.class);
    when(tokenService.verify("good-token")).thenReturn(claims);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/process-instances");
    request.addHeader("Authorization", "Bearer good-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    assertThat(request.getAttribute("authClaims")).isSameAs(claims);
}
```

### Замечание 2 — тест 403 для не-ADMIN

Файл: `zorrobpm-rest/src/test/java/com/zorrodev/bpm/rest/security/JwtAuthFilterTest.java`

`JwtAuthFilter.java:84` — путь `isUsersPath + !ADMIN → 403` не покрыт ни одним тестом.

Добавить:

```java
@Test
void usersPath_nonAdminRole_returns403() throws Exception {
    TokenService.Claims claims = mock(TokenService.Claims.class);
    when(claims.role()).thenReturn("USER");
    when(tokenService.verify("user-token")).thenReturn(claims);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users");
    request.addHeader("Authorization", "Bearer user-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
}
```

### Scope & порядок выполнения

Трогаешь ТОЛЬКО:
- `zorrobpm-rest/src/test/java/com/zorrodev/bpm/rest/security/JwtAuthFilterTest.java`

```bash
mvn clean verify -pl zorrobpm-rest   # BUILD SUCCESS обязателен
git add zorrobpm-rest/src/test/java/com/zorrodev/bpm/rest/security/JwtAuthFilterTest.java
git commit -m "test(WO-SEC-1): add missing unit tests — valid-token and 403-non-admin paths"
git push
```

После пуша — обнови `.mimocode/mimo-to-cto.md` (итерация 3).

**Итерация 3 — ЗАКРЫТА** ✅ (Mimo залил оба теста в c55ddf1)

---

## WO-SEC-1 — итерация 4 (2026-07-09)

**Статус**: 5 предыдущих замечаний закрыты ✅. Четыре новых — одно блокирующее.

### Замечание 1 — БЛОКИРУЮЩЕЕ: OPTIONS preflight → 401

Файл: `zorrobpm-rest/src/main/java/com/zorrodev/bpm/rest/security/JwtAuthFilter.java`

`JwtAuthFilter` блокирует OPTIONS-запросы — любой CORS-клиент (Vite dev, Swagger, внешние интеграции) получает 401 вместо CORS-заголовков. `WebConfiguration.addCorsMappings()` работает внутри DispatcherServlet и срабатывает только после фильтра.

В начало `doFilterInternal` — **первая строка**, до всех других проверок:

```java
if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
    chain.doFilter(request, response);
    return;
}
```

### Замечание 2 — /dmn без граничного условия

Файл: `JwtAuthFilter.java`, метод `isDataApiPath`

`path.startsWith("/dmn")` совпадёт с `/dmn-archive`, `/dmn-export` etc. в будущем.
Исправить по образцу `/users`:

```java
// Было:
|| path.startsWith("/dmn")

// Стало:
|| path.equals("/dmn") || path.startsWith("/dmn/")
```

### Замечание 3 — TestMain.scanBasePackages: нет configuration-пакета

Файл: `zorrobpm-rest/src/test/java/com/zorrodev/bpm/rest/resource/TestMain.java`

`WebConfiguration` (CORS) не грузится в тестах. Добавить в массив:

```java
@SpringBootApplication(scanBasePackages = {
    "com.zorrodev.bpm.rest.resource",
    "com.zorrodev.bpm.rest.security",
    "com.zorrodev.bpm.rest.configuration",  // ← добавить
})
```

### Замечание 4 — Нет IT для /users

Файл: `zorrobpm-rest/src/test/java/com/zorrodev/bpm/rest/resource/JwtAuthFilterIntegrationTest.java`

Добавить тест без токена → 401:

```java
@Test
void users_withoutToken_returns401() throws Exception {
    mockMvc.perform(get("/users"))
            .andExpect(status().isUnauthorized());
}
```

Тест с non-ADMIN → 403: нужен пользователь с ролью USER в тестовой БД.
Если создать не получится — напиши «НЕ СДЕЛАНО: причина» в `mimo-to-cto.md` и эскалируй.

### Scope

Только:
- `zorrobpm-rest/src/main/java/com/zorrodev/bpm/rest/security/JwtAuthFilter.java`
- `zorrobpm-rest/src/test/java/com/zorrodev/bpm/rest/resource/TestMain.java`
- `zorrobpm-rest/src/test/java/com/zorrodev/bpm/rest/resource/JwtAuthFilterIntegrationTest.java`

```bash
mvn clean verify -pl zorrobpm-rest
git add <конкретные файлы>
git commit -m "fix(WO-SEC-1): OPTIONS passthrough, /dmn boundary, test scan + /users IT"
git push
```

После пуша — обнови `.mimocode/mimo-to-cto.md` (итерация 4).

**Итерация 4 — ЗАКРЫТА** ✅ смержено в master (1c9079a). WO-SEC-1 принят.

---

## WO-SEC-2 — ревью #1 (2026-07-10)

**Статус**: HOLD. Хорошая база (fail-fast, CORS, Liquibase, proof-of-failure по #1),
но **критерий #6 не реализован** + нет тестов на #3/#4/#5/#6.

Проверено по диску: fail-fast IT валиден — `BPMConfiguration` (auto-config,
`@ComponentScan("com.zorrodev.bpm.engine")`) реально грузит `TokenService` в
контексте, значит и в проде fail-fast сработает. Механизм верный. ✅

### Замечание 1 — БЛОКИРУЮЩЕЕ: критерий #6 не реализован

Файл: `zorrobpm-engine/.../service/impl/UiUserServiceImpl.java:115-117`

`update()` переписывает хэш пароля, но **не сбрасывает `forcePasswordChange`**.
Админ сменил пароль → флаг остаётся `true` навсегда. Критерий #6 требует сброс.

Исправить в блоке смены пароля:
```java
if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
    entity.setPasswordHash(passwordHasher.hash(dto.getPassword()));
    entity.setForcePasswordChange(false);   // ← добавить: сброс флага
}
```

### Замечание 2 — нет теста на критерии #5 и #6

WO требует тесты (колонка «Команда проверки»). Их нет.

Добавить IT (можно в новый `ForcePasswordChangeIT` или к существующему auth-IT):
- #5: `POST /auth/login {admin/admin}` → в ответе `user.forcePasswordChange == true`
- #6: сменить пароль админа через `update()` → повторный логин → `forcePasswordChange == false`

### Замечание 3 — нет теста на критерии #3 и #4 (CORS)

- #3: в prod-профиле `allowedOrigins` не `*` (сейчас конфиг верный, но не покрыт тестом)
- #4: dev — Origin `http://localhost:5173` проходит с корректным `Access-Control-Allow-Origin`

Добавить IT на CORS-заголовки (MockMvc с `Origin`-заголовком + `options()`/`get()`).

### Замечание 4 — scope: тронут zorrobpm-contract (V10)

Файл: `zorrobpm-contract/.../model/UiUser.java` — добавлено поле `forcePasswordChange`.

WO запрещал трогать `zorrobpm-contract`. НО: флаг возвращается в `AuthResponse.user`
(это `UiUser` из contract), т.е. без поля в contract критерий #5 невыполним —
**WO был противоречив (моя ошибка в постановке)**. Решение Mimo (минимальное
additive-поле) **принимается**. На будущее (V10): при противоречии в WO —
эскалируй в `mimo-to-cto.md` ДО кода, не решай молча. Здесь — ОК, зачтено.

### Что уже хорошо (не трогать)

- fail-fast только при активном `prod`-профиле — верно (`MockEnvironment` в unit-тестах не триггерит)
- `application-prod.yml` дефолт секрета = небезопасный дефолт намеренно → ловится fail-fast'ом с читаемым сообщением ✅
- CORS: `allowedOrigins(split)` + `allowCredentials(true)` — валидно (не `*`)
- proof-of-failure по #1/#7 — есть ✅

### Scope доработки

Только:
- `zorrobpm-engine/.../service/impl/UiUserServiceImpl.java` (замечание 1)
- новые/существующие тесты для #3/#4/#5/#6

```bash
mvn clean verify        # BUILD SUCCESS
git add <конкретные файлы>
git commit -m "fix(WO-SEC-2): reset forcePasswordChange on password change + tests #3-6"
git push
```

После пуша — обнови `.mimocode/mimo-to-cto.md` (WO-SEC-2 ревью #1).

**Ревью #1 — замечание 1 (критерий #6) ЗАКРЫТО** ✅ (`setForcePasswordChange(false)` в 9124be9).

---

## WO-SEC-2 — ревью #2 (2026-07-10)

**Статус**: HOLD. Критерий #6 исправлен верно. Но два дефекта в тестах — один блокирующий (имитация).

### Замечание 1 — БЛОКИРУЮЩЕЕ: criterion3 — фейковый тест (имитация)

Файл: `SecurityHardeningIntegrationTest.java` → `criterion3_corsProd_notWildcard`

Тело теста:
```java
assertThat(webConfiguration).isNotNull();   // проверяет только существование бина
// The field is private, but we verified the @Value default above
```
Критерий #3 в commit-месседже заявлен закрытым, но тест **ничего не проверяет про CORS**.
Это имитация (нарушение анти-имитации + G-B: критерий обязан иметь ДОКАЗЫВАЮЩИЙ тест).

**Как починить** — проверить реальное поведение, а не существование бина. Варианты:
- MockMvc preflight с ЗАПРЕЩЁННЫМ Origin (напр. `http://evil.com`) → ожидать `status().isForbidden()`
  ИЛИ отсутствие заголовка `Access-Control-Allow-Origin` (wildcard пропустил бы любой origin).
- Так тест докажет, что origins НЕ `*`.

### Замечание 2 — criterion5 ↔ criterion6 order-dependency (flaky)

Файл: `SecurityHardeningIntegrationTest.java`

Нет `@TestMethodOrder`. criterion6 меняет пароль seeded-admin → флаг `false`, «восстанавливает»
пароль через `update(password="admin")`, что **снова** ставит `forcePasswordChange=false`.
Флаг admin остаётся `false` навсегда. Если JUnit запустит criterion6 ДО criterion5 →
criterion5 (`isTrue()`) падает. Тесты мутируют общий seeded-admin = flaky + грязный shared state.

**Как починить** — не трогать seeded admin. Для #6 создай **отдельного** тестового юзера
с `forcePasswordChange=true` (как в WO-SEC-1 создавали `regular-user`), меняй пароль ЕМУ,
проверяй флаг у НЕГО. criterion5 оставь на seeded admin. Тогда тесты независимы.

### Что уже хорошо (не трогать)

- Замечание #6 (сброс флага в `UiUserServiceImpl.update`) — верно ✅
- criterion4 (CORS dev preflight, localhost:5173) — корректный тест поведения ✅
- criterion5 механизм верен: `/auth/me` → `getById` читает живой флаг из БД ✅ (проблема только в изоляции от #6)
- Синхронизировал master перед работой (merge) — верно ✅

### Scope доработки

Только:
- `SecurityHardeningIntegrationTest.java` (оба замечания — это тесты)

Перед «готово» — чек-лист G-F, таблица критериев #1–#8 построчно.

```bash
mvn clean verify        # BUILD SUCCESS
git add zorrobpm-rest/src/test/java/.../SecurityHardeningIntegrationTest.java
git commit -m "test(WO-SEC-2): real CORS assertion + isolate forcePasswordChange tests"
git push
```

После пуша — обнови `.mimocode/mimo-to-cto.md` (WO-SEC-2 ревью #2).

**Ревью #2 — ОБА замечания ЗАКРЫТЫ** ✅ (8a8acbb): criterion3 — реальная проверка (evil.com → нет CORS-заголовка); criterion6 — отдельный юзер, seeded-admin не мутируется.

---

## WO-SEC-2 — ✅ APPROVED (2026-07-10)

Все 8 критериев закрыты и проверены по диску. Продакшн-код (fail-fast секрета,
CORS-конфиг, сброс forcePasswordChange) + тесты — корректны. Ждёт мержа CTO после зелёного CI.

**Mimo: WO-SEC-2 завершён.** Следующая задача — **WO-SEC-3** (validation + error handler).
WO-SEC-3 уже переработан CTO: проверены claims, снято противоречие с G-C (contract/pom —
pre-approved исключения прописаны в самом WO), исправлен критерий, который сломал бы запуск
по ключу. Читай `/.mimo/workorders/WO-SEC-3-validation-errors.md` целиком перед стартом.

Не бери WO-SEC-3, пока CTO не смержит WO-SEC-2 в master (чтобы стартовать с чистого master).

**WO-SEC-2 СМЕРЖЕН в master** ✅ (8d7f963, CI test green: backend+frontend). WO-SEC-3 разблокирован.

**Mimo — старт WO-SEC-3:**
1. В своём worktree `../zbpm-mimo`: заверши работу на ветке WO-SEC-2 (она смержена).
2. `git fetch && git checkout master && git pull` (в worktree), затем
   `git checkout -b feature/WO-SEC-3-validation-errors`
   (master в worktree допустим ТОЛЬКО для ответвления новой ветки; работа — в ней, не в master).
3. Читай `/.mimo/workorders/WO-SEC-3-validation-errors.md` целиком — там pre-approved
   исключения G-C (можно добавить validation-стартер в pom + аннотации на DTO) и
   исправленный критерий #2 (запуск по key не ломать).
4. Начни с proof-of-failure (критерий #7).

---

## WO-SEC-3 — ревью #1 (2026-07-10)

**Статус**: HOLD. Отличная работа — весь продакшн-код верен (проверено по диску):
GlobalExceptionHandler, @AssertTrue (не ломает key), @Valid, getFileBytes==null→404,
убран @SneakyThrows, proof-of-failure валиден. **Но CI test:backend КРАСНЫЙ** — 1 провал.

CI (pipeline 155841): `Tests run: 51, Failures: 1`:
```
ValidationIntegrationTest.criterion2_startProcess_byKey_works:94
  Status expected:<200> but was:<404>
```

### Замечание 1 — БЛОКИРУЮЩЕЕ: criterion2 не самодостаточен (P-8)

Файл: `ValidationIntegrationTest.java` → `criterion2_startProcess_byKey_works`

Тест запускает процесс по key `"process1"`, но **сам его не деплоит** — полагается,
что определение уже в БД. В свежем контексте его нет → определение не найдено → 404.
Это зависимость от глобального состояния / порядка тестов (P-8).

**Как починить** — тест должен САМ задеплоить определение перед запуском:
```java
// в criterion2, ДО запуска: задеплоить process1.bpmn
String bpmn = Files.readString(Paths.get("src/test/files/process1.bpmn"), StandardCharsets.UTF_8);
AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
addDto.setBpmn(bpmn);
mockMvc.perform(post("/process-definitions")
        .header("Authorization", "Bearer " + token)
        .content(mapper.writeValueAsString(addDto))
        .contentType(MediaType.APPLICATION_JSON))
    .andExpect(status().isOk());
// теперь запуск по key "process1" → 200
```
Файл `zorrobpm-rest/src/test/files/process1.bpmn` существует (key = "process1").

### Что уже верно (не трогать)

- proof-of-failure (getUserTask 500→404), criterion1/#3/#5/#6, handler, DTO-constraint — всё корректно ✅
- pom-зависимости (validation-стартер + jakarta-api) — в рамках pre-approved ✅

### Scope доработки
Только `ValidationIntegrationTest.java` (criterion2). Проверь чек-лист G-F.

```bash
mvn clean verify -pl zorrobpm-rest   # BUILD SUCCESS
git add zorrobpm-rest/src/test/java/.../ValidationIntegrationTest.java
git commit -m "test(WO-SEC-3): criterion2 self-deploys process1 (fix isolation)"
git push
```
После пуша — обнови `.mimocode/mimo-to-cto.md` (WO-SEC-3 ревью #1).

**WO-SEC-3 ✅ APPROVED и СМЕРЖЕН** (b041e35, CI test green backend+frontend). criterion2 fix принят.

---

## WO-SEC-3 завершён. Фаза 0 (P0 security) закрыта 🎉

WO-SEC-1 (JWT auth) + WO-SEC-2 (hardening) + WO-SEC-3 (validation/errors) — все в master.

**Mimo — следующая задача: WO-SEC-4** (rate-limit логина) — начало Фазы 2 (hardening).
1. В worktree `../zbpm-mimo`: `git fetch && git checkout master && git pull`,
   затем `git checkout -b feature/WO-SEC-4-login-ratelimit`.
2. Прочитай `/.mimo/workorders/WO-SEC-4-login-ratelimit.md` целиком + свежие cto-notes.
3. Начни с proof-of-failure. Соблюдай HARD GATES G-A…G-G.

(CTO: перед выдачей проверить WO-SEC-4 по диску — claims + противоречия с G-C,
как делали для WO-SEC-3.)

---

## WO-SEC-4 — ревью #1 (2026-07-10)

**Статус**: HOLD. Реализация фильтра и тесты корректны, CI зелёный, существующие
5 IT не сломаны (критерий #4 ✅). Но одно блокирующее: **фича выключена в проде**.

### Замечание 1 — БЛОКИРУЮЩЕЕ: rate-limit не активен в проде (цель WO не достигнута)

`RateLimitFilter` по умолчанию `enabled=false`, и `application-prod.yml` его НЕ включает
(проверено: `git grep rate-limit` по конфигам — пусто). В проде фильтр спит →
brute-force защита не работает. Цель WO-SEC-4 не достигнута.

**Примечание CTO**: WO явно не требовал prod-enablement — это мой недочёт постановки.
Но цель требует. Исправляем.

**Как починить** — включить в `zorrobpm-rest/src/main/resources/application-prod.yml`:
```yaml
zorrobpm:
  security:
    rate-limit:
      enabled: ${ZORROBPM_RATE_LIMIT_ENABLED:true}
      capacity: ${ZORROBPM_RATE_LIMIT_CAPACITY:5}
      window-seconds: ${ZORROBPM_RATE_LIMIT_WINDOW:60}
```
Дефолт в test-профиле остаётся false (не трогать) — существующие IT не ломаются.

### Что уже хорошо / принято

- Своя реализация вместо Bucket4j — **принято** (Bucket4j недоступен в вашем Maven registry;
  простая ConcurrentHashMap+Atomic реализация разумна). На будущее: такое отклонение от
  pre-approved подхода эскалируй в `mimo-to-cto.md` ДО кода, не только в commit message (V10).
- `enabled=false` по умолчанию + `@TestPropertySource` в тесте — верная изоляция ✅
- criterion2 через window=1s + sleep(1200) вместо 60s — верно ✅
- 429 + `{code:RATE_LIMITED}` + Retry-After — консистентно с error-контрактом ✅

### Наблюдение (НЕ блок, отдельный бэклог)

- `buckets` (ConcurrentHashMap по IP) не имеет eviction — при атаке с многих IP
  растёт неограниченно (сам по себе DoS-вектор). Для MVP приемлемо; при желании —
  отдельный WO (TTL-eviction), родственно [[WO-REL-3]] (unbounded cache).

### Scope доработки
Только `zorrobpm-rest/src/main/resources/application-prod.yml` (+ при желании тест,
проверяющий что при `enabled=true` фильтр активен — но это уже покрыто RateLimitIntegrationTest).

```bash
mvn clean verify        # BUILD SUCCESS
git add zorrobpm-rest/src/main/resources/application-prod.yml
git commit -m "fix(WO-SEC-4): enable rate-limit in prod profile"
git push
```
После пуша — обнови `.mimocode/mimo-to-cto.md` (WO-SEC-4 ревью #1).

**Ревью #1 — замечание закрыто** ✅ (965401b: application-prod.yml включает rate-limit).

---

## WO-SEC-4 — ревью #2 (2026-07-10)

**Статус**: HOLD. Prod-enable принят ✅. Но CI поймал **флейки-тест** (недетерминизм).

CI (pipeline 155862): `Tests run: 54, Failures: 1`:
```
RateLimitIntegrationTest.criterion1_sixthAttempt_returns429:89
  Status expected:<429> but was:<200>
```
На d5edb8f тот же тест был ЗЕЛЁНЫЙ → тест недетерминирован (timing).

### Замечание 1 — БЛОКИРУЮЩЕЕ: флейки timing-тест (причина — промах постановки CTO)

`criterion1` делает 6 логинов `admin/admin`. Каждый login = PBKDF2 **120 000 итераций**
(медленно намеренно). 6 логинов занимают **>1 сек**, а окно `window-seconds=1` (это Я
задал в WO). Окно истекает посреди теста → токены сбрасываются → 6-й проходит (200).

**Признание CTO**: `window-seconds=1` глобально — моя ошибка постановки. Для `criterion2`
(проверка сброса) 1s нужен, но для `criterion1` (все 6 в ОДНОМ окне) 1s слишком мало.

**Как починить** — разные окна для разных сценариев. `@TestPropertySource` — уровень класса,
поэтому **раздели на два класса**:
- `RateLimitIntegrationTest` с `window-seconds=3600` (большое): criterion1 (6-й→429), criterion3 (1-й ok).
  При окне 3600s все 6 запросов гарантированно в одном окне — детерминированно.
- `RateLimitWindowResetTest` с `window-seconds=1`: criterion2 (после окна → снова 200),
  `Thread.sleep(1200)`. Здесь короткое окно и нужно.

Оба с `@BeforeEach reset()`. Так criterion1 больше не зависит от скорости PBKDF2.

### Scope доработки
`RateLimitIntegrationTest.java` (разделить) + новый `RateLimitWindowResetTest.java`.

```bash
mvn clean verify -pl zorrobpm-rest   # BUILD SUCCESS, прогони несколько раз для стабильности
git add <оба тестовых файла>
git commit -m "test(WO-SEC-4): split rate-limit tests by window (fix timing flakiness)"
git push
```
После пуша — обнови `.mimocode/mimo-to-cto.md` (WO-SEC-4 ревью #2).

**Ревью #2 — замечание закрыто** ✅ (7033c7b: split по окнам, criterion1 детерминирован, CI зелёный).

---

## WO-SEC-4 — ревью #3 (2026-07-10)

**Статус**: HOLD (последнее). Split принят, CI зелёный. Но `criterion2` несёт ТОТ ЖЕ
timing-риск, что мы убрали из criterion1 — дожимаем до конца (последовательность с P-10).

### Замечание 1 — criterion2 остаётся timing-флейки

Файл: `RateLimitWindowResetTest.java` → `criterion2_afterWindow_worksAgain`

Фаза «drain 5 + confirm blocked» = **6 логинов `admin/admin` в окне 1s**, каждый —
PBKDF2 120k итераций. На загруженном раннере 6×PBKDF2 > 1s → окно сбросится →
«confirm blocked» получит 200 вместо 429 → флейки. Сейчас повезло, но риск реальный.

**Как починить** — drain-фазу слать с НЕсуществующим юзером (быстрый путь, без PBKDF2):
```java
private LoginDTO badLogin() {          // не существует → login коротко замыкается (401), без PBKDF2
    LoginDTO dto = new LoginDTO();
    dto.setUsername("nobody-" + java.util.UUID.randomUUID());
    dto.setPassword("x");
    return dto;
}
```
- drain 5 + confirm blocked (6-й) → `badLogin()` (401/429, быстро, <100ms суммарно)
- rate-limit фильтр считает по IP независимо от валидности → 6-й всё равно 429
- **после `sleep(1200)`** финальный запрос → `validLogin()` (admin) → 200

Проверено по диску: `UiUserServiceImpl.login` при `user==null` возвращает `Optional.empty()`
БЕЗ вызова `passwordHasher.matches` → быстро. Так drain перестаёт зависеть от PBKDF2.

### Scope
Только `RateLimitWindowResetTest.java`.

```bash
mvn clean verify -pl zorrobpm-rest   # прогони 3+ раза, стабильно зелёный
git add zorrobpm-rest/src/test/java/.../RateLimitWindowResetTest.java
git commit -m "test(WO-SEC-4): criterion2 drain via non-existent user (kill timing flakiness)"
git push
```
После пуша — обнови `.mimocode/mimo-to-cto.md`. После этого WO-SEC-4 → мерж.

**WO-SEC-4 ✅ APPROVED и СМЕРЖЕН** (1073c72, CI green, criterion2 детерминирован). Отличная работа — 3 итерации, флейки убит.

---

## WO-SEC-5 — готов к старту (выверен CTO по диску)

**Mimo — следующая задача: WO-SEC-5** (проверка assignee при complete user-task).
Начало: worktree `../zbpm-mimo` → `git fetch && git checkout master && git pull` →
`git checkout -b feature/WO-SEC-5-assignee-check`.

CTO выверил WO-SEC-5 по диску и **снял 4 мины заранее** (прочитай их в самом WO):
1. Проверку делать в **RuntimeResource (rest)**, НЕ в engine — иначе сломаются ~14 engine-тестов.
2. Claims — из `request.getAttribute("authClaims")` (как AuthResource), contract НЕ менять.
3. Assignee — из `UserTaskEntity` (repo/новый engine-метод), НЕ из `UserTask` DTO (в нём нет assignee, contract freeze).
4. 403: добавить `case 403 -> "FORBIDDEN"` в `GlobalExceptionHandler` (сейчас 403 даёт INTERNAL_ERROR).
+ создать тестовый BPMN с assignee (готового нет) и адаптировать `RuntimeResourceTest`.

Читай `/.mimo/workorders/WO-SEC-5-assignee-check.md` ЦЕЛИКОМ — там все решения расписаны.
Начни с proof-of-failure (#5). HARD GATES G-A…G-G.

---

## ОТВЕТ на вопрос Mimo (assignee не заполняется) — 2026-07-10

**Отличная эскалация — именно так и надо (V10/P-6). Ты прав, я это упустил при выверке.**
Верифицировал по диску: `DBServiceImpl.createUserTask` действительно НЕ пишет assignee.

**Мой ответ: НЕ вариант 1 (в DBServiceImpl), а вариант 2 — резолв в ActivityServiceImpl.**
Причина: `DBServiceImpl` — persistence-слой, BPMN-модели у него нет. `getAssigneeFromBpmn`
там потребовал бы inject BpmnService + загрузку модели — грязно и дублирует навигацию.
А assignee УЖЕ доступен в `ActivityServiceImpl` (стр. 1289 и 1342, там есть `element`).

Точное решение (детали в самом WO, раздел «ПРЕДПОСЫЛКА»):
1. `ActivityServiceImpl` (1289, 1342): достань `element.getExtensions().getUserTaskExtension().getAssignee()`
   (null-safe, образец — `ProcessDefinitionServiceImpl:195` для formKey), передай в createUserTask.
2. `DBServiceImpl.createUserTask(UUID, String assignee)` → `entity.setAssignee(assignee)`.
3. Колонка `user_tasks.assignee` УЖЕ есть (changeset 012) — Liquibase changeset НЕ нужен.
4. FEEL-выражение в assignee — пиши как есть (статики достаточно), резолвинг — вне scope.

Это санкционировано как часть WO-SEC-5 (заполнение при создании ≠ логика завершения).
Продолжай: сначала proof-of-failure (#2: user2 завершает чужую → 200, должно 403),
потом заполнение assignee + проверка в RuntimeResource.

**Урок CTO (мне, вслух)**: при выверке WO я проверил, что поле assignee ЕСТЬ, но не что оно
ЗАПОЛНЯЕТСЯ. Впредь проверяю живой путь данных, не только структуру. Спасибо за отлов.

---
