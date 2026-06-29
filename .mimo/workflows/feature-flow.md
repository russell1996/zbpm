# Workflow — Feature Flow (с гейтами)

Единый поток. Любой проваленный гейт = возврат на предыдущий шаг. В master мёржит **только Claude**.

```
Feature/Task (из роадмапа docs/README.md, трассировка к коду наблюдения)
   │
   ├─▶ Architecture Review ──[G1]── (структурное? → ADR; иначе пропуск)
   │
   ├─▶ Design ──────────────[G2]── work order: объективные критерии (V2), файлы, миграции
   │
   ├─▶ Implementation ──────[G3]── код в feature-ветке; дифф ⊆ scope (V8); код вызывается (V5)
   │
   ├─▶ Testing ─────────────[G4]── фальсифицируемый тест + proof-of-failure (V3)
   │
   ├─▶ Performance ─────────[G5]── (если перф-чувствительно) замеры числом (V6)
   │
   ├─▶ Security ────────────[G6]── (если security-поверхность) тест 401/валидация (SEC*)
   │
   ├─▶ Code Review (Claude по диску) ── вердикт: принято / НЕ принято с фактами
   │
   └─▶ Release ─────────────[G7]── merge в master (ТОЛЬКО Claude); тег/релиз — Claude
```

## Шаги
1. **Взять задачу.** Из [../workorders/](../workorders/_index.md) или промт от Claude ([../prompts/task-prompt.md](../prompts/task-prompt.md)). Понять трассировку к P/M/B/T/F/S/SEC.
2. **Архитектура (G1).** Если меняется модуль/контракт/модель исполнения → ADR + эскалация (V10). Иначе пропустить.
3. **Дизайн (G2).** Зафиксировать объективные критерии приёмки, затрагиваемые файлы, план миграций.
4. **Реализация (G3).** Feature-ветка. Один scope. `mvn clean verify` зелёный. Показать call-site (V5).
5. **Тесты (G4).** Тест на поведение → показать **красным** на баге → зелёным после фикса (V3).
6. **Перф/Безопасность (G5/G6).** Где применимо — замеры/тесты доступа.
7. **Self-review** по [../checklists/checklists.md](../checklists/checklists.md) + [../standards/definition-of-done.md](../standards/definition-of-done.md).
8. **PR + отчёт.** Честный отчёт (таблица критерий→команда→факт). Открыть PR, не мёржить.
9. **Ревью Claude.** Claude проверяет по диску, прогоняет команды. Принято → merge. Нет → факты + следующий промт.

## Возвраты
- Провал G3 (build red / out-of-scope) → назад в Implementation.
- Провал G4 (нет proof-of-failure / слабый assert) → назад в Testing.
- Конфликт с ADR / нужно структурное изменение → стоп, эскалация ([../prompts/escalation.md](../prompts/escalation.md)).
