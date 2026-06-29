# Playbook — Feature

Новая возможность из роадмапа. Поток: [feature-flow](../workflows/feature-flow.md). Гейты: G2→G3→G4(→G5/G6)→G7.

1. **Контекст.** `git status`+`git log`. Прочитать work order; найти трассировку (P/M/B/T/F/S/SEC) в [analysis](../../docs/analysis/README.md).
2. **Дизайн (G2).** Контракт API / точки в коде / миграции. Структурное? → ADR (G1, эскалация chief-architect).
3. **Ветка.** `git checkout -b feature/<scope>-<short>`.
4. **Тест сначала.** Написать падающий тест на целевое поведение (red). Это и есть proof-of-failure (V3).
5. **Реализация (G3).** Минимально и в scope (V8). Код **вызывается** из рабочего пути (V5).
6. **Зелёный.** Тест зелёный; `mvn clean verify` (или `npm run build`) зелёный.
7. **Перф/Безопасность.** Если применимо — замеры (G5)/тест доступа (G6).
8. **Доки/контракт.** Обновить контракт и `docs/` при изменении API/поведения.
9. **Self-review** ([checklists](../checklists/checklists.md)) + [DoD](../standards/definition-of-done.md).
10. **PR + отчёт** (таблица критерий→команда→факт). Не мёржить. Claude ревьюит → merge.

**Анти-имитация:** не happy-path-as-proof; не «метод в файле» без call-site; не косметика вместо сути.
