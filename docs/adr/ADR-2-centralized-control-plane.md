# ADR-2 — Centralized control plane + owner-scoped API keys

- **Статус:** Accepted (2026-07-12). Уточняет/заменяет части ADR-1: §2.2 (OWNER управляет членами),
  §2.4 (ключ per-process), §7 (deploy→owner self-service).
- **Контекст-владелец:** CTO, по решению продукт-владельца.

## Контекст
После выката MT-1..6 продукт-владелец уточнил целевую модель: **вся настройка — за одним/несколькими
супер-админами**. Пользователи только работают в рамках выданных им процессов и сами генерят себе токен.

## Решение

### Роли
- **SUPER_ADMIN** — единственный control plane. Только он:
  - заводит пользователей и видит раздел **Пользователи**;
  - **деплоит** модели процессов;
  - **раздаёт доступы** к процессам (членство user↔процесс с ролью OWNER/DESIGNER);
  - управляет ключами любого пользователя (может сгенерить и отдать).
- **USER** — работает в рамках выданных процессов:
  - оперирует ими через UI (cookie-сессия), в пределах роли членства (OWNER/DESIGNER);
  - **сам генерит себе owner-scoped API-токен**;
  - НЕ деплоит, НЕ раздаёт доступы, НЕ видит Пользователей.

### API-ключ — owner-scoped (заменяет per-process из ADR-1 §2.4)
```
api_key
 ├── owner_user_id → ui_users      (НЕ process_id)
 ├── key_hash (SHA-256) · prefix
 ├── permissions[]  (START | FETCH_LOCK | COMPLETE_SERVICE_TASK | COMPLETE_USER_TASK | CORRELATE_MESSAGE)
 └── created_at · last_used_at · expires_at? · revoked_at?
```
Доступ ключа = **все процессы, где `owner_user_id` — член (OWNER/DESIGNER)**, с правами `permissions[]`.
Динамика: супер-админ выдал юзеру новый процесс → тот же токен сразу работает, без перевыпуска.

### canOperate (ServicePrincipal)
`canOperate(sa, processKey, action)`:
- `action` — управляющее (DEPLOY/MANAGE_MEMBERS/DELETE_PROCESS) → **всегда false** (SA не рулит);
- runtime-действие → `action ∈ sa.permissions` **И** `sa.ownerUserId` — член целевого процесса (OWNER/DESIGNER).

### Управляющие действия — только SUPER_ADMIN (уточняет ADR-1)
`canOperate(UserPrincipal, …, {DEPLOY, MANAGE_MEMBERS, DELETE_PROCESS})` → только `isSuperAdmin`.
OWNER/DESIGNER (обычный USER) получают **только runtime-операции** над своими процессами, НЕ управление.

### Управление ключами
- **`/me/api-keys`** — пользователь управляет СВОИМИ ключами (create show-once / list / rotate / revoke).
- **Супер-админ** — управляет ключами любого пользователя (`/admin/users/{id}/api-keys` или аналог).

### Users / user management
Раздел «Пользователи» (UI) и backend `/users/**` — **только `SUPER_ADMIN`** (не legacy `ADMIN`).

## Что меняется vs построенное
| Компонент | ADR-1 / MT | ADR-2 |
|---|---|---|
| API-ключ | per-process (MT-4) | **owner-scoped** — MT-4 **superseded** |
| Управление ключами | вкладка на процессе (MT-6) | **`/me/api-keys`** + админ-вид |
| Членство (раздача доступа) | OWNER или super-admin (MT-5) | **только SUPER_ADMIN** |
| Деплой | self-service, deployer→OWNER (MT-5) | **только SUPER_ADMIN**, без авто-owner |
| Users guard | `requiresAdmin` (вкл. ADMIN) | **SUPER_ADMIN only** |

## Последствия
- Простая ментальная модель: один control plane, юзер — только исполнитель + свой токен.
- **Trade-off:** owner-scoped ключ при утечке открывает все процессы владельца (не один). Норма (как PAT);
  митигация — минимальные `permissions[]` + revoke + rotate.
- Реализация: WO-MT-7 (централизация control plane) → WO-MT-8 (owner-scoped keys) → WO-MT-9 (фронт).
