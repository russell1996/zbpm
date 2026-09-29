# ADR-4 — ZBPM Modeler (enterprise-замена Camunda Modeler)

Статус: **ОТМЕНЁН / SUPERSEDED** (продукт-владелец, 2026-07-15) — решение пересмотрено: собственный ZBPM Modeler
НЕ разрабатываем, моделирование ведём через **Camunda Modeler desktop** (внешний инструмент, import/export `.bpmn`).
Эпик EPIC-MODELER и WO-MOD-1..6 закрыты как cancelled. Ниже — исходный (недействующий) текст ADR.

~~Статус: ПРИНЯТ (продукт-владелец, 2026-07-15)~~
Автор: CTO / Chief Architect. Горизонт: многолетний независимый продукт экосистемы ZBPM.
Связь: [ADR-3 (формы)](ADR-3-embedded-forms.md), [integration-guide](../../README.md), [EPIC-MODELER](../../governance/archive/workorders/EPIC-MODELER.md).

## Ратифицированные решения (2026-07-15)
1. **D1 доставка:** **web-first** (встроенный `/ui/modeler`) → desktop (Electron, M3) на том же ядре.
2. **Ядро:** **сразу отдельный фреймворк-агностичный пакет `@zbpm/modeler-core`** + тонкая Vue-оболочка.
3. **Объём M0:** **полный BPMN-редактор сразу** — все zeebe-расширения в панели + полный ZBPM-lint + деплой
   + import/export + сервер-репозиторий.
4. **D4 хранение:** **сервер ZBPM (версии) + файлы** `.bpmn/.form/.dmn` (import/export).
5. Ядро рендеринга — стек **bpmn.io** (bpmn-js/dmn-js/form-js), оболочка **Vue**. DMN — M2.

---

## 0. Стартовая позиция — это НЕ greenfield
Проверено по диску, что уже есть и на что опираемся:
| Актив | Где | Роль в Modeler |
|---|---|---|
| `bpmn-js@18.18` | `widgets/bpmn/BpmnViewer.vue` (сейчас **viewer**) | ядро канваса → поднять до **Modeler** (редактирование) |
| `@bpmn-io/form-js@1.23` | FORM-3/6 (рендерер + редактор) | встроенный редактор форм |
| Deploy BPMN/форм | `POST /process-definitions`, `POST /forms` | прямой деплой из Modeler |
| DMN-движок | `DmnContract` (`/dmn`, `/dmn/{id}/evaluate`) | цель для `dmn-js` редактора |
| Парсинг zeebe-расширений | `engine/bpmn/xml/extension/*` (Assignment, FormDefinition, CalledDecision, IoMapping, TaskDefinition, UserTaskExtension, LoopCharacteristics, ZeebeScript, Subscription) | **execution-семантика** для панелей свойств |

Вывод: 60% фундамента заложено. Modeler — это **надстройка редактирования + панели свойств под ZBPM-семантику
+ валидация + деплой**, а не движок канваса с нуля.

---

## 1. Видение и позиционирование
**ZBPM Modeler** — визуальный редактор исполняемых моделей (BPMN 2.0, DMN 1.3, формы), нативно понимающий
execution-семантику движка ZBPM и деплоящий прямо в него. Замена Camunda Modeler со стратегическими отличиями:

| | Camunda Modeler | ZBPM Modeler |
|---|---|---|
| Доставка | только desktop (Electron) | **web-встроенный в консоль ZBPM** + desktop (позже) |
| Деплой | в Camunda 8 (Zeebe) | нативно в ZBPM (`/process-definitions`, `/forms`) |
| Формы/DMN | отдельные редакторы | **единый холст**: BPMN + форма + DMN в одном продукте |
| Семантика | Camunda-расширения | ровно те расширения, что исполняет ZBPM (нет «нарисовал, но движок не понял») |
| Валидация | bpmnlint | bpmnlint + **ZBPM-правила** (formId резолвится, FEEL-assignee валиден, called-decision существует) |

Ключевая ценность: **что нарисовал — то и исполнится**. Modeler и движок разделяют один контракт расширений.

---

## 2. Стратегические решения (форки — на ратификацию)

### D1. Модель доставки — **web-first, затем desktop** (рекомендация)
- Фаза 1: **встроенный в консоль ZBPM** раздел `/ui/modeler` (ноль установки, интегрирован с auth/деплоем).
- Фаза 3: **standalone desktop (Electron)** для оффлайна/файловой работы, **переиспользуя то же ядро** (общая
  JS-библиотека `@zbpm/modeler-core`).
