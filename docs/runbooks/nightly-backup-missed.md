# Runbook: ночной бэкап PG не отработал — как понять и что делать

Ночной бэкап делает job `backup:pg` в schedule-пайплайне расписания №56
(`0 2 * * *`, master): `ci/pg-backup.sh` → `pg_dump -Fc` через
`docker exec zorrobpm-postgres`, файлы
`/opt/zorro-bpm/backups/zorrobpm-db_<UTC-timestamp>.dump`, ретенция 14 суток.
Восстановление из дампа — `docs/runbooks/pg-restore.md`.

## Как понять, что бэкап не отработал

1. **Возраст последнего дампа** (норма — младше 26 ч):
   `ls -lt /opt/zorro-bpm/backups/ | head -5`.
2. **Статус джобы в GitLab**: Pipelines → источник «Schedule» → job `backup:pg`.
   `failed` = упал сам бэкап (лог джобы — причина);
   `canceled` при работавшем пайплайне = смотреть пункт 3.
3. **Сторож свежести** (`ci/check-backup-freshness.sh`, шаг той же джобы):
   падает видимым `FAIL: newest dump … is Nh old (limit 26h)` — значит дамп
   писался, но старше 26 ч (зависший/тихо неуспешный `pg_dump`).

Что НЕ является проблемой: `backup:pg` в статусе `skipped` на push/MR-пайплайнах —
так задумано (джоба только на schedule, WO-REL-67).

## Известные причины и действия

| Симптом | Причина | Действие |
|---|---|---|
| `backup:pg` canceled в ночь пушей | Авто-отмена GitLab (прецедент: пайплайн 176380, 2026-10-07 — ночь 06→07.10 без дампа). Защита — `interruptible: false` у джобы (WO-REL-67): авто-отмена её не трогает | Убедиться, что `interruptible: false` на месте (`grep -A2 '^backup:pg' .gitlab-ci.yml`); разовый пропуск закрыть ручным запуском schedule-пайплайна |
| `FAIL: newest dump … is Nh old` | `pg-backup.sh` отработал, но свежий дамп не записан (диск/упавший postgres) | Место: `bash ci/disk-space-check.sh`; БД: `docker compose logs postgres`; затем ручной перезапуск `backup:pg` |
| Пустой каталог / нет `.dump` вообще | Бэкап не писался дольше ретенции | Восстанавливать не из чего — проверить, что расписание №56 включено в GitLab (владелец правит расписание, не исполнитель); после починки — ручной прогон |
| Ночной пайплайн содержит build/test/cve-джобы | Регресс schedule-разреза (WO-REL-67): в пайплайне с источником schedule должны быть ТОЛЬКО `backup:pg`, `test:chaos`, `test:e2e-login` | Страж `ScheduleSplitGuardTest` (модуль `zorrobpm-app`) ловит это в CI; чинить `rules` в `.gitlab-ci.yml`, сверять состав через Lint API (`POST /ci/lint` с `dry_run` + `include_jobs`) |

## Ручной перезапуск

GitLab → Build → Pipeline schedules → №56 → «Run pipeline» (только владелец/CTO:
расписание и ручной запуск — не действия исполнителя). Проверка после:
свежий `.dump` в каталоге + `pg_restore --list` показывает оглавление
(подробно — `docs/runbooks/pg-restore.md`, раздел «Если бэкапа нет»).

## Проверено

Сторож: `ci/test-check-backup-freshness.sh all` (свежий/протухший/пустой/
граница 25ч при лимите 26ч + лимит из окружения). Авто-отмена: механизм
подтверждён документацией GitLab (`workflow:auto_cancel:on_new_commit`:
режимы `interruptible`/`conservative` не отменяют `interruptible: false`) и
живым Lint API при сдаче WO-REL-67.
