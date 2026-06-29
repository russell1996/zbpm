# /.mimo — Операционная система разработки ZBPM

Это **операционка для команды Mimo**: как брать задачу, как доказывать, что сделано, какие гейты пройти,
чтобы Claude (CTO/governor) замёржил в master. Источник истины по продукту — [../docs/README.md](../docs/README.md)
(трекер + роадмап) и [../docs/analysis/](../docs/analysis/README.md) (as-is + коды наблюдений).

## Как пользоваться (короткий путь)
1. Берёшь **work order** из [workorders/](workorders/_index.md) (или Claude выдаёт промт по [prompts/task-prompt.md](prompts/task-prompt.md)).
2. Открываешь нужный **playbook** в [playbooks/](playbooks/) (feature/bugfix/refactoring/…).
3. Работаешь по правилам [rules/rules.md](rules/rules.md) — особенно **анти-имитационные V1–V10**.
4. Проходишь **чеклист этапа** [checklists/checklists.md](checklists/checklists.md).
5. Прогоняешь **quality gate** [quality-gates/gates.md](quality-gates/gates.md) для своего этапа.
6. Сдаёшь по [standards/definition-of-done.md](standards/definition-of-done.md) с **честным отчётом** (таблица критерий→команда→факт).
7. Claude ревьюит по диску → вердикт → merge (в master мёржит **только Claude**, после гейтов).

## Структура
| Каталог | Что внутри |
|---|---|
| [standards/](standards/) | skill-template, definition-of-done, conventions (стек ZBPM) |
| [rules/](rules/rules.md) | инварианты «всегда/никогда» + анти-имитационные V1–V10 |
| [quality-gates/](quality-gates/gates.md) | 7 объективных гейтов (architecture→release) |
| [workflows/](workflows/feature-flow.md) | поток Feature→…→Release с гейтами |
| [skills/](skills/_catalog.md) | карточки ролей (architect/engineer/qa/…) |
| [playbooks/](playbooks/) | пошаговые сценарии (feature/bugfix/refactor/test/review/release/incident/perf/doc-audit) |
| [checklists/](checklists/checklists.md) | чеклисты по этапам |
| [prompts/](prompts/) | переиспользуемые шаблоны (task/review/validation/escalation/handoff/system) |
| [workorders/](workorders/_index.md) | **готовые задачи** под роадмап (Фаза 1: T1/T4/T2) |

## Железные принципы (одной строкой)
- **Не верь словам — проверяй по диску** (grep/find/запуск). «Сделал в чате» ≠ «есть в репо».
- **Зелёный тест — не доказательство**, пока не показан красным на баге (proof-of-failure).
- **Код в файле ≠ сделано**: символ должен вызываться из рабочего пути + покрыт фальсифицируемым тестом.
- **Замеры, не оценки.** **Одна задача за раз.** **Честно «не смог» лучше фейка.**
- Архитектурные отклонения — через **ADR**, не молчком.

> Эта операционка — для governance-дисциплины, не бюрократии. Если шаг не повышает доказуемость или
> качество — он лишний. Но V1–V10 и DoD — не опциональны.
