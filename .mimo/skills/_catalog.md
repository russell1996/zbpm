# Skills Catalog

Карточки ролей по [../standards/skill-template.md](../standards/skill-template.md). Инженерные роли несут
анти-имитационные инварианты ([rules V1–V10](../rules/rules.md)). Одна задача может задействовать несколько ролей.

## Cross-cutting
| Skill | Назначение | Гейт-владелец |
|---|---|---|
| [chief-architect](chief-architect.md) | системная архитектура, ADR, границы модулей | G1 |
| [senior-engineer](senior-engineer.md) | реализация задач по правилам, тесты, PR | G3 |
| [qa-architect](qa-architect.md) | стратегия качества, приёмка, анти-имитация | надзор |
| [test-architect](test-architect.md) | фальсифицируемые тесты, proof-of-failure | G4 |
| [code-review-architect](code-review-architect.md) | ревью по диску, верификация | G3 |
| [performance-architect](performance-architect.md) | замеры, бюджеты, узкие места | G5 |
| [security-architect](security-architect.md) | auth, secure-by-default, threat | G6 |
| [release-architect](release-architect.md) | гейт релиза, merge/тег (вердикт Claude) | G7 |
| [documentation-architect](documentation-architect.md) | единый источник истины, ADR-учёт | надзор |

## ZBPM-domain
| Skill | Зона ответственности (классы) |
|---|---|
| [execution-engine-architect](execution-engine-architect.md) | `ActivityServiceImpl`, токены/диспетчер/шлюзы/подпроцессы/MI |
| [persistence-architect](persistence-architect.md) | `DBServiceImpl`, JPA-сущности, Liquibase, локи/скоупы |
| [scheduler-architect](scheduler-architect.md) | `TimerScheduler`, таймеры/события, HA-координация |
| [feel-dmn-architect](feel-dmn-architect.md) | `ScriptServiceImpl`, `DmnServiceImpl`, FEEL/DMN/hit policies |

## Как выбрать роль
- Структурное изменение (модуль/контракт/модель исполнения) → **chief-architect** + ADR (G1) до кода.
- Обычная задача роадмапа → **senior-engineer** + профильный domain-architect для ревью дизайна.
- Любая задача проходит **test-architect** (G4) и **code-review-architect** (G3); релиз — только Claude (G7).
