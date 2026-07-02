# Security Analysis — ZorroBPM CE

> Каждая проблема: точная ссылка на файл:строку + почему опасно + как проверить.

---

## CRITICAL-1
**CORS wildcard + `allowCredentials(true)`**

```
zorrobpm-rest/.../configuration/WebConfiguration.java:12-16
```

```java
registry.addMapping("/**")
    .allowedOriginPatterns("*")          // любой origin
    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
    .allowedHeaders("*")
    .allowCredentials(true);             // + с кредами
```

Spring разрешает `allowedOriginPatterns("*") + allowCredentials(true)` — браузер будет получать
`Access-Control-Allow-Origin: <attacker-origin>` + `Access-Control-Allow-Credentials: true`.
Это означает: любой сайт может делать авторизованные кросс-доменные запросы от имени залогиненного пользователя.

**Проверка:** `curl -H "Origin: https://evil.com" -I http://localhost:8080/process-instances`
→ ответ содержит `Access-Control-Allow-Origin: https://evil.com` + `Access-Control-Allow-Credentials: true`.

**Фикс:** задать явный список допустимых origins через конфиг (`zorrobpm.cors.allowed-origins`), fail-fast в prod-профиле если не задан.

---

## CRITICAL-2
**Нет аутентификации на 90% REST API**

```
zorrobpm-rest/.../security/JwtAuthFilter.java:29-31
```

```java
private static boolean isProtected(String path) {
    return path.equals("/auth/me") || isUsersPath(path);  // только это
}
```

Фильтр пропускает без токена **всё** кроме `/auth/me` и `/users/**`:

| Эндпоинт | Что можно сделать без токена |
|---|---|
| `GET /process-instances` | читать все инстансы |
| `GET /variables` | читать все переменные (включая секреты в них) |
| `POST /process-instances` | запускать произвольные процессы |
| `POST /user-tasks/{id}/complete` | завершать любые задачи |
| `POST /incidents/{id}/resolve` | резолвить инциденты |
| `POST /process-definitions` | деплоить BPMN/DMN |
| `GET /incidents`, `GET /timer-jobs`, `GET /message-subscriptions` | полный read доступ |

**Проверка:** `curl http://localhost:8080/process-instances` без заголовка Authorization → HTTP 200.

**Фикс:** расширить `isProtected` чтобы охватить все data/runtime пути. Флаг для обратной совместимости с Java-клиентом. IT: GET без токена → 401 для всех эндпоинтов.

---

## CRITICAL-3
**Дефолтные `admin/admin` + JWT-секрет `change-me-...`**

```
zorrobpm-engine/.../security/UiUserBootstrap.java:24-27
zorrobpm-engine/.../security/TokenService.java:29
```

```java
// UiUserBootstrap.java
@Value("${zorrobpm.security.default-admin-username:admin}")
private String adminUsername;
@Value("${zorrobpm.security.default-admin-password:admin}")
private String adminPassword;

// TokenService.java
@Value("${zorrobpm.security.jwt-secret:change-me-dev-secret-please-override-in-production}")
private String secret;
```

Ни один из деплойных конфигов (application.properties, application-prod.yml) не переопределяет эти значения.
Атакующий: (1) логинится как `admin/admin`, (2) получает JWT, (3) с известным секретом может форжить токены произвольно.

**Проверка:**
1. `POST /auth/login {"username":"admin","password":"admin"}` → 200 + token.
2. Любой JWT подписанный `change-me-dev-secret-please-override-in-production` будет принят.

**Фикс:**
- В prod-профиле: fail-fast при старте если `zorrobpm.security.jwt-secret` == дефолту.
- Первый вход `admin/admin` → форс-смена пароля (флаг `forcePasswordChange`).
- IT: старт контекста с prod-профилем и дефолтным секретом → `ApplicationContext fails`.

---

## HIGH-1
**Hand-rolled JWT — риск регрессии**

```
zorrobpm-engine/.../security/TokenService.java
```

JWT полностью написан вручную: base64url-кодирование, HMAC-SHA256, парсинг. Текущая реализация технически работает, но:
- `constantTimeEquals` сначала проверяет `a.length() != b.length()` (timing side-channel — на практике длины равны, поэтому некритично сейчас).
- Любое изменение алгоритма = прямой путь к ошибке.

**Фикс (Phase 3):** заменить на `io.jsonwebtoken:jjwt` или `com.nimbusds:nimbus-jose-jwt`.

---

## HIGH-2
**JWT в `localStorage` — уязвимость при XSS**

```
zorrobpm-frontend/src/stores/auth.ts:10,19-20
```

```typescript
const TOKEN_KEY = 'zbpm_token'
const accessToken = ref<string | null>(localStorage.getItem(TOKEN_KEY))
// ...
if (token) localStorage.setItem(TOKEN_KEY, token)
```

Любой XSS (в зависимостях, BPMN-рендерере) крадёт токен и получает полный доступ до истечения 12 часов.

**Фикс:** перейти на `httpOnly; Secure; SameSite=Strict` cookie. Требует изменений в backend (выдача cookie вместо body) + фронт (убрать ручную передачу `Authorization: Bearer`).

---

## HIGH-3
**Нет rate-limit на `/auth/login`**

```
zorrobpm-rest/.../resource/AuthResource.java:24
```

```java
public AuthResponse login(@RequestBody LoginDTO dto) {
    return userService.login(dto)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "..."));
}
```