- Альтернативы: desktop-only (как Camunda — но теряем интеграцию), web-only (нет оффлайна для разработчиков).

### D2. Ядро рендеринга — **стек bpmn.io** (bpmn-js / dmn-js / form-js), рекомендация
Промышленный стандарт, MIT, расширяемый, **уже частично в проекте**. Свой канвас = годы работы и мимо. bpmn.io —
самостоятельный MIT-проект (не заблокирован на Camunda). Строим ZBPM-слой поверх.

### D3. Оболочка/фреймворк — **Vue** (переиспользуем консоль), рекомендация
bpmn-js — vanilla-JS (встраивается в любой фреймворк). Vue-оболочка = переиспользование auth, дизайн-системы,
i18n, компонентов ZBPM. Ядро (`@zbpm/modeler-core`) — фреймворк-агностичное, чтобы Electron/embeddable его брали.

### D4. Хранение/версионирование — **ZBPM-сервер как репозиторий** + файлы (import/export), рекомендация
Процессы и формы уже версионируются в движке (`process_definitions`, `form`). Modeler читает/пишет туда (server
mode) + умеет `.bpmn/.form/.dmn` файлы (file mode, как Camunda). Реалтайм-коллаборация — отдельная поздняя фаза.

### D5. Объём MVP — **BPMN-редактирование + ZBPM-панель свойств + деплой** (M0)
Не тащить DMN/коллаборацию в MVP. Сначала — рабочий цикл «нарисовал процесс → настроил ZBPM-свойства →
провалидировал → задеплоил → запустил». Форм-редактор уже есть (FORM-6) — интегрируем, не пишем заново.

---

## 3. Архитектура (слои)
```
┌──────────────────────────────────────────────────────────────┐
│  Shell (Vue)  — /ui/modeler: холст, палитра, панель свойств,    │
│                 тулбар (деплой/валидация/импорт-экспорт), табы   │
├──────────────────────────────────────────────────────────────┤
│  @zbpm/modeler-core (фреймворк-агностичное ядро, TS)            │
│   ├─ BpmnEditor      (bpmn-js Modeler + палитра + контекст-пад) │
│   ├─ PropertiesPanel (bpmn-js-properties-panel + ZBPM-провайдер)│  ← ключевой ZBPM-слой
│   ├─ FormEditor      (form-js FormEditor — из FORM-6)           │
│   ├─ DmnEditor       (dmn-js — фаза M2)                         │
│   ├─ Validation      (bpmnlint + ZBPM-lint-правила)            │
│   ├─ ElementTemplates(шаблоны сконфигурированных элементов)     │
│   └─ DeployClient    (POST /process-definitions, /forms)        │
├──────────────────────────────────────────────────────────────┤
│  ZBPM Engine — контракт расширений (единый с движком),          │
│                деплой, серверная валидация, версии              │
└──────────────────────────────────────────────────────────────┘
```

### 3.1 ZBPM Properties Provider — сердце продукта
Кастомный провайдер для `bpmn-js-properties-panel`, редактирующий РОВНО те расширения, что исполняет движок
(маппинг 1:1 с `engine/bpmn/xml/extension/*`):
| Элемент BPMN | Группа свойств | Пишет в |
|---|---|---|
| User Task | Assignee/Candidate groups (с FEEL), Form (formId из `/forms`) | `zeebe:assignmentDefinition`, `zeebe:formDefinition` |
| Service Task | Task type/retries, IO-mapping | `zeebe:taskDefinition`, `zeebe:ioMapping` |
| Business Rule Task | Called decision (из `/dmn`), result var | `zeebe:calledDecision` |
| Call Activity | Called element | `zeebe:calledElement` |
| Message events | Subscription (correlation key, FEEL) | `zeebe:subscription` |
| Script Task | Скрипт/FEEL-выражение | `zeebe:script` |
| Multi-instance | Loop characteristics | `zeebe:loopCharacteristics` |
| Start event | Стартовая форма (formId) | `zeebe:formDefinition` |

Формы (formId) и решения (decisionId) выбираются из **живых списков** движка (`GET /forms`, `GET /dmn`) —
нельзя сослаться на несуществующее.

### 3.2 Валидация (двухуровневая)
- **Статическая (в Modeler):** `bpmnlint` (базовые BPMN-правила) + **ZBPM-правила**: `formId` резолвится в `/forms`;
  `calledDecision` есть в `/dmn`; FEEL-выражения (assignee/condition) парсятся; user-task имеет исполнителя;
  нет недостижимых узлов/висящих потоков.
- **Серверная (деплой-тайм):** движок уже валидирует BPMN при `POST /process-definitions` — Modeler показывает
  ошибки движка инлайн.

