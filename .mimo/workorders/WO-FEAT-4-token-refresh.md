# WO-FEAT-4 — Refresh Token + Revocation     [P3 / MEDIUM-2]

## Цель
Сократить TTL access-token до 15 минут. Добавить refresh-token (7 дней) с возможностью отзыва.
Устранить ситуацию: скомпрометированный токен валиден 12 часов без возможности отзыва.

## Проблема
```
zorrobpm-engine/.../security/TokenService.java:30
```
```java
@Value("${zorrobpm.security.jwt-ttl-minutes:720}") long ttlMinutes  // 12 часов
```
Нет refresh-механизма, нет blacklist, нет revocation.

## Зависимость
**Требует WO-SEC-6** (httpOnly cookie) — refresh token хранится в httpOnly cookie.

## Scope
**Можно трогать:** `TokenService.java`, `AuthResource.java`, `AuthContract.java`,
новая таблица `refresh_tokens` (Liquibase changeset), `auth.ts` (frontend interceptor для auto-refresh).  
**Нельзя:** менять формат access-token (breaking change для Java-клиентов); менять signing key.

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | Access-token TTL = 15 мин | decode JWT exp field | exp - iat = 900 |
| 2 | `POST /auth/refresh` с valid refresh-token → новый access | IT: логин → подождать → refresh | 200 + новый access token |
| 3 | `POST /auth/logout` инвалидирует refresh-token | IT: logout → refresh → должен упасть | 401 |
| 4 | Истёкший refresh → 401 | IT: передать истёкший refresh token | 401 |
| 5 | Frontend auto-refresh при 401 на запросе | e2e: истёкший access → запрос | запрос прозрачно повторяется |
| 6 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## Запреты
Не убирать Bearer поддержку. Не хранить refresh-token в localStorage.

## DoD / отчёт
Таблица критерий → команда → факт.