Нет lockout, нет throttle, нет CAPTCHA. Перебор паролей — неограниченный.

**Фикс:** Bucket4j или Spring rate-limiting filter на `/auth/login`. Lockout на 5 неверных попыток за 1 минуту.

---

## HIGH-4
**`@SneakyThrows` — стектрейс в 500-ответе**

```
zorrobpm-rest/.../resource/ProcessDefinitionResource.java:46
```

```java
@SneakyThrows
@Override
public String getProcessDefinitionXml(UUID id) {
    return fileService.getFileBytes(id);
}
```

`IOException` от чтения файла → необъявленное RuntimeException → Spring Boot default 500.
В dev-режиме `server.error.include-stacktrace=always` (или `on-trace-param`) — стектрейс с путями к файлам утекает клиенту.

**Фикс:** убрать `@SneakyThrows`, поймать IOException, выбросить `ResponseStatusException(NOT_FOUND)` или `(INTERNAL_SERVER_ERROR)` с безопасным сообщением. Долгосрочно — global `@RestControllerAdvice`.

---

## HIGH-5
**Нет `@Valid` на DTO — нет валидации входных данных**

Ни один из `@RequestBody` параметров в ресурсах не аннотирован `@Valid`. Ни одно поле DTO не имеет `@NotNull`, `@NotBlank`, `@Size`:

| Эндпоинт | DTO | Риск |
|---|---|---|
| `POST /process-instances` | `StartProcessInstanceDTO` | null `processDefinitionId` → NPE в движке |
| `POST /user-tasks/{id}/complete` | `CompleteTaskDTO` | null list → NPE |
| `POST /process-definitions` | Multipart | пустой файл → exception на парсинге |
| `POST /auth/login` | `LoginDTO` | пустые строки → DB query с пустыми значениями |
| `POST /users` | `CreateUiUserDTO` | пустые username/password → вставка пустых значений |

**Фикс:** добавить `@Valid` на все `@RequestBody` + jakarta validation аннотации на полях DTO + `@ControllerAdvice` перехватывающий `MethodArgumentNotValidException` → 400.

---

## HIGH-6
**Нет проверки assignee при завершении user-task**

```
zorrobpm-rest/.../resource/RuntimeResource.java:44-46
```

```java
public IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
    return Optional.ofNullable(runtimeService.completeUserTask(id, dto.getVariables()))
        .map(this::toDTO).orElseThrow();
}
```

Нет никакой проверки: кто вызвал? Является ли он assignee задачи? Знает ли UUID задачи?
Любой пользователь (сейчас даже без токена — CRITICAL-2) может завершить чужую задачу.

**Фикс:** после закрытия CRITICAL-2 — получить `authClaims` из `request.getAttribute("authClaims")`, проверить что `claims.username` == `task.assignee` ИЛИ `claims.role == ADMIN`.

---

## MEDIUM-1
**Нет CSRF-защиты**

Spring Security не подключён (`zorrobpm-rest/pom.xml` — нет зависимости). Нет CSRF filter.
Частичная митигация: токен в localStorage + Bearer header (форма не может выставить header).
Но при CRITICAL-1 (CORS wildcard + credentials) и потенциальном переходе на cookie-auth — CSRF станет реальной угрозой.

**Фикс (при переходе на cookie-auth):** добавить Spring Security CSRF token или SameSite=Strict cookie.

---

## MEDIUM-2
**Токены живут 12 часов без возможности отзыва**

```
zorrobpm-engine/.../security/TokenService.java:30
```

```java
@Value("${zorrobpm.security.jwt-ttl-minutes:720}") long ttlMinutes
```

Нет blacklist / token store / refresh token. Скомпрометированный токен валиден до 12 часов.
Единственный способ инвалидировать все токены — сменить JWT-секрет (logout всех пользователей).

**Фикс:** уменьшить TTL до 15–30 минут + добавить refresh-token в httpOnly cookie (связано с HIGH-2).

---

## MEDIUM-3
**PII переменные логируются на INFO**

```
zorrobpm-engine/.../service/impl/ScriptServiceImpl.java:65
```

```java
log.info("Variable {} = {}", variable.getName(), variable.getValue());
```

Все переменные процесса (имена и значения) выводятся в лог при каждом FEEL-вычислении.
Если в переменных есть персональные данные — они попадают в лог-файлы / Splunk / Kibana.

**Фикс:** убрать это `log.info`, либо перевести на DEBUG, либо маскировать значения.

---

## MEDIUM-4
**Роль — свободная строка `varchar(32)`, нет CHECK constraint**

```
zorrobpm-engine/.../entity/UiUserEntity.java:25
```

```java
/** "ADMIN" or "USER". */
private String role;
```

Прямое SQL-изменение в БД → можно вставить произвольную роль.
Код нормализует через `normalizeRole()`, но это только в application-уровне.

**Фикс:** добавить Liquibase changeset с `CHECK (role IN ('ADMIN','USER'))` на колонке.

---

## MEDIUM-5
**Роут `/admin/users` не проверяет роль в frontend-роутере**

```
zorrobpm-frontend/src/app/router.ts:127, 140-153
```

Route guard проверяет только `isAuthenticated`, не `isAdmin`. Любой USER-роль видит `/admin/users`.
Backend защищён (`/users/**` требует ADMIN), но UX некорректен + информация об эндпоинтах утекает.

**Фикс:** в route guard добавить проверку `meta.requiresAdmin && !auth.isAdmin → redirect('/403')`.
