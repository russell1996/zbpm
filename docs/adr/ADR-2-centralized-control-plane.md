> ## ⛔ ЗАМЕЩЁН ADR-8 (2026-08-15) в трёх пунктах
>
> Отменены: **деплой только супер-админом**, **отсутствие авто-owner при деплое**, **создание токенов
> только супер-админом**. Причина: модель делает супер-админа узким горлышком в системе, где процессы
> заводят сами пользователи. См. [ADR-8](ADR-8-self-service-ownership.md).
>
> Остаётся в силе: централизованное управление пользователями и глобальными ролями, audit-log,
> один ключ на пользователя с гранатами по процессам (`api_key` + `api_key_grant`).

---

# ADR-2 — Centralized control plane + scoped API keys + audit

- **Статус:** Accepted (2026-07-12). Уточняет/заменяет части ADR-1: §2.2 (OWNER управляет членами),
  §2.4 (ключ per-process), §7 (deploy→owner self-service).
- **Контекст-владелец:** CTO, по решению продукт-владельца.

## Контекст
После выката MT-1..6 продукт-владелец финализировал модель: **вся настройка — за одним/несколькими
супер-админами**; пользователи только работают в выданных им процессах, видят и отзывают свои токены;
нужна **атрибуция «кто менял»**.

## Решение

### Роли
- **SUPER_ADMIN** — единственный control plane. Только он:
  - заводит пользователей и видит раздел **Пользователи**;
  - **деплоит** модели процессов;
  - **раздаёт доступы** к процессам (членство user↔процесс, роль OWNER/DESIGNER);
  - **создаёт API-токены** пользователям и **выбирает, к каким процессам** токен даёт доступ;
  - видит **audit-log** и управляет ключами любого.
- **USER** — работает в рамках выданных процессов:
  - **оперирует** ими через UI (cookie-сессия), в пределах роли членства;
  - **видит свои API-ключи, делает rotate (сброс) и revoke** — но НЕ создаёт и НЕ меняет их scope;
  - **НЕ** деплоит, **НЕ** раздаёт доступы, **НЕ** видит Пользователей.

### Чтение открыто / запись по доступу (подтверждает ADR-1 §2.1 + MT-3)
- **Смотреть** (list/GET любых процессов/инстансов/задач) — можно любому аутентифицированному.
- **Влиять** (start / complete / cancel / resolve) — только по процессам, к которым у субъекта есть доступ.
  Timur не может закрыть/повлиять на процесс без доступа; посмотреть — может.

### API-ключ — ОДИН на юзера + права по каждому процессу
```
api_key         id · owner_user_id (UNIQUE — один ключ на юзера, личность/аудит)
                · key_hash(SHA-256) · prefix · created_at · last_used_at · expires_at? · revoked_at?
api_key_grant   api_key_id · process_id · permissions[]   ← права ПО КАЖДОМУ процессу
                permissions ⊆ {START|FETCH_LOCK|COMPLETE_SERVICE_TASK|COMPLETE_USER_TASK|CORRELATE_MESSAGE}, или FULL
```
- **Один ключ на пользователя** (`owner_user_id` UNIQUE) — его единственный интеграционный креденшл. Атрибуция в аудите: «ключ timur сделал X».
- **Права настраиваются ПО КАЖДОМУ процессу** (`api_key_grant`): на процесс A — набор прав, на B — FULL, на C — нет гранта = нет доступа.
- Настраивает **super-admin**. Ограничение: грант можно дать только на процесс, к которому владелец допущен (членство).

### canOperate (ServicePrincipal)
`canOperate(sa, processKey, action)`:
- управляющее (DEPLOY/MANAGE_MEMBERS/DELETE_PROCESS) → **всегда false**;
- runtime → есть `api_key_grant(sa.key, целевой process)` И (`action ∈ grant.permissions` ИЛИ grant.FULL).
`canCompleteUserTask(SA)`: грант на process задачи содержит `COMPLETE_USER_TASK` (или FULL).

### Управляющие действия — только SUPER_ADMIN (уточняет ADR-1)
`canOperate(UserPrincipal, …, {DEPLOY, MANAGE_MEMBERS, DELETE_PROCESS})` → только `isSuperAdmin`.
OWNER/DESIGNER (обычный USER) получают **только runtime-операции** над выданными процессами.

### Управление ключами
- **Super-admin**: создать ключ юзеру (один) + **настроить гранты по процессам** (на каждый — набор прав
  или FULL); add/edit/remove грант; rotate/revoke; всё над любым юзером.
- **USER** (`/me/api-key`): **видит свой ключ + его гранты (read-only), делает rotate (новый секрет, те же
  гранты) и revoke**. Создание ключа и настройку грантов — НЕ может.

### Audit-log (кто менял)
```
audit_log   id · principal_type(USER|API_KEY) · principal_id · owner_user_id
            · action · process_key · target_id · at
```
Каждая **мутация** (start/complete/cancel/resolve/deploy/member-grant/key-create-rotate-revoke) → запись.
Super-admin видит журнал. Так на любой вопрос «кто менял процесс X» есть ответ.

### Users / user management
Раздел «Пользователи» (UI) и backend `/users/**` — **только `SUPER_ADMIN`** (не legacy `ADMIN`).

## Что меняется vs построенное
| Компонент | ADR-1 / MT | ADR-2 |
|---|---|---|
| API-ключ | per-process (MT-4) | **один ключ на юзера + права по процессам** (`api_key`+`api_key_grant`) — MT-4 superseded |
| Кто создаёт/настраивает | OWNER/super (MT-4) | **super-admin** создаёт ключ + гранты; USER — только view/rotate/revoke свой |
| Членство (раздача доступа) | OWNER или super (MT-5) | **только SUPER_ADMIN** |
| Деплой | self-service, deployer→OWNER (MT-5) | **только SUPER_ADMIN**, без авто-owner |
| Users guard | `requiresAdmin` (вкл. ADMIN) | **SUPER_ADMIN only** |
| Аудит | только `last_used_at` | **audit_log** мутаций |

## Последствия
- Централизованная модель, простая атрибуция (owner_user_id + audit_log).
- **Trade-off:** утёкший ключ открывает процессы своего scope (не всё). Явный scope = меньше радиус поражения
  и точнее аудит (по токену видно, какая интеграция что сделала). Митигация — минимальные `permissions[]` + revoke.
- Реализация: **WO-MT-7** (централизация control plane) → **WO-MT-8** (один ключ на юзера `api_key` +
  права по процессам `api_key_grant`, super-admin создаёт+настраивает, `/me/api-key` view/rotate/revoke) →
  **WO-MT-9** (фронт) → **WO-MT-10** (audit-log).
