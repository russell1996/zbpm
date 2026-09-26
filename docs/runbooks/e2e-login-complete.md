# E2E login → complete (WO-TEST-12)

Сквозной сценарий против **реального backend**: логин → смена пароля →
deploy → старт → complete, с live SSE-проверками без перезагрузки.

## Запуск

```bash
bash ci/e2e-login-complete.sh
```

Скрипт сам: собирает `app` + `frontend` из текущего дерева, поднимает
стенд (`down -v` → `up -d`), ждёт healthy, гоняет Playwright-спек,
на успехе роняет стек (`down -v`), на падении печатает хвост логов app.

## Переменные (всё с дефолтами)

| Переменная | Дефолт | Смысл |
|---|---|---|
| `E2E_ADMIN_PASSWORD` | `E2eAdminStr0ng!Pass` | bootstrap-admin (сильный — fail-fast SEC-14/SEC-68/SEC-80 иначе не стартуют) |
| `E2E_ADMIN_NEW_PASSWORD` | `E2eNewStr0ng!Pass12` | смена (forcePasswordChange у fresh bootstrap — часть сценария) |
| `E2E_JWT_SECRET` | свежий `openssl rand -base64 48` | JWT-секрет стенда |
| `APP_TAG` / `FRONTEND_TAG` | `e2e-test-12` | теги образов (НЕ `:latest` — не затирать локальное) |
| `E2E_SKIP_BUILD=1` | — | пропустить сборку (стенд уже собран) |
| `KEEP_E2E_UP=1` | — | оставить стек после прогона (отладка) |

## Порты (не конфликтуют с лабораторными/прод-стеками)

`E2E_APP_PORT=58080`, `E2E_FRONTEND_PORT=58081`, `E2E_DB_PORT=55432`,
`E2E_RABBITMQ_PORT=55672`, `E2E_RABBITMQ_MGMT_PORT=59300`.
Внутри сети wiring byte-identical проду (`postgres:5432`, `app:8080`).

## Отличия стенда от прода (только в `ci/docker-compose.e2e.yml`)

1. `ZORROBPM_SECURITY_COOKIE_SECURE=false` — E2E идёт по plain HTTP,
   браузер не хранит `Secure`-куки без TLS (`__Host-` требует Secure).
   Прод-дефолт `true` не тронут.
2. CORS-allowlist = E2E-origin (два свойства: `ZORROBPM_CORS_*` ×2 —
   Spring `WebConfiguration` и prod-yml читают разные ключи).
3. `SPRING_PROFILES_ACTIVE=dev` — дефолтные `zorrodev`-креды БД/брокера
   легитимны на тестовом стенде (fail-fast SEC-80 пропускает dev/test
   по построению). `dev`, не `test`: test-профиль уводит datasource
   на shared H2 мимо реального postgres. Admin-пароль/JWT — прямыми
   `ZORROBPM_SECURITY_*`-алиасами (в dev-профиле prod-yml не грузится).
4. `ZORROBPM_SECURITY_RATE_LIMIT_DATA_CAPACITY=10000` — за одним IP
   весь стенд делит один data-бакет (300/мин); ручной дебаг против
   поднятого стенда жрёт его вместе со спеком. Rate-limit доказывают
   его собственные IT, не этот спек. Прод-дефолт 300 не тронут.

## Ловушки (пойманы живьём при построении)

- `docker compose` APPEND'ит `ports` из override — сброс только через
  `!override`, иначе базовые 5432/5672/8080/8081 остаются и конфликтуют.
- `container_name` фиксированы — два стека рядом не живут; скрипт
  проверяет чужой запущенный стек fail-fast (точное совпадение имени —
  префикс-матч цеплял `raxon-lab-zorro-*` как «чужих»).
- Playwright 1.62: `page.request` НЕ наследует куки страницы для
  sub-запросов → API-setup через `request.newContext({storageState})`.
- API-контекст обязан слать `Origin`: CORS-гейт SEC-44 режет запросы
  без Origin 403 даже под SUPER_ADMIN-сессией.
- Смена пароля бампит `tokenVersion` (SEC-63) + отзывает refresh-токены →
  сессия умирает, спек перелогинивается (реальный путь пользователя).
- Login rate-limit 5/мин персистентен в БД стенда: `down -v` перед каждым
  прогоном (свежий bootstrap + пустое окно), ровно один логин на пароль.
- Хвост логов app — в файл (`E2E_APP_LOG_TAIL`), не в stdout: json-логи
  хоронят вывод playwright.
- Idle SSE молчит (ни байта без событий) — это норма, не поломка;
  проверять событиями, не наличием байтов.
