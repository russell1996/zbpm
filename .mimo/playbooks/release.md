# Playbook — Release

Гейт G7. Merge в master и тег — **только Claude**.

1. **Гейты зелёные.** Все релевантные G1–G6 пройдены (с доказательствами).
2. **Полная сборка.** `mvn clean verify` (10/10 модулей) + `npm run build` — зелёные, прогоняет Claude сам (V1).
3. **DoD.** [definition-of-done](../standards/definition-of-done.md) закрыт; отчёт честный (НЕ СДЕЛАНО — явно).
4. **Сверка доков.** Контракт/`docs/` обновлены; единый роадмап без противоречий.
5. **Merge.** Claude: `git checkout master && git merge --no-ff <branch>`; сообщение по conventional commits.
6. **Тег/релиз (если применимо).** Версия + CHANGELOG — Claude; согласовать с [deployment](../../docs/deployment.md). Образы по `$CI_COMMIT_SHORT_SHA`.
7. **Откат.** Убедиться, что откат предусмотрен (CI rollback / previous tag).

**Анти-имитация:** не мёржить с красным verify; не верить «зелёному» из отчёта — перепроверить диск; security-изменение без G6 не релизится.
