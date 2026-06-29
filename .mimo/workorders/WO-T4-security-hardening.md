# WO-T4 — Security Hardening (secret / CORS / admin)     [Приоритет: P0 / High]

## Цель
Убрать небезопасные дефолты: (a) дефолтный `jwt-secret`, (b) CORS `*`+credentials, (c) bootstrap `admin/admin` без форс-смены.

## Трассировка
Коды: **SEC2/SEC3/SEC4** ([analysis/08](../../docs/analysis/08-cluster-ha-security.md)). Роадмап: **T4** (Фаза 1).

## Объём (scope) — строго
Можно трогать: `zorrobpm-engine/.../security/TokenService.java`, `zorrobpm-rest/.../configuration/WebConfiguration.java`,
`zorrobpm-engine/.../security/UiUserBootstrap.java` + конфиги профилей + тесты.
НЕЛЬЗЯ: ломать dev-режим (дефолты допустимы вне prod-профиля). Изменения contract — нет.

## Критерии приёмки (объективно, V2)
| # | Критерий | Команда проверки | Ожидаемый факт |
|---|----------|------------------|----------------|
| 1 | Fail-fast на дефолтном secret в prod | IT с `spring.profiles.active=prod` и дефолтным `jwt-secret` | старт контекста **падает** с понятной ошибкой |
| 2 | Не-prod не ломается | IT в test/dev профиле с дефолтом | контекст стартует |
| 3 | CORS в prod не `*`+credentials | IT/проверка бинов CORS в prod | origin не `*` при `allowCredentials(true)` |
| 4 | Форс-смена admin | IT: первый вход `admin/admin` | требуется смена пароля (флаг `forcePasswordChange`) |
| 5 | **proof-of-failure (V3)** | прогнать IT №1 до фикса | **КРАСНЫЙ** (сейчас стартует), после фикса падает |
| 6 | Регресс | `mvn clean verify` | BUILD SUCCESS 10/10 |

## Запреты (V4/V8)
Без отключения проверок ради зелёного; «secure» подтверждать тестом, а не комментарием; dev-режим не ломать; scope строго.

## Гейты
G6 (владелец) + G3 + G4.

## DoD / отчёт
Таблица критерий→команда→факт; явно показать proof-of-failure по №1; невыполненное — «НЕ СДЕЛАНО:<причина>».
