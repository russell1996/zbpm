# WO-SEC-2 — Security Hardening (CORS + secret + admin)     [P0 / CRITICAL]

## Цель
Устранить три критических уязвимости конфигурации:
(a) CORS wildcard + credentials,
(b) дефолтный JWT-секрет `change-me-...` не вызывает fail-fast в prod,
(c) дефолтный `admin/admin` без форс-смены.

## Проблемы (точно по коду)

**(a) CORS:**
```
zorrobpm-rest/.../configuration/WebConfiguration.java:12-16
```
```java
.allowedOriginPatterns("*").allowCredentials(true)  // любой origin + kreды
```

**(b) JWT secret:**
```
zorrobpm-engine/.../security/TokenService.java:29
```
```java
@Value("${zorrobpm.security.jwt-secret:change-me-dev-secret-please-override-in-production}")
```

**(c) Admin bootstrap:**
```
zorrobpm-engine/.../security/UiUserBootstrap.java:24-27
```
```java
@Value("${zorrobpm.security.default-admin-password:admin}")
private String adminPassword;
```

## Scope — строго
**Можно трогать:**
- `WebConfiguration.java` + конфиг `zorrobpm.cors.allowed-origins`
- `TokenService.java` — добавить валидацию секрета при старте
- `UiUserBootstrap.java` + `UiUserEntity` (флаг `forcePasswordChange`) + `AuthResource.java` (возврат флага)
- `application-prod.yml` — новый файл с fail-fast настройками
- Тесты в `zorrobpm-rest` и `zorrobpm-engine`

**Нельзя:** менять `zorrobpm-contract`; ломать dev-профиль (дефолты допустимы вне prod).

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | Prod-профиль с дефолтным secret → fail-fast | IT: старт с `spring.profiles.active=prod` + дефолт secret | `ApplicationContext fails` с читаемым сообщением |
| 2 | Dev-профиль с дефолтным secret → старт | IT: старт без prod профиля | контекст стартует |
| 3 | CORS в prod не `*` + credentials | IT: CORS bean в prod-profile | `allowedOrigins` не `*` при `allowCredentials(true)` |
| 4 | CORS в dev работает (`localhost`) | curl с Origin: http://localhost:5173 | 200 + правильный Access-Control заголовок |
| 5 | Первый логин admin/admin → `forcePasswordChange: true` | `POST /auth/login {"username":"admin","password":"admin"}` | response содержит `forcePasswordChange: true` |
| 6 | После смены пароля флаг сбрасывается | смена пароля → повторный логин | `forcePasswordChange: false` |
| 7 | **proof-of-failure (V3)** | IT #1 на текущем коде | **КРАСНЫЙ** (контекст стартует) |
| 8 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## Запреты
Без `@Disabled`; dev-режим не ломать; `zorrobpm-contract` не трогать.

## Гейты
G6 (security) + G3 + G4. CTO review.

## DoD / отчёт
Таблица критерий → команда → факт. Proof-of-failure #7. Невыполненное — "НЕ СДЕЛАНО: причина".
