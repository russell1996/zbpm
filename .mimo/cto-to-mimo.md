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

---
