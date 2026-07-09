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

---