### 3.3 Element Templates
Механизм шаблонов (как Camunda element templates): преднастроенные элементы (напр. «REST-коннектор»,
«Email-задача») с зафиксированными свойствами и полями ввода → переиспользование, стандартизация.

---

## 4. Технологический стек
`TypeScript` · `bpmn-js` + `bpmn-js-properties-panel` + `@bpmn-io/properties-panel` · `bpmnlint` ·
`@bpmn-io/form-js` (есть) · `dmn-js` (M2) · оболочка `Vue 3 + Vite` (консоль) / `Electron` (desktop, M3) ·
ядро `@zbpm/modeler-core` (публикуемая TS-библиотека). Все зависимости — MIT.

---

## 5. Доставка/упаковка
1. **Web-модуль** в консоли ZBPM (`/ui/modeler`) — основная поставка.
2. **Standalone desktop (Electron)** — оффлайн, файловая система, авто-обновления (M3), то же ядро.
3. **Embeddable-библиотека** (`@zbpm/modeler-core`) — встраивание в сторонние продукты экосистемы.

---

## 6. Нефункциональные требования
Производительность на больших диаграммах (виртуализация bpmn-js, ленивые панели); undo/redo (нативно в bpmn-js);
autosave (server/file); i18n (en/ru/kz — как консоль); тема light/dark (как консоль); a11y (клавиатура, ARIA);
оффлайн (desktop); keyboard-shortcuts; экспорт PNG/SVG/PDF; импорт `.bpmn` Camunda (миграция расширений camunda→zeebe).

---

## 7. Дорожная карта (многолетняя, фазовая)
| Веха | Содержание | Итог |
|---|---|---|
| **M0 — MVP** | bpmn-js Modeler в `/ui/modeler`; палитра/контекст-пад; ZBPM properties provider (user/service/start); валидация базовая; деплой `POST /process-definitions`; импорт/экспорт `.bpmn` | Нарисовал → настроил → задеплоил → запустил |
| **M1 — Полнота BPMN** | все ZBPM-расширения в панели; ZBPM-lint полный; element templates; интеграция форм (formId picker → FORM-6) | Полноценный BPMN-редактор ZBPM |
| **M2 — DMN** | `dmn-js` редактор таблиц решений; деплой/привязка `calledDecision`; DRD | BPMN+DMN в одном продукте |
| **M3 — Desktop** | Electron-оболочка на общем ядре; оффлайн; файловый режим; авто-обновления | Замена Camunda Modeler для разработчиков |
| **M4 — Коллаборация/версии** | server-репозиторий версий; diff/сравнение диаграмм; комментарии; блокировки; (позже realtime) | Командная работа |
| **M5 — Экосистема** | плагины/marketplace шаблонов; симуляция/токен-плей; линт-правила как плагины | Платформа |

---

## 8. Риски и снятие
| Риск | Снятие |
|---|---|
| Зависимость от upstream bpmn.io | MIT + активный проект; форк как крайняя мера; изолируем через `@zbpm/modeler-core` |
| Scope creep (гонка за Camunda-паритетом) | Строгие вехи; MVP = деплой-цикл, не «всё сразу» |
| Рассинхрон Modeler ↔ движок | Единый контракт расширений; ZBPM-lint = отражение того, что движок исполняет; серверная валидация на деплое |
| Поддержка Electron | Desktop только M3; общее ядро минимизирует дублирование |
| Импорт Camunda-моделей | Отдельный конвертер camunda→zeebe расширений (M1) |

---

## 9. Governance (ручной деплой НЕ отменяется)
Modeler разрабатывается по тому же процессу: WO → дисковое ревью CTO + CI → **ручной мерж/деплой CTO** (G-G),
security-затрагивающие части — под red-team G-H. Деплой моделей из Modeler в движок — **явное действие
пользователя** (кнопка «Deploy»), не авто. Каждая веха M0..M5 разбивается на WO (WO-MOD-*), как эпик форм.

---

## 10. Открытые решения (ратифицировать)
1. **D1 доставка:** web-first + desktop (реком.) / desktop-only / web-only?
2. **D4 хранение:** server-репозиторий ZBPM + файлы (реком.) / только файлы / только сервер?
3. **Объём M0:** минимальный деплой-цикл (реком.) или сразу + формы/шаблоны?
4. **DMN-редактор:** нужен (M2) или ссылаться на внешний / не делать пока?
5. **Отдельный пакет `@zbpm/modeler-core`** (переиспользование в Electron/embed) — сразу выделять или монолит-Vue сначала?
