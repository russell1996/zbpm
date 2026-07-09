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

---
