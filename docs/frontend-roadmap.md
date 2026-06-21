# ZorroBPM Frontend — Product Vision, Architecture & Roadmap

---

# 1. Product Vision

## 1.1 Что такое ZorroBPM

ZorroBPM — современная BPM-платформа на Spring Boot, объединяющая в одном продукте функциональность, которую Camunda 8 разбивает на три отдельных приложения: **Operate** (мониторинг процессов), **Tasklist** (управление задачами) и **Cockpit** (аналитика и администрирование).

**Позиционирование**: лёгкая, self-hosted альтернатива Camunda 8 для команд, которые хотят BPM-платформу без enterprise-лицензии и сложной кластерной инфраструктуры.

## 1.2 Конкурентные преимущества

| Camunda 8 | ZorroBPM |
|---|---|
| 3 отдельных приложения (Operate, Tasklist, Cockpit) | **Один SPA** с unified navigation |
| Complex Kubernetes deployment | **Docker Compose** — один `docker compose up` |
| Enterprise license для кластера | **Apache 2.0** — полностью open source |
| Zeebe protocol (gRPC) | **REST API** — стандартный HTTP |
| Обязательный Keycloak | **Optional** Keycloak + white list |

## 1.3 Целевая аудитория

- **Команды 5-50 человек**, внедряющие workflow-автоматизацию
- **Стартапы**, которым нужен BPM без vendor lock-in
- **Enterprise-команды**, ищущие light-weight alternative для не-критичных процессов
- **BPM-консультанты**, которым нужна быстрая прототипизация

## 1.4 Ключевые принципы

1. **Unified Experience** — одно приложение, одна навигация, один пользователь
2. **Developer-first** — REST API-first, CLI-friendly, easy to integrate
3. **Minimal Infrastructure** — PostgreSQL + RabbitMQ + JVM = всё
4. **BPMN 2.0 Native** — полная поддержка стандарта + Zeebe-расширения
5. **Observability Built-in** — инциденты, метрики, история — из коробки

---

# 2. Unified Product Experience

## 2.1 Принцип

**Весь функционал — в одном SPA.** Пользователь не должен знать, что внутри существуют разные подсистемы. Нет отдельных приложений, отдельных URL-пространств, отдельных авторизаций.

### Что НЕ делаем

- ❌ Отдельный Tasklist-app на `/tasklist`
- ❌ Отдельный Cockpit-app на `/cockpit`
- ❌ Отдельный Operate-app on `/operate`
- ❌ Микрофронтенды с разными сборками
- ❌ Разные ключи localStorage/sessionStorage

### Что делаем

- ✅ **Один SPA** на `zorrobpm.example.com`
- ✅ **Одна авторизация** (Keycloak OIDC)
- ✅ **Один Layout** с sidebar navigation
- ✅ **Единый context** — процесс → задача → инцидент → переменные
- ✅ **Cross-linking** — из задачи в процесс, из процесса в инцидент, из инцидента в переменные

## 2.2 Навигационная модель

```
┌─────────────────────────────────────────────────────────┐
│ Header: Logo │ Search │ Notifications │ User Menu       │
├──────────┬──────────────────────────────────────────────┤
│          │                                              │
│ Sidebar  │           Main Content Area                  │
│          │                                              │
│ Dashboard│  ┌──────────────────────────────────────┐    │
│ Processes│  │  Breadcrumbs: Processes > PI-123 >    │    │
│ Tasks    │  │  Variables                          │    │
│ Incidents│  │                                      │    │
│ Messages │  │  Content...                         │    │
│ Timers   │  │                                      │    │
│ Analytics│  └──────────────────────────────────────┘    │
│ Admin    │                                              │
│          │                                              │
└──────────┴──────────────────────────────────────────────┘
```

---

# 3. Backend Analysis

## 3.1 REST API Map

### Process Definitions

| Endpoint | Метод | Назначение | DTO |
|---|---|---|---|
| `/process-definitions` | `POST` | Деплой BPMN XML | `AddProcessDefinitionDTO { bpmn: String }` |
| `/process-definitions` | `GET` | Список определений (постранично) | Query: `pageIndex, pageSize, name, processDefinitionKey, processDefinitionVersion, latestVersionOnly` → `PagedDataDTO<ProcessDefinition>` |
| `/process-definitions/{id}` | `GET` | Метаданные определения | → `ProcessDefinition { id, key, version, name, sha256, createdAt, startFormKey }` |
| `/process-definitions/{id}/xml` | `GET` | Исходный BPMN XML | → `String` |
| `/process-definitions/{id}/structure` | `GET` | Разобранная структура BPMN | → `BpmnProcessStructure { id, key, version, name, nodes[], flows[] }` |

### Runtime

| Endpoint | Метод | Назначение | DTO |
|---|---|---|---|
| `/process-instances` | `POST` | Запуск экземпляра | `StartProcessInstanceDTO { processDefinitionId?, processDefinitionKey?, processDefinitionVersion?, variables[] }` → `IdDTO` |
| `/service-tasks/{id}/complete` | `POST` | Завершить сервис-задачу | `CompleteTaskDTO { variables[] }` → `IdDTO` |
| `/user-tasks/{id}/complete` | `POST` | Завершить пользовательскую задачу | `CompleteTaskDTO { variables[] }` → `IdDTO` |
| `/incidents/{id}/resolve` | `POST` | Разрешить инцидент | `ResolveIncidentDTO { id, variables[] }` → `IdDTO` |

### Queries

| Endpoint | Метод | Query Params | Result |
|---|---|---|---|
| `/process-instances` | `GET` | `parentProcessInstanceId, processDefinitionId, processDefinitionKey, processDefinitionVersion, pageIndex, pageSize` | `PagedDataDTO<ProcessInstance>` |
| `/user-tasks` | `GET` | `processInstanceId, assignee, candidateGroup, candidateUser, completed, assigned, pageIndex, pageSize` | `PagedDataDTO<UserTask>` |
| `/service-tasks` | `GET` | `processInstanceId, jobType, completed, pageIndex, pageSize` | `PagedDataDTO<ServiceTask>` |
| `/incidents` | `GET` | `processInstanceId, processDefinitionId, processDefinitionKey, processDefinitionVersion, bpmnElementId, pageIndex, pageSize` | `PagedDataDTO<Incident>` |
| `/variables` | `GET` | `processInstanceId, name, type, value, pageIndex, pageSize` | `PagedDataDTO<ProcessVariable>` |

## 3.2 Сущности

```
ProcessDefinition
  ├─ id: UUID
  ├─ key: String (process ID в BPMN)
  ├─ version: Integer
  ├─ name: String
  ├─ sha256: String
  ├─ createdAt: Instant
  └─ startFormKey: String?

ProcessInstance
  ├─ id: UUID
  ├─ parentActivityId: UUID? (для call activity)
  ├─ processDefinitionId: UUID
  ├─ startedAt: Instant
  └─ completedAt: Instant?

UserTask
  ├─ id: UUID
  ├─ code: String?
  ├─ name: String?
  ├─ processInstanceId: UUID
  ├─ processDefinitionId: UUID
  ├─ formKey: String?
  ├─ createdAt: Instant
  └─ completedAt: Instant?

ServiceTask
  ├─ id: UUID
  ├─ code: String?
  ├─ name: String?
  ├─ processInstanceId: UUID
  ├─ processDefinitionId: UUID
  ├─ job: String (job type)
  ├─ createdAt: Instant
  └─ completedAt: Instant?

Incident
  ├─ id: UUID
  ├─ activityId: UUID
  ├─ message: String
  ├─ createdAt: Instant
  └─ completedAt: Instant?

ProcessVariable
  ├─ name: String
  ├─ value: String
  └─ type: ProcessVariableType (STRING | UUID | LONG | BOOLEAN)

BpmnProcessStructure
  ├─ id: UUID
  ├─ key: String
  ├─ version: Integer
  ├─ name: String
  ├─ nodes: BpmnNode[]
  └─ flows: BpmnFlow[]

BpmnNode
  ├─ id: String
  ├─ name: String?
  ├─ type: String (startEvent, serviceTask, exclusiveGateway, ...)
  ├─ eventDefinition: String? (message, timer, error, signal, ...)
  ├─ incoming: String[]
  ├─ outgoing: String[]
  ├─ properties: Map<String, Object>
  ├─ boundaryEvents: BpmnNode[]
  └─ children: BpmnScope? (для embedded subprocess)

BpmnFlow
  ├─ id: String
  ├─ name: String?
  ├─ sourceRef: String
  ├─ targetRef: String
  └─ conditionExpression: String? (FEEL)
```

## 3.3 Ограничения Backend (для Frontend)

| Ограничение | Влияние на Frontend |
|---|---|
| Нет аутентификации | Frontend должен реализовать Keycloak OIDC |
| `ProcessInstance` не содержит `processDefinitionKey`/`version` | Нужен JOIN через `processDefinitionId` для отображения key/name |
| `ProcessVariable` — только `name/value/type` | Нет scope, нет versioning, нет metadata |
| `Incident` не содержит `processDefinitionId` | Нужен JOIN через `activityId` → `Activity` → `processInstanceId` → `ProcessInstance` |
| Нет endpoints для statistics/analytics | Frontend должен агрегировать данные из существующих queries |
| `UserTask` не содержит `assignee`/`candidateUsers`/`candidateGroups` | Данные есть в BPMN, но не в API response |
| `BpmnProcessStructure` — нет live-данных (active tokens) | Нужно комбинировать с Activity query |
| CORS полностью открыт | Ограничивать на уровне nginx/proxy |

## 3.4 Карта доступных возможностей

```
✅ ДЕПЛОЙ
  ├── Загрузка BPMN XML
  ├── Версионирование (auto)
  └── Просмотр структуры BPMN

✅ ПРОЦЕССЫ
  ├── Запуск экземпляров
  ├── Список экземпляров
  ├── Фильтрация по определению
  ├── Просмотр переменных
  └── Завершение/отмена (через terminate end)

✅ ЗАДАЧИ
  ├── Пользовательские задачи (список, фильтрация)
  ├── Сервисные задачи (список, фильтрация)
  ├── Завершение задач
  └── Передача переменных

✅ ИНЦИДЕНТЫ
  ├── Список инцидентов
  ├── Фильтрация
  └── Разрешение (re-execute)

✅ BPMN СТРУКТУРА
  ├── JSON-представление графа
  ├── Узлы с properties
  ├── Рёбра с conditions
  └── Boundary events (nested)

❌ ОТСУТСТВУЕТ (нужен Backend)
  ├── Таймеры (список/управление)
  ├── Сообщения (список/отправка)
  ├── Сигналы (список/отправка)
  ├── Статистика/аналитика
  ├── Массовые операции
  ├── Экспорт данных
  └── Real-time обновления (WebSocket/SSE)
```

---

# 4. User Roles

## 4.1 Administrator

**Задачи**: Управление пользователями, настройка системы, мониторинг здоровья

| Раздел | Действия |
|---|---|
| Dashboard | Обзор системы, здоровье сервисов |
| Administration → Users | CRUD пользователей, активация/блокировка |
| Administration → Settings | Настройка Keycloak, CORS, limits |
| Processes | Деплой новых BPMN, управление версиями |
| Incidents | Просмотр и разрешение критических инцидентов |

## 4.2 BPM Developer

**Задачи**: Разработка и деплой процессов, отладка, тестирование

| Раздел | Действия |
|---|---|
| Processes | Деплой BPMN, просмотр структуры, запуск тестов |
| Process Details | BPMN Viewer, variables, execution history |
| Tasks | Просмотр задач для отладки |
| Incidents | Анализ ошибок, переменные, стек |
| Analytics | Статистика по процессам |

## 4.3 Business Analyst

**Задачи**: Анализ процессов, KPI, отчёты

| Раздел | Действия |
|---|---|
| Dashboard | KPI, SLA, тренды |
| Processes | Список процессов, фильтрация по статусу |
| Analytics | Графики, отчёты, статистика |
| Process Details | История выполнения, время по элементам |

## 4.4 Operator

**Задачи**: Обработка задач, разрешение инцидентов

| Раздел | Действия |
|---|---|
| Dashboard | Мои задачи, инциденты, таймеры |
| Tasks | Обработка пользовательских задач |
| Incidents | Разрешение инцидентов |
| Process Details | Просмотр экземпляра, переменные |

## 4.5 End User

**Задачи**: Завершение задач, передача данных

| Раздел | Действия |
|---|---|
| Tasks | Мои задачи |
| Task Details | Заполнение формы, завершение задачи |

---

# 5. Information Architecture

## 5.1 Sidebar Navigation

```
┌─────────────────────────┐
│ 🔷 ZorroBPM             │
├─────────────────────────┤
│ 📊 Dashboard            │
├─────────────────────────┤
│ 🔄 Processes            │
│   ├─ Definitions        │
│   ├─ Instances          │
│   └─ Deploy             │
├─────────────────────────┤
│ 📋 Tasks                │
│   ├─ My Tasks           │
│   ├─ All Tasks          │
│   └─ Completed          │
├─────────────────────────┤
│ ⚠️ Incidents            │
├─────────────────────────┤
│ 📈 Analytics            │
│   ├─ Overview           │
│   ├─ Process Metrics    │
│   └─ SLA Reports        │
├─────────────────────────┤
│ 🔧 Administration       │
│   ├─ Users              │
│   └─ Settings           │
├─────────────────────────┤
│ ❓ Help                 │
└─────────────────────────┘
```

## 5.2 Header

```
┌──────────────────────────────────────────────────────────┐
│ [Logo] ZorroBPM   [🔍 Search...]   [🔔 3] [👤 John D.] │
└──────────────────────────────────────────────────────────┘
```

- **Search**: глобальный поиск по process definitions, process instances, tasks, incidents
- **Notifications**: bell icon с badge count (инциденты, задачи)
- **User Menu**: profile, settings, logout

## 5.3 Breadcrumbs

```
Processes > Process Definitions > Order Processing v3
Processes > Process Instances > PI-a1b2c3d4
Tasks > My Tasks > UT-x1y2z3
Incidents > INC-m1n2o3
```

## 5.4 URL Structure

```
/                                    → Dashboard
/processes/definitions               → Список определений
/processes/definitions/:id           → Детали определения
/processes/definitions/:id/xml       → BPMN XML (view)
/processes/deploy                    → Деплой BPMN
/processes/instances                 → Список экземпляров
/processes/instances/:id             → Детали экземпляра
/tasks                               → Мои задачи
/tasks/all                           → Все задачи
/tasks/completed                     → Завершённые задачи
/tasks/:id                           → Детали задачи
/incidents                           → Список инцидентов
/incidents/:id                       → Детали инцидента
/analytics                           → Аналитика
/analytics/processes                 → Метрики по процессам
/analytics/sla                       → SLA отчёты
/admin/users                         → Управление пользователями
/admin/settings                      → Настройки системы
```

---

# 6. Dashboard

## 6.1 Виджеты

### Row 1: KPI Cards

| Виджет | Источник данных | Формула |
|---|---|---|
| Active Processes | `GET /process-instances?completed=false` | `totalElements` |
| Open Tasks | `GET /user-tasks?completed=false` | `totalElements` |
| Open Incidents | `GET /incidents?completedAt=null` | `totalElements` |
| Completed Today | `GET /process-instances?completed=true&startedAt=today` | `totalElements` |

### Row 2: Charts

| Виджет | Тип | Описание |
|---|---|---|
| Process Instances Trend | Line chart | Запуски/завершения за 7/30 дней |
| Task Distribution | Doughnut | Active vs Completed vs Overdue |
| Incident Heatmap | Bar chart | Инциденты по process definitions |

### Row 3: Tables

| Виджет | Данные | Строки |
|---|---|---|
| Recent Incidents | `GET /incidents` (last 10) | id, process, message, createdAt |
| My Open Tasks | `GET /user-tasks?assignee=me&completed=false` | id, name, process, createdAt |
| Active Timers | Future: `GET /timer-jobs?active=true` | id, process, dueAt |

## 6.2 UX Dashboard

- **Auto-refresh**: каждые 30 секунд (configurable)
- **Date range picker**: для фильтрации графиков
- **Quick actions**: Deploy BPMN, Start Process, Create Task
- **Responsive**: адаптивный grid (1/2/3 колонки)

---

# 7. Process Monitoring

## 7.1 Process Definitions List

**Endpoint**: `GET /process-definitions`

**Экран**: таблица с колонками:

| Колонка | Источник | Сортировка |
|---|---|---|
| Name | `ProcessDefinition.name` | ✅ |
| Key | `ProcessDefinition.key` | ✅ |
| Version | `ProcessDefinition.version` | ✅ |
| Created | `ProcessDefinition.createdAt` | ✅ |
| SHA-256 | `ProcessDefinition.sha256` | — |
| Actions | View / Start / Deploy XML | — |

**Фильтры**:
- Name (текстовый поиск)
- Key (точное совпадение)
- Version (число)
- Latest version only (чекбокс)

**Действия**:
- Клик по строке → Process Definition Details
- "Start Process" → модалка с переменными
- "View XML" → модалка с BPMN XML
- "View Structure" → BPMN Viewer

## 7.2 Process Definition Details

**Endpoint**: `GET /process-definitions/{id}` + `GET /process-definitions/{id}/structure`

**Layout**:

```
┌──────────────────────────────────────────────────────────┐
│ Header: Order Processing v3                              │
│ Key: order-processing | Version: 3 | Created: 2024-01-15│
├──────────────────────────────────────────────────────────┤
│ [BPMN Viewer]          │  [Instances Tab] [Variables Tab]│
│                        │                                 │
│  ◉ Start → ○ Task1    │  Recent instances:              │
│       ↓               │  PI-abc123 - Running - 2m ago   │
│  ○ Gateway → ○ Task2  │  PI-def456 - Completed - 1h ago │
│       ↓               │                                 │
│  ◉ End                 │                                 │
│                        │                                 │
├──────────────────────────────────────────────────────────┤
│ Statistics: Active: 5 | Completed: 142 | Incidents: 2   │
└──────────────────────────────────────────────────────────┘
```

**Tabs**:
- **BPMN Viewer** — визуализация графа с активными элементами
- **Instances** — список экземпляров этого определения
- **Variables** — переменные по умолчанию (если есть startFormKey)
- **XML** — исходный BPMN XML

## 7.3 Process Instance Details

**Endpoint**: `GET /process-instances?id={id}` + `GET /variables?processInstanceId={id}` + `GET /incidents?processInstanceId={id}` + `GET /user-tasks?processInstanceId={id}` + `GET /service-tasks?processInstanceId={id}`

**Layout**:

```
┌──────────────────────────────────────────────────────────┐
│ Header: Process Instance PI-a1b2c3d4                     │
│ Definition: Order Processing v3 | Started: 2024-01-15    │
│ Status: Running | Duration: 2m 15s                       │
├──────────────────────────────────────────────────────────┤
│ [BPMN Flow] [Variables] [Tasks] [Incidents] [History]   │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ Tab: BPMN Flow                                           │
│ ┌────────────────────────────────────────────────────┐   │
│ │ BPMN Viewer с подсветкой активных элементов       │   │
│ │ (token positions, completed elements, incidents)   │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│ Tab: Variables                                           │
│ ┌────────────────────────────────────────────────────┐   │
│ │ Name        │ Type    │ Value                      │   │
│ │ orderId     │ LONG    │ 12345                      │   │
│ │ customer    │ STRING  │ John Doe                   │   │
│ │ approved    │ BOOLEAN │ true                       │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│ Tab: Tasks                                               │
│ ┌────────────────────────────────────────────────────┐   │
│ │ Active: User Task "Review" (UT-abc)               │   │
│ │ Completed: Service Task "Send Email" (ST-def)     │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│ Tab: Incidents                                           │
│ ┌────────────────────────────────────────────────────┐   │
│ │ INC-xyz | "NullPointerException" | 2m ago         │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## 7.4 Variables View

**Endpoint**: `GET /variables?processInstanceId={id}`

**Компонент**: `DataTable` с inline editing

| Колонка | Тип | Описание |
|---|---|---|
| Name | text | Имя переменной |
| Type | Badge | STRING / LONG / BOOLEAN |
| Value | editable input | Текущее значение |
| Actions | IconButton | Edit / Delete |

## 7.5 Active Tokens Visualization

**Источник**: `GET /process-instances?id={id}` → `GET /variables` → BPMN Structure + Activity data

Визуализация текущих позиций токенов на BPMN-графе:
- **Зелёные узлы** — завершённые элементы
- **Синие узлы** — активные элементы (текущие позиции токенов)
- **Красные узлы** — элементы с инцидентами
- **Серые узлы** — ещё не достигнутые элементы

---

# 8. Task Management

## 8.1 Task List — Мои задачи

**Endpoint**: `GET /user-tasks?assignee={currentUser}&completed=false`

**Layout**:

```
┌──────────────────────────────────────────────────────────┐
│ My Tasks (12)                      [Filter] [Search...]  │
├──────────────────────────────────────────────────────────┤
│ ☐ │ Name              │ Process      │ Created │ Due     │
├───┼───────────────────┼──────────────┼─────────┼─────────┤
│ ☐ │ Review Order #123 │ Order Proc.  │ 2m ago  │ —       │
│ ☐ │ Approve Payment   │ Payment Flow │ 15m ago │ 1h      │
│ ☐ │ Sign Contract     │ Contract     │ 1h ago  │ —       │
└───┴───────────────────┴──────────────┴─────────┴─────────┘
                                                    [Complete ▼]
```

**Фильтры**:
- Process Definition (combobox)
- Created Date Range
- Status (Active / Completed)
- Assignee

**Bulk Operations**:
- Выбрать все → Complete selected
- Export to CSV

## 8.2 Task Details

**Endpoint**: `GET /user-tasks?id={id}` + `GET /variables?processInstanceId={task.processInstanceId}`

**Layout**:

```
┌──────────────────────────────────────────────────────────┐
│ Task: Review Order #123                                  │
│ Process: Order Processing v3 | Started: 2m ago           │
├──────────────────────────────────────────────────────────┤
│ [Task Details] [Process Context] [Variables]             │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ Task Details:                                            │
│ ┌────────────────────────────────────────────────────┐   │
│ │ Form: ReviewForm (custom or generic)               │   │
│ │                                                    │   │
│ │ Order ID:  [12345        ]                         │   │
│ │ Customer:  [John Doe     ]                         │   │
│ │ Amount:    [100.00       ]                         │   │
│ │                                                    │   │
│ │ Decision:  ○ Approve  ○ Reject                     │   │
│ │ Comment:   [________________]                      │   │
│ │                                                    │   │
│ │              [Complete Task]                        │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│ Process Context:                                         │
│ ┌────────────────────────────────────────────────────┐   │
│ │ BPMN Viewer (read-only) с подсветкой текущего     │   │
│ │ элемента                                           │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## 8.3 Task Completion Flow

1. Пользователь открывает задачу
2. Frontend загружает variables через `GET /variables?processInstanceId={id}`
3. Пользователь заполняет форму (генерируется на основе variables)
4. Пользователь нажимает "Complete Task"
5. Frontend отправляет `POST /user-tasks/{id}/complete` с variables
6. Backend завершает задачу и продолжает процесс
7. Frontend обновляет UI (задача исчезает из списка)

---

# 9. BPMN Viewer

## 9.1 Технология

**Библиотека**: `bpmn-js` (bpmn.io) — стандарт де-факто для BPMN визуализации

**Подключаемые модули**:
- `bpmn-js` — основной viewer
- `bpmn-js-properties-panel` — панель свойств (для будущего designer)
- `diagram-js-minimap` — мини-карта

## 9.2 Viewing Mode

```typescript
import BpmnViewer from 'bpmn-js/lib/Viewer'

const viewer = new BpmnViewer({
  container: '#bpmn-container',
  additionalModules: [minimapModule],
  minimap: { position: 'bottom-right' }
})

// Load BPMN XML
viewer.importXML(bpmnXml)

// Get canvas for overlays
const canvas = viewer.get('canvas')
const overlays = viewer.get('overlays')

// Zoom
viewer.get('zoom-scroll').stepZoom(1)

// Fit to screen
canvas.zoom('fit-viewport')
```

## 9.3 Features

### Zoom Controls
- Zoom in/out buttons
- Fit to viewport
- Reset zoom
- Minimap toggle

### Active Elements Highlighting
```typescript
// Highlight active elements (blue)
canvas.addMarker('elementId', 'highlight-active')

// Highlight completed elements (green)
canvas.addMarker('elementId', 'highlight-completed')

// Highlight incidents (red)
canvas.addMarker('elementId', 'highlight-incident')
```

### Token Visualization
- Animated tokens on active sequence flows
- Token count labels on parallel gateways
- Token history (fade effect on completed paths)

### Overlays
```typescript
// Add overlay with variable value
overlays.add('elementId', {
  position: { bottom: 0, right: 0 },
  html: '<div class="overlay-badge">orderId=12345</div>'
})

// Add overlay with incident count
overlays.add('elementId', {
  position: { top: 0, left: 0 },
  html: '<div class="overlay-incident">1 incident</div>'
})
```

### Click Navigation
- Клик по элементу → показать details panel
- Клик по task → переход к task details
- Клик по subprocess → drill-down в подпроцесс

### CSS для подсветки
```css
.highlight-active .djs-element rect,
.highlight-active .djs-element path {
  stroke: #3b82f6 !important;  /* blue-500 */
  stroke-width: 3px;
  filter: drop-shadow(0 0 6px rgba(59, 130, 246, 0.5));
}

.highlight-completed .djs-element rect,
.highlight-completed .djs-element path {
  stroke: #22c55e !important;  /* green-500 */
  fill: #f0fdf4 !important;    /* green-50 */
}

.highlight-incident .djs-element rect,
.highlight-incident .djs-element path {
  stroke: #ef4444 !important;  /* red-500 */
  fill: #fef2f2 !important;    /* red-50 */
  animation: pulse-red 2s infinite;
}
```

## 9.4 BPMN Viewer Component

```vue
<!-- components/BpmnViewer.vue -->
<template>
  <div class="bpmn-viewer-wrapper">
    <div class="bpmn-toolbar">
      <Button variant="outline" size="icon" @click="zoomIn">
        <ZoomIn class="h-4 w-4" />
      </Button>
      <Button variant="outline" size="icon" @click="zoomOut">
        <ZoomOut class="h-4 w-4" />
      </Button>
      <Button variant="outline" size="icon" @click="fitViewport">
        <Maximize class="h-4 w-4" />
      </Button>
      <Separator orientation="vertical" />
      <Button variant="outline" size="icon" @click="toggleMinimap">
        <Map class="h-4 w-4" />
      </Button>
    </div>
    <div ref="container" class="bpmn-container" />
    <div v-if="selectedElement" class="bpmn-details-panel">
      <!-- Element details: name, type, variables, incidents -->
    </div>
  </div>
</template>
```

---

# 10. BPMN Designer Integration

## 10.1 Преимущества

- Полноценный визуальный редактор BPMN 2.0
- Drag-and-drop элементов
- Auto-layout
- Интеграция с properties panel
- Немедленный деплой после редактирования

## 10.2 Риски

- **Сложность**: bpmn-js/diagram-js — крупная библиотека, высокий порог входа
- **Совместимость**: нужно поддерживать Zeebe-расширения (`zeebe:taskDefinition`, `zeebe:ioMapping`)
- **Размер бандла**: +200KB min+gzip
- **Валидация**: нужно обеспечить валидность BPMN перед деплоем

## 10.3 Архитектура

```
┌─────────────────────────────────────────────┐
│ Frontend (SPA)                              │
│ ┌───────────────────────────────────────┐   │
│ │ BPMN Designer (bpmn-js + properties) │   │
│ │                                       │   │
│ │  ┌──────────┐  ┌──────────────────┐  │   │
│ │  │ Palette  │  │ Canvas           │  │   │
│ │  │ (drag)   │  │ (draw)           │  │   │
│ │  └──────────┘  └──────────────────┘  │   │
│ │                                       │   │
│ │  ┌──────────────────────────────────┐ │   │
│ │  │ Properties Panel                 │ │   │
│ │  │ (Zeebe-compatible fields)        │ │   │
│ │  └──────────────────────────────────┘ │   │
│ └───────────────────────────────────────┘   │
│                     │                       │
│                     ▼                       │
│           POST /process-definitions         │
│           { bpmn: "<xml>..." }              │
└─────────────────────────────────────────────┘
```

## 10.4 Фазы внедрения

1. **Phase 1**: Read-only viewer (bpmn-js Viewer) — MVP
2. **Phase 2**: Designer с basic palette — P2
3. **Phase 3**: Zeebe properties panel — P3
4. **Phase 4**: Custom properties, validation — P4

---

# 11. Incident Management

## 11.1 Incident List

**Endpoint**: `GET /incidents`

**Компонент**: `DataTable` с фильтрами

| Колонка | Источник |
|---|---|
| ID | `Incident.id` |
| Message | `Incident.message` |
| Process Instance | Link → `/processes/instances/{processInstanceId}` |
| Activity | `activityId` → BPMN element name |
| Created | `Incident.createdAt` |
| Status | Badge: Open / Resolved |
| Actions | View / Resolve |

**Фильтры**:
- Process Definition
- Process Instance
- BPMN Element
- Status (Open / Resolved)
- Date Range

## 11.2 Incident Details

**Endpoint**: `GET /incidents?id={id}` + related data

**Layout**:

```
┌──────────────────────────────────────────────────────────┐
│ Incident INC-xyz                                         │
│ Status: Open | Created: 2m ago                           │
├──────────────────────────────────────────────────────────┤
│ [Overview] [Process Context] [Variables] [Actions]       │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ Overview:                                                │
│ ┌────────────────────────────────────────────────────┐   │
│ │ Message: NullPointerException at line 649          │   │
│ │ Element: callActivity_1 (Call Activity)            │   │
│ │ Process: Order Processing v3                       │   │
│ │ Instance: PI-a1b2c3d4                             │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│ Process Context:                                         │
│ ┌────────────────────────────────────────────────────┐   │
│ │ BPMN Viewer с подсветкой инцидентного элемента    │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│ Actions:                                                 │
│ ┌────────────────────────────────────────────────────┐   │
│ │ [Resolve Incident]  [View Process]  [View History] │   │
│ │                                                    │   │
│ │ Resolve options:                                   │   │
│ │ ☐ Update variables before retry                    │   │
│ │ Variable: [key] [value] [type] [+ Add]            │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## 11.3 Resolve Flow

1. Пользователь нажимает "Resolve Incident"
2. Опционально добавляет/изменяет переменные
3. Нажимает "Confirm Resolve"
4. Frontend отправляет `POST /incidents/{id}/resolve` с variables
5. Backend повторно исполняет элемент
6. Frontend обновляет статус инцидента

---

# 12. Process Explorer

## 12.1 Единый интерфейс

Вместо отдельных экранов для каждого типа данных — **unified explorer** с переключением контекста:

```
┌──────────────────────────────────────────────────────────┐
│ Process Explorer                     [Filter▼] [Search]  │
├──────────────────────────────────────────────────────────┤
│ [Definitions] [Instances] [Tasks] [Incidents] [Timers]  │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ Tab: Definitions                                         │
│ ┌────────────────────────────────────────────────────┐   │
│ │ Order Processing v3    │ v3 │ 15 instances │ 2 inc│   │
│ │ Payment Flow           │ v1 │ 8 instances  │ 0 inc│   │
│ │ Contract Signing       │ v2 │ 42 instances │ 1 inc│   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│ Tab: Instances                                           │
│ ┌────────────────────────────────────────────────────┐   │
│ │ PI-abc │ Order Proc. │ Running │ 2m │ vars: 5     │   │
│ │ PI-def │ Payment     │ Done    │ 1h │ vars: 3     │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│ Tab: Tasks                                               │
│ ┌────────────────────────────────────────────────────┐   │
│ │ UT-123 │ Review Order │ Assignee: john │ 2m ago   │   │
│ │ ST-456 │ Send Email   │ Job: email-snd │ done     │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│ Tab: Incidents                                           │
│ ┌────────────────────────────────────────────────────┐   │
│ │ INC-789 │ NPE in Call Activity │ Open │ 2m ago    │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## 12.2 Drill-down Navigation

Клик по элементу → context panel справа:

```
┌─────────────────────────────────────────────┬─────────────┐
│ Process Explorer                            │ Details     │
│                                             │             │
│ PI-abc │ Order Proc. │ Running │ 2m        │ Instance    │
│                                             │ PI-abc      │
│ [selected]                                  │ Status: Run │
│                                             │ Started: 2m │
│                                             │ Def: v3     │
│                                             │             │
│                                             │ Variables:  │
│                                             │ orderId:123 │
│                                             │ status:new  │
│                                             │             │
│                                             │ Tasks:      │
│                                             │ UT-123 Review│
│                                             │             │
│                                             │ Incidents:  │
│                                             │ (none)      │
│                                             │             │
│                                             │ [Open] [▶]  │
└─────────────────────────────────────────────┴─────────────┘
```

---

# 13. Process Analytics

## 13.1 KPI Dashboard

| KPI | Formula | Визуализация |
|---|---|---|
| Process Completion Rate | `completed / (completed + running) * 100` | Gauge chart |
| Average Duration | `AVG(completedAt - startedAt)` | Line chart |
| Incident Rate | `incidents / total_instances * 100` | Bar chart |
| Task Throughput | `completed_tasks / day` | Line chart |
| SLA Compliance | `completed_within_sla / total * 100` | Gauge chart |

## 13.2 Charts

### Process Instances Over Time
- **X axis**: Date (day/week/month)
- **Y axis**: Count
- **Series**: Started / Completed / Active
- **Type**: Stacked area chart

### Duration Distribution
- **X axis**: Duration buckets (0-1m, 1-5m, 5-30m, 30m-1h, 1h+)
- **Y axis**: Count
- **Type**: Histogram

### Incident Distribution by Process
- **X axis**: Process Definition name
- **Y axis**: Incident count
- **Type**: Horizontal bar chart

### Task Completion Time
- **X axis**: Task name
- **Y axis**: Average completion time
- **Type**: Bar chart with error bars

## 13.3 SLA Reports

**Определение SLA**: время от создания задачи/экземпляра до завершения

| Report | Описание |
|---|---|
| Task SLA | Время выполнения задачи vs target |
| Process SLA | Время выполнения процесса vs target |
| Overdue Tasks | Задачи, превышающие SLA |
| SLA Trend | Динамика SLA по времени |

---

# 14. DMN Management

## 14.1 Текущие возможности Backend

Backend поддерживает:
- DMN deploy: `DmnServiceImpl.deploy(dmnXml)` — через отдельный endpoint (нужно добавить REST)
- DMN evaluate: `DmnServiceImpl.evaluate(decisionId, variables)`
- DMN storage: `DmnDefinitionEntity` в БД

## 14.2 Экран DMN Management

### DMN Definitions List

| Колонка | Описание |
|---|---|
| Decision ID | Уникальный ID |
| Name | Название |
| Version | Версия |
| Created | Дата создания |
| Actions | View / Test / Deploy |

### DMN Viewer

- Отображение decision table в виде таблицы
- Input columns / Output columns / Rules
- Hit policy indicator

### DMN Tester

```
┌──────────────────────────────────────────────────────┐
│ DMN Tester: Credit Decision                          │
├──────────────────────────────────────────────────────┤
│ Input:                                               │
│ creditScore: [750    ]                               │
│ income:      [50000  ]                               │
│                                              [Evaluate]│
├──────────────────────────────────────────────────────┤
│ Result:                                              │
│ decision: "APPROVED"                                 │
│ amount:   25000                                      │
│ rate:     0.05                                       │
├──────────────────────────────────────────────────────┤
│ Matched Rule: Rule 2                                 │
│ Input: creditScore >= 700 AND income >= 40000        │
└──────────────────────────────────────────────────────┘
```

---

# 15. Administration

## 15.1 Users Management

**Endpoint**: Новый (требуется Backend) → `GET/POST/PUT/DELETE /admin/users`

### Users List

| Колонка | Описание |
|---|---|
| Login | preferred_username из Keycloak |
| Full Name | Отображаемое имя |
| Email | Email |
| Active | Статус (Badge: Active / Inactive) |
| Created | Дата создания |
| Actions | Edit / Deactivate |

### User Form

```
┌──────────────────────────────────────────────┐
│ Edit User: john.doe                          │
├──────────────────────────────────────────────┤
│ Login:       [john.doe          ] (readonly) │
│ Full Name:   [John Doe          ]            │
│ Email:       [john@example.com  ]            │
│ Active:      [✓]                             │
│                                              │
│              [Save]  [Cancel]                │
└──────────────────────────────────────────────┘
```

## 15.2 Settings

**Текущие настройки** (из `application.properties`):

| Настройка | Описание |
|---|---|
| `zorrobpm.engine.max-execution-depth` | Max depth рекурсии |
| `zorrobpm.engine.timer-poll-interval-ms` | Интервал опроса таймеров |
| `DB_URL` | JDBC URL |
| `RABBITMQ_HOST` | RabbitMQ host |

**Будущие настройки**:
- Keycloak configuration
- CORS policies
- Email notifications
- Logging levels

---

# 16. Authentication

## 16.1 Keycloak OIDC Integration

### Flow

```
1. User opens ZorroBPM
2. Frontend checks for valid JWT token
3. If no token → redirect to Keycloak login
4. Keycloak authenticates user
5. Keycloak redirects back with auth code
6. Frontend exchanges code for tokens (access_token, refresh_token)
7. Frontend stores tokens in memory (not localStorage)
8. Frontend sends Authorization: Bearer {token} on all API calls
```

### Frontend Implementation

```typescript
// composables/useAuth.ts
import { useOidcClient } from '@axa-fr/oauth-client'

const oidcConfig = {
  clientId: 'zorrobpm-frontend',  // placeholder, will be updated
  redirectUri: window.location.origin,
  scope: 'openid profile email',
  discoveryEndpoint: `${KEYCLOAK_URL}/realms/${REALM}/.well-known/openid-configuration`
}
```

### Token Management

- **Access Token**: хранится в内存 (Pinia store)
- **Refresh Token**: хранится в httpOnly cookie (если backend proxy) или в内存
- **Auto-refresh**: за 30 секунд до истечения
- **Logout**: clear tokens + redirect to Keycloak logout

## 16.2 Backend Token Validation

Backend должен:
1. Получать `Authorization: Bearer {token}` header
2. Валидировать JWT signature через Keycloak public key
3. Извлекать `preferred_username` из claims
4. Искать пользователя в `users` таблице
5. Проверять `active = true`

## 16.3 User White List

### Таблица `users`

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    login VARCHAR(255) NOT NULL UNIQUE,
    full_name VARCHAR(255),
    email VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

### Flow

```
1. User authenticates via Keycloak
2. Backend receives JWT with preferred_username
3. SELECT * FROM users WHERE login = ?
4. If found AND active = true → proceed
5. If not found → 403 Forbidden
6. If active = false → 403 Forbidden
```

---

# 17. Future RBAC Strategy

## 17.1 Архитектура (не реализовывать сейчас)

```
users
  ├─ id, login, full_name, email, active

roles
  ├─ id, name (admin, developer, operator, viewer)

user_roles
  ├─ user_id, role_id

permissions
  ├─ id, name (process.deploy, process.start, task.complete, ...)

role_permissions
  ├─ role_id, permission_id

groups
  ├─ id, name (department-finance, team-backend)

user_groups
  ├─ user_id, group_id

process_permissions
  ├─ process_definition_id, group_id, permission_type
```

## 17.2 Принципы

1. **Пользователь → Roles → Permissions** — классическая RBAC
2. **Process-level permissions** — к каким процессам доступ
3. **Group-based access** — доступ через группы/отделы
4. **Frontend**:隱藏 элементы, к которым нет доступа
5. **Backend**: проверка прав на каждом endpoint

## 17.3 Миграция

- Текущая `users.active` → базовый ACL
- В будущем: `users.active` + `user_roles` + `role_permissions`
- Frontend: `useAuthorization()` composable для проверки прав

---

# 18. UX/UI Design System

## 18.1 Technology Stack

| Технология | Назначение |
|---|---|
| Vue 3 | UI framework (Composition API) |
| TypeScript | Type safety |
| Vite | Build tool |
| Pinia | State management |
| Vue Router | Routing |
| Tailwind CSS | Utility-first CSS |
| shadcn-vue | UI component library |
| Axios | HTTP client |
| bpmn-js | BPMN viewer/designer |

## 18.2 shadcn-vue Components

| Компонент | Где используется | Почему выбран |
|---|---|---|
| **DataTable** | Process list, Task list, Incident list, User list | Сортировка, пагинация, фильтрация из коробки |
| **Dialog** | Deploy BPMN, Start Process, Complete Task | Модальные формы с backdrop |
| **Sheet** | Process details side panel, Variable editor | Slide-out панель без потери контекста |
| **Drawer** | Mobile navigation, Quick actions | Responsive альтернатива Sheet |
| **Card** | Dashboard widgets, KPI cards | Компактные контейнеры с контентом |
| **Tabs** | Process details, Task details | Переключение контекста в одном view |
| **Badge** | Status indicators (Running, Completed, Open) | Цветовые индикаторы статуса |
| **Pagination** | Все списки | Стандартная навигация по страницам |
| **Sonner** | Toast notifications | Уведомления о действиях (deploy, complete, resolve) |
| **Tooltip** | Icon buttons, truncated text | Контекстная помощь |
| **Command** | Global search (Cmd+K) | Palette-style поиск |
| **Combobox** | Process definition filter, User select | Поиск + выбор из списка |
| **Breadcrumb** | Все страницы | Навигационный контекст |
| **Form** | Variable editor, User form, Settings | Валидируемые формы |
| **Select** | Filter dropdowns, Type selectors | Стандартные dropdown |
| **Button** | Все действия | Primary/Secondary/Danger variants |
| **Separator** | Toolbar dividers | Визуальное разделение |
| **Avatar** | User menu, Task assignee | Отображение пользователя |
| **Dropdown Menu** | Context menus, User menu | Action menus |
| **Alert Dialog** | Delete confirmation, Critical actions | Confirmation с backdrop |
| **Popover** | Filter panels, Date picker | Floating panels |
| **Calendar** | Date range filter | Date selection |
| **Skeleton** | Loading states | Content placeholders |

## 18.3 Цветовая схема

```css
/* Tailwind CSS custom theme */
:root {
  /* Primary — Blue (BPM/Professional) */
  --primary: 221 83% 53%;         /* #3b82f6 */
  --primary-foreground: 0 0% 100%;

  /* Status Colors */
  --status-running: 217 91% 60%;  /* Blue — Running */
  --status-completed: 142 71% 45%; /* Green — Completed */
  --status-error: 0 84% 60%;      /* Red — Error/Incident */
  --status-warning: 38 92% 50%;   /* Orange — Warning */
  --status-idle: 220 9% 46%;      /* Gray — Idle */

  /* Background */
  --background: 0 0% 100%;
  --foreground: 222 84% 5%;
  --card: 0 0% 100%;
  --sidebar: 222 47% 6%;
  --sidebar-foreground: 210 40% 98%;
}
```

## 18.4 Layout Component

```vue
<!-- layouts/MainLayout.vue -->
<template>
  <div class="flex h-screen">
    <!-- Sidebar -->
    <aside class="w-64 bg-sidebar text-sidebar-foreground">
      <SidebarNav :items="navigation" />
    </aside>

    <!-- Main -->
    <div class="flex-1 flex flex-col">
      <!-- Header -->
      <header class="h-14 border-b flex items-center px-4">
        <SearchCommand />
        <NotificationBell />
        <UserMenu />
      </header>

      <!-- Breadcrumbs -->
      <nav class="h-10 border-b flex items-center px-4">
        <Breadcrumb :items="breadcrumbs" />
      </nav>

      <!-- Content -->
      <main class="flex-1 overflow-auto p-6">
        <router-view />
      </main>
    </div>
  </div>
</template>
```

---

# 19. Frontend Architecture

## 19.1 Project Structure

```
zorrobpm-frontend/
├── src/
│   ├── app/
│   │   ├── App.vue
│   │   ├── main.ts
│   │   └── router.ts
│   ├── pages/
│   │   ├── Dashboard.vue
│   │   ├── processes/
│   │   │   ├── ProcessDefinitionList.vue
│   │   │   ├── ProcessDefinitionDetail.vue
│   │   │   ├── ProcessInstanceList.vue
│   │   │   ├── ProcessInstanceDetail.vue
│   │   │   └── DeployProcess.vue
│   │   ├── tasks/
│   │   │   ├── TaskList.vue
│   │   │   ├── TaskDetail.vue
│   │   │   └── CompletedTasks.vue
│   │   ├── incidents/
│   │   │   ├── IncidentList.vue
│   │   │   └── IncidentDetail.vue
│   │   ├── analytics/
│   │   │   ├── AnalyticsOverview.vue
│   │   │   ├── ProcessMetrics.vue
│   │   │   └── SLAReports.vue
│   │   ├── admin/
│   │   │   ├── UserList.vue
│   │   │   ├── UserForm.vue
│   │   │   └── Settings.vue
│   │   └── AuthCallback.vue
│   ├── layouts/
│   │   ├── MainLayout.vue
│   │   └── AuthLayout.vue
│   ├── widgets/
│   │   ├── dashboard/
│   │   │   ├── KpiCards.vue
│   │   │   ├── ProcessTrendChart.vue
│   │   │   ├── TaskDistributionChart.vue
│   │   │   ├── IncidentHeatmap.vue
│   │   │   ├── RecentIncidents.vue
│   │   │   └── MyOpenTasks.vue
│   │   ├── bpmn/
│   │   │   ├── BpmnViewer.vue
│   │   │   ├── BpmnToolbar.vue
│   │   │   ├── BpmnElementDetails.vue
│   │   │   └── BpmnTokenOverlay.vue
│   │   └── shared/
│   │       ├── DataTable.vue
│   │       ├── StatusBadge.vue
│   │       ├── DateRangePicker.vue
│   │       └── SearchCommand.vue
│   ├── features/
│   │   ├── auth/
│   │   │   ├── useAuth.ts
│   │   │   ├── AuthGuard.vue
│   │   │   └── KeycloakService.ts
│   │   ├── processes/
│   │   │   ├── useProcessDefinitions.ts
│   │   │   ├── useProcessInstances.ts
│   │   │   └── useProcessStructure.ts
│   │   ├── tasks/
│   │   │   ├── useUserTasks.ts
│   │   │   ├── useServiceTasks.ts
│   │   │   └── useTaskCompletion.ts
│   │   ├── incidents/
│   │   │   ├── useIncidents.ts
│   │   │   └── useIncidentResolution.ts
│   │   └── bpmn/
│   │       ├── useBpmnViewer.ts
│   │       ├── useBpmnDesigner.ts
│   │       └── bpmnHighlighter.ts
│   ├── entities/
│   │   ├── process/
│   │   │   ├── ProcessDefinition.ts
│   │   │   ├── ProcessInstance.ts
│   │   │   └── ProcessVariable.ts
│   │   ├── task/
│   │   │   ├── UserTask.ts
│   │   │   └── ServiceTask.ts
│   │   ├── incident/
│   │   │   └── Incident.ts
│   │   ├── bpmn/
│   │   │   ├── BpmnProcessStructure.ts
│   │   │   ├── BpmnNode.ts
│   │   │   └── BpmnFlow.ts
│   │   └── user/
│   │       └── User.ts
│   ├── shared/
│   │   ├── ui/           # shadcn-vue components
│   │   ├── lib/          # Utilities
│   │   └── api/          # Axios instance, interceptors
│   ├── services/
│   │   ├── api.ts        # Axios instance with auth interceptor
│   │   ├── processService.ts
│   │   ├── taskService.ts
│   │   ├── incidentService.ts
│   │   ├── variableService.ts
│   │   ├── bpmnService.ts
│   │   ├── userService.ts
│   │   └── analyticsService.ts
│   ├── stores/
│   │   ├── auth.ts       # Keycloak tokens, user info
│   │   ├── process.ts    # Process definitions cache
│   │   ├── task.ts       # Task list state
│   │   ├── incident.ts   # Incident list state
│   │   ├── ui.ts         # Sidebar state, theme
│   │   └── notification.ts # Notifications
│   ├── composables/
│   │   ├── useAuth.ts
│   │   ├── usePagination.ts
│   │   ├── useSearch.ts
│   │   ├── useDebounce.ts
│   │   └── useToast.ts
│   └── types/
│       ├── api.ts        # API response types
│       ├── models.ts     # Domain models
│       └── router.ts     # Route meta types
├── public/
│   └── favicon.ico
├── package.json
├── vite.config.ts
├── tailwind.config.ts
├── tsconfig.json
└── .env
```

## 19.2 API Layer

```typescript
// services/api.ts
import axios from 'axios'
import { useAuthStore } from '@/stores/auth'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  headers: { 'Content-Type': 'application/json' }
})

api.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      const auth = useAuthStore()
      await auth.refreshToken()
      // retry request
    }
    return Promise.reject(error)
  }
)

export default api
```

## 19.3 State Management

```typescript
// stores/process.ts
import { defineStore } from 'pinia'
import { processService } from '@/services/processService'

export const useProcessStore = defineStore('process', {
  state: () => ({
    definitions: [] as ProcessDefinition[],
    instances: [] as ProcessInstance[],
    currentDefinition: null as ProcessDefinition | null,
    currentInstance: null as ProcessInstance | null,
    loading: false,
    error: null as string | null
  }),
  actions: {
    async fetchDefinitions(params: ProcessDefinitionsQuery) {
      this.loading = true
      try {
        const response = await processService.getDefinitions(params)
        this.definitions = response.data.data
        return response.data
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    },
    // ...
  }
})
```

## 19.4 Error Handling

```typescript
// composables/useErrorHandler.ts
import { useToast } from 'vue-sonner'

export function useErrorHandler() {
  const toast = useToast()

  function handleError(error: unknown) {
    if (axios.isAxiosError(error)) {
      const status = error.response?.status
      const message = error.response?.data?.message || error.message

      if (status === 401) {
        toast.error('Session expired. Please log in again.')
      } else if (status === 403) {
        toast.error('Access denied.')
      } else if (status === 404) {
        toast.error('Resource not found.')
      } else {
        toast.error(`Error: ${message}`)
      }
    } else {
      toast.error('An unexpected error occurred.')
    }
  }

  return { handleError }
}
```

## 19.5 Routing

```typescript
// app/router.ts
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'dashboard', component: () => import('@/pages/Dashboard.vue') },
      { path: 'processes/definitions', name: 'process-definitions', component: () => import('@/pages/processes/ProcessDefinitionList.vue') },
      { path: 'processes/definitions/:id', name: 'process-definition-detail', component: () => import('@/pages/processes/ProcessDefinitionDetail.vue') },
      { path: 'processes/instances', name: 'process-instances', component: () => import('@/pages/processes/ProcessInstanceList.vue') },
      { path: 'processes/instances/:id', name: 'process-instance-detail', component: () => import('@/pages/processes/ProcessInstanceDetail.vue') },
      { path: 'processes/deploy', name: 'deploy-process', component: () => import('@/pages/processes/DeployProcess.vue') },
      { path: 'tasks', name: 'my-tasks', component: () => import('@/pages/tasks/TaskList.vue') },
      { path: 'tasks/all', name: 'all-tasks', component: () => import('@/pages/tasks/TaskList.vue') },
      { path: 'tasks/completed', name: 'completed-tasks', component: () => import('@/pages/tasks/CompletedTasks.vue') },
      { path: 'tasks/:id', name: 'task-detail', component: () => import('@/pages/tasks/TaskDetail.vue') },
      { path: 'incidents', name: 'incidents', component: () => import('@/pages/incidents/IncidentList.vue') },
      { path: 'incidents/:id', name: 'incident-detail', component: () => import('@/pages/incidents/IncidentDetail.vue') },
      { path: 'analytics', name: 'analytics', component: () => import('@/pages/analytics/AnalyticsOverview.vue') },
      { path: 'admin/users', name: 'admin-users', component: () => import('@/pages/admin/UserList.vue') },
      { path: 'admin/settings', name: 'admin-settings', component: () => import('@/pages/admin/Settings.vue') }
    ]
  },
  { path: '/auth/callback', name: 'auth-callback', component: () => import('@/pages/AuthCallback.vue') },
  { path: '/login', name: 'login', component: () => import('@/pages/Login.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    await auth.login()
  }
})

export default router
```

## 19.6 Code Splitting

| Route | Chunk | Размер (est.) |
|---|---|---|
| Dashboard | `dashboard.js` | ~15KB |
| Processes | `processes.js` | ~20KB |
| Tasks | `tasks.js` | ~12KB |
| Incidents | `incidents.js` | ~10KB |
| Analytics | `analytics.js` | ~25KB (charts) |
| Admin | `admin.js` | ~8KB |
| BPMN Viewer | `bpmn-viewer.js` | ~80KB (bpmn-js) |
| BPMN Designer | `bpmn-designer.js` | ~250KB |

---

# 20. MVP

## 20.1 P1 — Обязательный MVP

| # | Экран | Описание | API Endpoints |
|---|---|---|---|
| 1 | **Dashboard** | KPI cards, recent incidents, my tasks | `/process-instances`, `/incidents`, `/user-tasks` |
| 2 | **Process Definitions List** | Таблица с фильтрами | `GET /process-definitions` |
| 3 | **Process Definition Details** | Метаданные, BPMN structure, instances | `GET /process-definitions/{id}`, `GET /.../structure` |
| 4 | **Process Instances List** | Таблица с фильтрами | `GET /process-instances` |
| 5 | **Process Instance Details** | Variables, tasks, incidents, BPMN flow | `GET /process-instances`, `GET /variables`, `GET /user-tasks`, `GET /incidents` |
| 6 | **Task List (My Tasks)** | Мои задачи с фильтрами | `GET /user-tasks?assignee=me` |
| 7 | **Task Details** | Variables, complete action | `GET /user-tasks`, `GET /variables`, `POST /user-tasks/{id}/complete` |
| 8 | **Incident List** | Таблица инцидентов | `GET /incidents` |
| 9 | **Incident Details** | Message, process context, resolve | `GET /incidents`, `POST /incidents/{id}/resolve` |
| 10 | **User List** | Управление пользователями | `GET/POST/PUT /admin/users` (новый Backend) |

## 20.2 P2 — Мониторинг

| # | Экран | Описание |
|---|---|---|
| 11 | **Deploy BPMN** | Upload XML, preview, deploy |
| 12 | **BPMN Viewer** | Просмотр графа с подсветкой |
| 13 | **Start Process** | Модалка с переменными |
| 14 | **Variables Editor** | Inline editing переменных |
| 15 | **Execution History** | Timeline по элементам |

## 20.3 P3 — Продвинутый

| # | Экран | Описание |
|---|---|---|
| 16 | **DMN Viewer** | Decision table viewer |
| 17 | **DMN Tester** | Evaluate с input/output |
| 18 | **Timers Management** | Список активных таймеров |
| 19 | **Messages Management** | Список подписок |
| 20 | **Administration Settings** | Системные настройки |

---

# 21. Frontend Backlog

| # | Описание | Бизнес-ценность | Сложность | Зависимости | Приоритет |
|---|---|---|---|---|---|
| F01 | Scaffold Vue 3 + Vite + TypeScript проект | Высокая | Низкая | — | P1 |
| F02 | shadcn-vue UI library setup | Высокая | Низкая | F01 | P1 |
| F03 | Tailwind CSS theme (BPM colors) | Средняя | Низкая | F02 | P1 |
| F04 | Keycloak OIDC integration | Критическая | Средняя | F01 | P1 |
| F05 | API service layer (Axios + interceptors) | Высокая | Низкая | F01 | P1 |
| F06 | MainLayout (sidebar + header + breadcrumbs) | Высокая | Средняя | F02, F03 | P1 |
| F07 | Vue Router setup | Высокая | Низкая | F01 | P1 |
| F08 | Pinia stores (auth, process, task, incident) | Высокая | Средняя | F01 | P1 |
| F09 | Dashboard — KPI cards | Высокая | Низкая | F06, F08 | P1 |
| F10 | Process Definitions List | Высокая | Средняя | F06, F08 | P1 |
| F11 | Process Definition Details | Высокая | Средняя | F10 | P1 |
| F12 | Process Instances List | Высокая | Средняя | F06, F08 | P1 |
| F13 | Process Instance Details | Высокая | Средняя | F12 | P1 |
| F14 | Task List (My Tasks) | Критическая | Средняя | F06, F08 | P1 |
| F15 | Task Details + Complete | Критическая | Средняя | F14 | P1 |
| F16 | Incident List | Высокая | Средняя | F06, F08 | P1 |
| F17 | Incident Details + Resolve | Высокая | Средняя | F16 | P1 |
| F18 | User List (Admin) | Высокая | Средняя | F06 | P1 |
| F19 | Dashboard — charts (Process Trend) | Средняя | Средняя | F09 | P2 |
| F20 | Deploy BPMN screen | Высокая | Средняя | F10 | P2 |
| F21 | BPMN Viewer (bpmn-js) | Высокая | Высокая | F11 | P2 |
| F22 | Start Process modal | Высокая | Низкая | F11 | P2 |
| F23 | Variables editor | Средняя | Средняя | F13 | P2 |
| F24 | Execution history timeline | Средняя | Высокая | F13 | P2 |
| F25 | Dashboard — Incident heatmap | Средняя | Средняя | F09 | P2 |
| F26 | Global search (Cmd+K) | Средняя | Средняя | F06 | P2 |
| F27 | Notifications (toast + bell) | Средняя | Низкая | F06 | P2 |
| F28 | DMN Viewer | Средняя | Высокая | F11 | P3 |
| F29 | DMN Tester | Средняя | Средняя | F28 | P3 |
| F30 | Timers management | Низкая | Средняя | F06 | P3 |
| F31 | Messages management | Низкая | Средняя | F06 | P3 |
| F32 | Analytics — SLA reports | Средняя | Высокая | F19 | P3 |
| F33 | BPMN Designer (bpmn-js) | Средняя | Очень высокая | F21 | P3 |
| F34 | Bulk operations (tasks) | Средняя | Средняя | F14 | P3 |
| F35 | Export to CSV/Excel | Низкая | Низкая | F10, F12, F14 | P3 |
| F36 | Dark mode | Низкая | Низкая | F03 | P3 |
| F37 | Responsive mobile layout | Средняя | Высокая | F06 | P3 |

---

# 22. Roadmap

## Этап 1 — MVP (4-6 недель)

**Цель**: Рабочий SPA с базовым мониторингом и управлением задачами.

### Состав работ

| Задача | Неделя | Зависимости |
|---|---|---|
| F01: Scaffold проекта | 1 | — |
| F02: shadcn-vue setup | 1 | F01 |
| F03: Tailwind theme | 1 | F02 |
| F04: Keycloak OIDC | 1-2 | F01 |
| F05: API service layer | 2 | F01 |
| F06: MainLayout | 2-3 | F02, F03 |
| F07: Vue Router | 2 | F01 |
| F08: Pinia stores | 2-3 | F01 |
| F09: Dashboard KPI | 3 | F06, F08 |
| F10: Process Definitions List | 3 | F06, F08 |
| F11: Process Definition Details | 3-4 | F10 |
| F12: Process Instances List | 4 | F06, F08 |
| F13: Process Instance Details | 4 | F12 |
| F14: Task List | 4-5 | F06, F08 |
| F15: Task Details + Complete | 5 | F14 |
| F16: Incident List | 5 | F06, F08 |
| F17: Incident Details + Resolve | 5-6 | F16 |
| F18: User List (Admin) | 6 | F06 |

### Зависимости от Backend

1. **Критическая**: Добавить JWT validation endpoint (`POST /auth/validate`)
2. **Критическая**: Добавить `users` CRUD endpoints (`GET/POST/PUT /admin/users`)
3. **Важная**: Добавить `completedAt` фильтр в `IncidentQuery`
4. **Важная**: Добавить `processDefinitionKey` и `processDefinitionVersion` в `ProcessInstance` response
5. **Желательная**: Добавить `assignee`, `candidateUsers`, `candidateGroups` в `UserTask` response

### Риски

| Риск | Вероятность | Влияние | Митигация |
|---|---|---|---|
| Keycloak integration delays | Средняя | Высокое | Mock auth для dev |
| Backend API changes | Высокая | Среднее | Versioned API, adapter layer |
| bpmn-js learning curve | Средняя | Среднее | Start with simple viewer |
| Performance на больших списках | Низкая | Среднее | Virtual scrolling |

### Объём

- ~20 компонентов
- ~10 API services
- ~8 Pinia stores
- ~3500 строк кода (est.)

---

## Этап 2 — Monitoring (2-3 недели)

**Цель**: Расширенный мониторинг с графиками и BPMN-визуализацией.

### Состав работ

| Задача | Неделя |
|---|---|
| F19: Dashboard charts | 7 |
| F20: Deploy BPMN | 7-8 |
| F21: BPMN Viewer | 8-9 |
| F22: Start Process modal | 8 |
| F23: Variables editor | 9 |
| F24: Execution history | 9-10 |
| F25: Incident heatmap | 10 |

### Зависимости

- bpmn-js npm package
- Recharts/Chart.js для графиков

### Объём

- ~12 компонентов
- ~2000 строк кода (est.)

---

## Этап 3 — BPMN Viewer (2 недели)

**Цель**: Полноценный BPMN viewer с подсветкой активных элементов и token visualization.

### Состав работ

| Задача | Неделя |
|---|---|
| F26: Global search | 11 |
| F27: Notifications | 11 |
| Token visualization | 11-12 |
| Element click → details panel | 12 |
| Minimap integration | 12 |

### Объём

- ~8 компонентов
- ~1500 строк кода (est.)

---

## Этап 4 — Administration (2 недели)

**Цель**: Полный admin panel с пользователями и настройками.

### Состав работ

| Задача | Неделя |
|---|---|
| User CRUD (create, edit, activate/deactivate) | 13 |
| User search and filtering | 13 |
| Settings page | 14 |
| Audit log (future) | 14 |

### Зависимости

- Backend: `users` CRUD endpoints
- Backend: `settings` endpoints

### Объём

- ~6 компонентов
- ~1200 строк кода (est.)

---

## Этап 5 — Enterprise Features (4-6 недель)

**Цель**: DMN, аналитика, продвинутые возможности.

### Состав работ

| Задача | Неделя |
|---|---|
| F28: DMN Viewer | 15-16 |
| F29: DMN Tester | 16-17 |
| F30: Timers management | 17 |
| F31: Messages management | 17-18 |
| F32: SLA reports | 18-19 |
| F33: BPMN Designer | 19-21 |
| F34: Bulk operations | 20 |
| F35: Export | 20-21 |
| F36: Dark mode | 21 |
| F37: Responsive mobile | 21-22 |

### Объём

- ~15 компонентов
- ~4000 строк кода (est.)

---

## Итоговая оценка

| Этап | Недели | Компоненты | Строки кода |
|---|---|---|---|
| MVP | 4-6 | ~20 | ~3500 |
| Monitoring | 2-3 | ~12 | ~2000 |
| BPMN Viewer | 2 | ~8 | ~1500 |
| Administration | 2 | ~6 | ~1200 |
| Enterprise | 4-6 | ~15 | ~4000 |
| **Итого** | **14-19** | **~61** | **~12200** |

---

# 23. MVP Scope Review

## 23.1 Определение MVP

**Цель MVP**: минимально жизнеспособный продукт, позволяющий оператору и администратору работать с процессами через единый веб-интерфейс.

**Исключено из MVP**: BPMN Designer, DMN Editor, Process Analytics, Timers Management, Messages Management, Signals Management.

## 23.2 Состав MVP

| # | Раздел | Бизнес-ценность | Необходимость для запуска | Зависимости от Backend |
|---|---|---|---|---|
| 1 | **Authentication** | Критическая — без авторизации продукт недоступен | Обязательна | Keycloak OIDC (существует) + Backend JWT validation (новый endpoint) |
| 2 | **Users** | Высокая — контроль доступа, white list | Обязательна для production | `GET/POST/PUT/DELETE /admin/users` (новый Backend) |
| 3 | **Dashboard** | Высокая — точка входа, обзор состояния | Желательна | `GET /process-instances`, `GET /incidents`, `GET /user-tasks` (существуют) |
| 4 | **Process Definitions** | Критическая — просмотр задеплоенных процессов | Обязательна | `GET /process-definitions` (существует) |
| 5 | **Process Instances** | Критическая — мониторинг запущенных процессов | Обязательна | `GET /process-instances` (существует), но缺少 processDefinitionKey/version в response |
| 6 | **Process Details** | Высокая — детальный просмотр экземпляра | Обязательна | `GET /variables`, `GET /user-tasks`, `GET /incidents` (существуют) + BPMN Structure (существует) |
| 7 | **User Tasks** | Критическая — оператор обрабатывает задачи | Обязательна | `GET /user-tasks`, `POST /user-tasks/{id}/complete` (существуют) |
| 8 | **Task Details** | Критическая — просмотр и завершение задачи | Обязательна | `GET /user-tasks`, `GET /variables`, `POST /user-tasks/{id}/complete` (существуют) |
| 9 | **Incidents** | Высокая — обработка ошибок процессов | Обязательна | `GET /incidents`, `POST /incidents/{id}/resolve` (существуют) |
| 10 | **Incident Details** | Высокая — анализ и разрешение инцидентов | Обязательна | `GET /incidents`, `POST /incidents/{id}/resolve` (существуют) |
| 11 | **BPMN Viewer** | Высокая — визуализация графа процесса | Желательна | `GET /process-definitions/{id}/xml`, `GET /process-definitions/{id}/structure` (существуют) |

## 23.3 Что НЕ входит в MVP

| Раздел | Причина исключения | Когда добавить |
|---|---|---|
| BPMN Designer | Очень высокая сложность, нет срочной бизнес-need | Этап 5 |
| DMN Editor | Нет REST API для DMN deploy на Backend | Этап 5 |
| Process Analytics | Нет statistics API на Backend, вычисления на клиенте — ненадёжны | Этап 3 |
| Timers Management | Нет REST API для таймеров | Этап 4 |
| Messages Management | Нет REST API для сообщений | Этап 4 |
| Signals Management | Нет REST API для сигналов | Этап 4 |
| SLA Reports | Нет SLA данных на Backend | Этап 3 |
| Export CSV/Excel | Низкая приоритетность | Этап 4 |
| Dark mode | Косметическое улучшение | Этап 5 |
| Mobile responsive | Целевая аудитория — desktop | Этап 5 |

---

# 24. Backend Gap Analysis

## 24.1 Backend Requirements For Frontend

### P1 — Критично (блокирует MVP)

| # | Endpoint | Метод | Request | Response | Назначение | Экран |
|---|---|---|---|---|---|---|
| B1 | `/auth/me` | `GET` | — | `{ login, fullName, email, active }` | Получение информации о текущем аутентифицированном пользователе из JWT | Все экраны |
| B2 | `/admin/users` | `GET` | `?pageIndex,pageSize,login,active` | `PagedDataDTO<User>` | Список пользователей | Admin → Users |
| B3 | `/admin/users` | `POST` | `{ login, fullName, email, active }` | `IdDTO` | Создание пользователя | Admin → Users |
| B4 | `/admin/users/{id}` | `GET` | — | `User` | Получение пользователя | Admin → User Form |
| B5 | `/admin/users/{id}` | `PUT` | `{ fullName, email, active }` | `IdDTO` | Редактирование пользователя | Admin → User Form |
| B6 | `/process-instances` | `GET` | `?processDefinitionId` (уже есть) | `PagedDataDTO<ProcessInstance>` | Список экземпляров (существует) | Process Instances |

### P2 — Важно (улучшает UX)

| # | Endpoint | Метод | Request | Response | Назначение | Экран |
|---|---|---|---|---|---|---|
| B7 | `/process-instances/{id}` | `GET` | — | `ProcessInstance` с `processDefinitionKey`, `processDefinitionVersion`, `processDefinitionName` | Детали экземпляра с данными определения | Process Instance Details |
| B8 | `/user-tasks` | `GET` | `?assignee=me` (имя из JWT) | `PagedDataDTO<UserTask>` с `processDefinitionName` | Задачи текущего пользователя | Task List |
| B9 | `/incidents` | `GET` | `?completedAt=null` (open incidents) | `PagedDataDTO<Incident>` с `processInstanceId`, `processDefinitionName`, `bpmnElementId` | Открытые инциденты | Incident List, Dashboard |
| B10 | `/process-definitions/{id}` | `GET` | — | `ProcessDefinition` с `instanceCount`, `activeInstanceCount`, `incidentCount` | Метаданные с агрегированной статистикой | Process Definition Details |

### P3 — Желательно (nice-to-have)

| # | Endpoint | Метод | Request | Response | Назначение | Экран |
|---|---|---|---|---|---|---|
| B11 | `/dashboard/stats` | `GET` | — | `{ activeProcesses, openTasks, openIncidents, completedToday }` | KPI для Dashboard | Dashboard |
| B12 | `/process-definitions/{id}/instances` | `GET` | `?pageIndex,pageSize` | `PagedDataDTO<ProcessInstance>` | Инстансы конкретного определения | Process Definition Details |

## 24.2 Существующие API (уже работают)

| Endpoint | Метод | Готовность |
|---|---|---|
| `/process-definitions` | `POST` | ✅ |
| `/process-definitions` | `GET` | ✅ |
| `/process-definitions/{id}` | `GET` | ✅ |
| `/process-definitions/{id}/xml` | `GET` | ✅ |
| `/process-definitions/{id}/structure` | `GET` | ✅ |
| `/process-instances` | `POST` | ✅ |
| `/process-instances` | `GET` | ✅ |
| `/user-tasks` | `GET` | ✅ |
| `/user-tasks/{id}/complete` | `POST` | ✅ |
| `/service-tasks` | `GET` | ✅ |
| `/service-tasks/{id}/complete` | `POST` | ✅ |
| `/incidents` | `GET` | ✅ |
| `/incidents/{id}/resolve` | `POST` | ✅ |
| `/variables` | `GET` | ✅ |

---

# 25. Authentication Architecture

## 25.1 Keycloak Configuration

| Параметр | Значение |
|---|---|
| Provider | Keycloak |
| Realm | `KT` |
| OIDC Discovery | `https://keycloak.example.com/realms/KT/.well-known/openid-configuration` |
| Authorization Endpoint | `https://keycloak.example.com/realms/KT/protocol/openid-connect/auth` |
| Token Endpoint | `https://keycloak.example.com/realms/KT/protocol/openid-connect/token` |
| Logout Endpoint | `https://keycloak.example.com/realms/KT/protocol/openid-connect/logout` |
| Flow | Authorization Code + PKCE |
| Client ID | Будет предоставлен позже |
| Client Secret | Будет предоставлен позже |

## 25.2 Environment Variables

```env
# .env
VITE_KEYCLOAK_URL=https://keycloak.example.com
VITE_KEYCLOAK_REALM=KT
VITE_KEYCLOAK_CLIENT_ID=<provided-later>
VITE_KEYCLOAK_REDIRECT_URI=http://localhost:5173
VITE_API_URL=http://localhost:8080
```

## 25.3 OIDC Flow

```
1. User opens ZorroBPM → redirect to Keycloak
2. Keycloak login page → user authenticates
3. Keycloak redirects back with authorization code
4. Frontend exchanges code for tokens (access_token, refresh_token, id_token)
5. Tokens stored in memory (Pinia store, NOT localStorage)
6. All API requests include Authorization: Bearer {access_token}
7. Token refresh: automatic 30 seconds before expiry
8. Logout: clear tokens + redirect to Keycloak logout endpoint
```

## 25.4 Frontend Auth State

```
auth store:
  isAuthenticated: boolean
  user: { login, fullName, email }
  accessToken: string (in memory only)
  refreshToken: string (in memory only)
```

## 25.5 Backend Validation

Backend endpoint `GET /auth/me`:
1. Receives `Authorization: Bearer {token}` header
2. Validates JWT signature via Keycloak public key
3. Extracts `preferred_username` from JWT claims
4. Looks up user in `users` table by `login = preferred_username`
5. Returns user info or 403 Forbidden

---

# 26. User White List Strategy

## 26.1 Модель данных

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    login VARCHAR(255) NOT NULL UNIQUE,
    full_name VARCHAR(255),
    email VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

## 26.2 Логика доступа

```
1. User authenticates via Keycloak → receives JWT
2. Frontend sends JWT to Backend
3. Backend extracts preferred_username from JWT
4. SELECT * FROM users WHERE login = preferred_username
5. If found AND active = true → 200 OK (user info)
6. If not found → 403 Forbidden
7. If active = false → 403 Forbidden
```

## 26.3 Frontend Behavior

```
if (auth.me.active === false) → show "Access denied" page
if (auth.me === null) → show "Access denied" page
else → show MainLayout
```

---

# 27. Future RBAC Strategy

## 27.1 Архитектурная модель (не реализовывать сейчас)

```
users          → id, login, full_name, email, active
roles          → id, name (admin, developer, operator, viewer)
user_roles     → user_id, role_id
permissions    → id, name (process.deploy, process.start, task.complete, ...)
role_permissions → role_id, permission_id
groups         → id, name (department-finance, team-backend)
user_groups    → user_id, group_id
process_permissions → process_definition_id, group_id, permission_type
```

## 27.2 Принципы расширения

1. **useAuthorization()** composable: проверяет `auth.me.roles` → `permissions`
2. **Router guards**: `meta: { requiresPermission: 'process.deploy' }`
3. **UI elements**: `v-if="hasPermission('task.complete')"` скрывает кнопки
4. **Backend**: JWT claims расширены roles/permissions
5. **Миграция**: текущая `users.active` → базовый ACL, роли добавляются как новая таблица

---

# 28. User Management

## 28.1 Экран: Administration → Users

### Список пользователей

**Endpoint**: `GET /admin/users`

| Колонка | Источник | Фильтр |
|---|---|---|
| Login | `User.login` | Текстовый поиск |
| Full Name | `User.fullName` | — |
| Email | `User.email` | — |
| Active | `User.active` | Toggle (Active/Inactive) |
| Created | `User.createdAt` | Date Range |
| Actions | Edit / Activate / Deactivate | — |

### Создание пользователя

**Endpoint**: `POST /admin/users`

```
Form:
  Login:       [________] (обязательное, уникальное)
  Full Name:   [________]
  Email:       [________]
  Active:      [✓] (по умолчанию true)
```

### Редактирование пользователя

**Endpoint**: `PUT /admin/users/{id}`

```
Form:
  Login:       [john.doe] (readonly)
  Full Name:   [John Doe]
  Email:       [john@example.com]
  Active:      [✓]
```

### Активация/Блокировка

**Endpoint**: `PUT /admin/users/{id}` с `{ active: true/false }`

---

# 29. Information Architecture

## 29.1 Sidebar Navigation (MVP)

```
┌─────────────────────────┐
│ ZorroBPM                │
├─────────────────────────┤
│ Dashboard               │
├─────────────────────────┤
│ Processes               │
│   Definitions           │
│   Instances             │
├─────────────────────────┤
│ Tasks                   │
│   My Tasks              │
│   All Tasks             │
├─────────────────────────┤
│ Incidents               │
├─────────────────────────┤
│ Administration          │
│   Users                 │
└─────────────────────────┘
```

## 29.2 Header

```
┌──────────────────────────────────────────────────────────┐
│ [Logo] ZorroBPM   [🔍 Search]   [👤 John Doe ▼]        │
└──────────────────────────────────────────────────────────┘
```

- **Search**: глобальный поиск по process definitions, instances, tasks
- **User Menu**: profile info, logout

## 29.3 Breadcrumbs (MVP)

```
/                                       → Dashboard
/processes/definitions                  → Processes > Definitions
/processes/definitions/:id              → Processes > Definitions > Order Processing v3
/processes/instances                    → Processes > Instances
/processes/instances/:id                → Processes > Instances > PI-a1b2c3d4
/tasks                                  → Tasks > My Tasks
/tasks/:id                              → Tasks > My Tasks > Review Order #123
/incidents                              → Incidents
/incidents/:id                          → Incidents > INC-xyz
/admin/users                            → Administration > Users
```

---

# 30. Dashboard

## 30.1 MVP Widgets

### Row 1: KPI Cards

| Виджет | Источник | Формула |
|---|---|---|
| Active Processes | `GET /process-instances?completed=false` | `totalElements` |
| Open Tasks | `GET /user-tasks?completed=false` | `totalElements` |
| Open Incidents | `GET /incidents?completedAt=null` | `totalElements` |
| Completed Today | `GET /process-instances?completed=true` | `totalElements` (фильтр на клиенте) |

### Row 2: Tables

| Виджет | Данные | Колонки |
|---|---|---|
| Recent Incidents | `GET /incidents` (last 5) | Message, Process, Created, Status |
| My Open Tasks | `GET /user-tasks?completed=false` (last 5) | Name, Process, Created |

## 30.2 Future Widgets

| Виджет | Источник | Описание |
|---|---|---|
| Process Instances Trend | Агрегация на клиенте | Line chart запусков/завершений |
| Task Distribution | Агрегация на клиенте | Doughnut chart |
| Incident Heatmap | Агрегация на клиенте | Bar chart по process definitions |
| Active Timers | `GET /timer-jobs` (future API) | Список активных таймеров |

---

# 31. Process Monitoring

## 31.1 MVP

### Process Definitions List

**Endpoint**: `GET /process-definitions`

| Колонка | Источник | Сортировка |
|---|---|---|
| Name | `ProcessDefinition.name` | ✅ |
| Key | `ProcessDefinition.key` | ✅ |
| Version | `ProcessDefinition.version` | ✅ |
| Created | `ProcessDefinition.createdAt` | ✅ |
| Actions | Start / View | — |

**Фильтры**: Name, Key, Version, Latest version only

### Process Instances List

**Endpoint**: `GET /process-instances`

| Колонка | Источник | Сортировка |
|---|---|---|
| ID | `ProcessInstance.id` | — |
| Definition | `processDefinitionId` → name (client-side join) | — |
| Started | `ProcessInstance.startedAt` | ✅ |
| Completed | `ProcessInstance.completedAt` | ✅ |
| Status | Badge: Running / Completed | — |

**Фильтры**: Process Definition, Status (Running/Completed)

### Process Instance Details

**Tabs**:

| Tab | Данные | Описание |
|---|---|---|
| BPMN Flow | `GET /process-definitions/{id}/structure` + activities | BPMN viewer с подсветкой |
| Variables | `GET /variables?processInstanceId={id}` | Таблица переменных |
| Tasks | `GET /user-tasks?processInstanceId={id}` + `GET /service-tasks?processInstanceId={id}` | Задачи экземпляра |
| Incidents | `GET /incidents?processInstanceId={id}` | Инциденты экземпляра |

## 31.2 Future

| Функция | Описание |
|---|---|
| Execution history timeline | Timeline по элементам |
| Token visualization | Анимированные токены на графе |
| Variables editor | Inline editing переменных |
| Process statistics | KPI по конкретному процессу |

---

# 32. Task Management

## 32.1 MVP

### Task List

**Endpoint**: `GET /user-tasks?completed=false`

| Колонка | Источник |
|---|---|
| Name | `UserTask.name` |
| Process | `processDefinitionId` → name |
| Created | `UserTask.createdAt` |
| Actions | View |

**Фильтры**: Process Definition, Status

### Task Details

**Endpoint**: `GET /user-tasks?id={id}` + `GET /variables?processInstanceId={id}`

**Layout**:

```
┌──────────────────────────────────────────────────────────┐
│ Task: Review Order #123                                  │
│ Process: Order Processing v3 | Created: 2m ago           │
├──────────────────────────────────────────────────────────┤
│ [Task Details] [Variables]                               │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ Variables:                                               │
│ ┌────────────────────────────────────────────────────┐   │
│ │ Name        │ Type    │ Value                      │   │
│ │ orderId     │ LONG    │ 12345                      │   │
│ │ customer    │ STRING  │ John Doe                   │   │
│ └────────────────────────────────────────────────────┘   │
│                                                          │
│                    [Complete Task]                        │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### Task Completion Flow

1. Пользователь открывает задачу
2. Frontend загружает variables
3. Пользователь просматривает/изменяет variables
4. Нажимает "Complete Task"
5. Frontend: `POST /user-tasks/{id}/complete` с variables
6. Backend завершает задачу
7. Frontend обновляет UI

## 32.2 Future

| Функция | Описание |
|---|---|
| Bulk operations | Массовое завершение задач |
| Task assignment | Назначение задач |
| Claim/Unclaim | Взятие задачи в работу |
| Task comments | Комментарии к задачам |
| Task history | История действий над задачей |

---

# 33. Incident Management

## 33.1 MVP

### Incident List

**Endpoint**: `GET /incidents`

| Колонка | Источник |
|---|---|
| Message | `Incident.message` |
| Process Instance | `Incident.activityId` → processInstanceId |
| Created | `Incident.createdAt` |
| Status | Badge: Open / Resolved |
| Actions | View / Resolve |

**Фильтры**: Status (Open/Resolved), Process Instance

### Incident Details

**Endpoint**: `GET /incidents?id={id}` + related data

**Layout**:

```
┌──────────────────────────────────────────────────────────┐
│ Incident                                                 │
│ Status: Open | Created: 2m ago                           │
├──────────────────────────────────────────────────────────┤
│ [Overview] [Process Context] [Actions]                   │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ Message: NullPointerException at line 649                │
│ Element: callActivity_1 (Call Activity)                  │
│ Process: Order Processing v3                             │
│ Instance: PI-a1b2c3d4                                   │
│                                                          │
│ [Resolve Incident]  [View Process]                       │
│                                                          │
│ Resolve options:                                         │
│ ☐ Update variables before retry                          │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### Resolve Flow

1. Пользователь нажимает "Resolve Incident"
2. Опционально добавляет/изменяет переменные
3. Нажимает "Confirm Resolve"
4. Frontend: `POST /incidents/{id}/resolve` с variables
5. Backend повторно исполняет элемент
6. Frontend обновляет статус

---

# 34. BPMN Viewer

## 34.1 MVP

### Технология

**Библиотека**: `bpmn-js` (bpmn.io)

### Возможности MVP

| Функция | Описание | Сложность |
|---|---|---|
| Просмотр BPMN | Загрузка XML, рендеринг графа | Низкая |
| Zoom in/out | Кнопки + mouse wheel | Низкая |
| Fit to viewport | Автоматическое масштабирование | Низкая |
| Minimap | Мини-карта в углу | Низкая |
| Active element highlighting | Синяя подсветка активных элементов | Средняя |
| Incident highlighting | Красная подсветка элементов с инцидентами | Средняя |
| Completed element highlighting | Зелёная подсветка завершённых элементов | Средняя |
| Click → element details | Клик по элементу → panel с информацией | Средняя |

### UX взаимодействия

1. **Открытие**: BPMN viewer открывается на странице Process Definition Details и Process Instance Details
2. **Навигация**: zoom buttons в toolbar, minimap в bottom-right
3. **Подсветка**: автоматическая на основе Activity data
4. **Клик**: клик по элементу →右侧 panel с name, type, properties
5. **Клик по task**: переход к task details

### Источники данных для подсветки

```
GET /process-definitions/{id}/structure → BPMN graph
GET /user-tasks?processInstanceId={id} → active tasks
GET /service-tasks?processInstanceId={id} → completed service tasks
GET /incidents?processInstanceId={id} → incidents
```

## 34.2 Future

| Функция | Описание |
|---|---|
| Token animation | Анимированные токены на sequence flows |
| Token count labels | Счётчики токенов на parallel gateways |
| Variable overlays | Значения переменных на элементах |
| Heatmap | Визуализация частоты прохождения элементов |
| Process analytics overlay | Время выполнения по элементам |
| Drill-down subprocess | Клик по subprocess → открытие вложенного графа |
| Export to image | Экспорт BPMN как PNG/SVG |

---

# 35. UX Review

## 35.1 Чего не хватает

| Проблема | Описание | Решение |
|---|---|---|
| Нет quick actions | Пользователю нужно переходить на отдельные страницы для common operations | Добавить action buttons на Dashboard |
| Нет confirmation dialogs | Критические действия (resolve incident, complete task) без подтверждения | Добавить Alert Dialog |
| Нет loading states | Непонятно, загружается ли данные | Добавить Skeleton компоненты |
| Нет empty states | Пустые списки без объяснения | Добавить Empty State с call-to-action |
| Нет error states | Ошибки API не показываются | Добавить Error Boundary |
| Нет breadcrumbs context | Breadcrumbs не показывают текущий контекст | Убедиться что breadcrumbs генерируются из router |

## 35.2 Что лишнее

| Элемент | Причина |
|---|---|
| Analytics в MVP | Нет Backend API, вычисления на клиенте ненадёжны |
| Timers/Messages/Signals management | Нет Backend API |
| Mobile responsive в MVP | Целевая аудитория — desktop операторы |

## 35.3 Не покрытые сценарии

| Сценарий | Описание |
|---|---|
| Пользователь не найден в white list | После Keycloak auth → 403 → показать "Access Denied" страницу |
| Session expired | 401 → автоматический refresh → если неудачно → redirect to login |
| Concurrent task completion | Два оператора завершают одну задачу → показать warning |
| Process definition version conflict | Деплой новой версии во время выполнения → показать warning |

## 35.4 Улучшения

| Улучшение | Описание | Приоритет |
|---|---|---|
| Keyboard shortcuts | Cmd+K search, Cmd+D deploy, Esc close | P2 |
| Breadcrumb click → modal | Клик по breadcrumb → quick preview | P3 |
| Drag & drop BPMN deploy | Drag XML file → deploy | P2 |
| Copy to clipboard | Клик по ID → копирование в буфер | P1 |
| Relative timestamps | "2m ago" вместо "2024-01-15 10:30" | P1 |
| Status badges | Цветовые индикаторы статусов | P1 |

---

# 36. Frontend Architecture Review

## 36.1 Структура проекта

```
zorrobpm-frontend/
├── src/
│   ├── app/              # Entry point, router, global config
│   ├── pages/            # Route-level components (one per URL)
│   ├── layouts/          # Layout wrappers (MainLayout, AuthLayout)
│   ├── widgets/          # Reusable UI blocks (Dashboard cards, BPMN viewer)
│   ├── features/         # Business logic composables (auth, processes, tasks)
│   ├── entities/         # TypeScript interfaces (ProcessDefinition, UserTask, ...)
│   ├── shared/           # Shared components (shadcn-vue), utilities
│   ├── stores/           # Pinia stores (auth, process, task, incident)
│   ├── services/         # API service layer (Axios wrappers)
│   ├── composables/      # Generic composables (usePagination, useSearch, ...)
│   └── types/            # TypeScript type definitions
```

## 36.2 Почему эта структура подходит для BPM

| Слой | Назначение для BPM |
|---|---|
| `pages/` | Каждый URL — отдельная страница (Process List, Task Details, ...) |
| `widgets/` | BPMN Viewer, KPI Cards, DataTable — переиспользуемые блоки |
| `features/` | Business logic: auth flow, task completion, incident resolution |
| `entities/` | TypeScript модели: ProcessDefinition, ProcessInstance, UserTask, Incident |
| `stores/` | Кэширование данных, состояние UI (sidebar, theme) |
| `services/` | API calls: processService, taskService, incidentService |

## 36.3 Ключевые решения

| Решение | Обоснование |
|---|---|
| Pinia (не Vuex) | Vue 3 ecosystem standard, TypeScript-first |
| Vue Router (не Nuxt) | Полный контроль над routing, нет server-side rendering |
| Tailwind CSS (не styled-components) | Утилитарный подход, быстрая разработка |
| shadcn-vue (не Vuetify) | Полный контроль над кодом компонентов, нет vendor lock-in |
| Axios (не fetch) | Interceptors, automatic JSON parsing, cancellation |
| bpmn-js (единственный BPMN library) | Стандарт де-факто, активная поддержка |

---

# 37. Shadcn-Vue Component Mapping

## 37.1 Для каждого экрана

### Dashboard

| Компонент | Зачем | Какую задачу решает |
|---|---|---|
| **Card** | KPI cards, widget containers | Компактные контейнеры с заголовком и контентом |
| **DataTable** | Recent incidents, My tasks | Табличные данные с сортировкой |
| **Badge** | Status indicators (Open/Resolved) | Цветовые индикаторы |
| **Skeleton** | Loading states | Placeholder пока данные загружаются |

### Process Definitions List

| Компонент | Зачем | Какую задачу решает |
|---|---|---|
| **DataTable** | Список определений | Сортировка, пагинация |
| **Input** | Поиск по имени | Текстовый фильтр |
| **Checkbox** | "Latest version only" | Toggle фильтр |
| **Button** | "Start Process", "View XML" | Действия |
| **Dialog** | Start Process modal | Модальная форма с переменными |
| **Badge** | Version number | Визуальное отображение версии |

### Process Instance Details

| Компонент | Зачем | Какую задачу решает |
|---|---|---|
| **Tabs** | BPMN Flow / Variables / Tasks / Incidents | Переключение контекста |
| **DataTable** | Variables, Tasks, Incidents | Табличные данные |
| **Card** | Instance header info | Информация о экземпляре |
| **Badge** | Status (Running/Completed) | Цветовой индикатор |

### Task List

| Компонент | Зачем | Какую задачу решает |
|---|---|---|
| **DataTable** | Список задач | Сортировка, пагинация |
| **Select** | Фильтр по процессу | Dropdown |
| **Badge** | Task status | Индикатор |

### Task Details

| Компонент | Зачем | Какую задачу решает |
|---|---|---|
| **Card** | Task header | Информация о задаче |
| **DataTable** | Variables | Таблица переменных |
| **Button** | "Complete Task" | Primary action |
| **Alert Dialog** | Confirm completion | Подтверждение действия |

### Incident Details

| Компонент | Зачем | Какую задачу решает |
|---|---|---|
| **Card** | Incident info | Детали инцидента |
| **Badge** | Status (Open/Resolved) | Индикатор |
| **Button** | "Resolve Incident" | Primary action |
| **Dialog** | Resolve with variables | Модальная форма |

### Administration → Users

| Компонент | Зачем | Какую задачу решает |
|---|---|---|
| **DataTable** | Список пользователей | Сортировка, пагинация |
| **Input** | Поиск по login | Текстовый фильтр |
| **Button** | "Create User" | Primary action |
| **Dialog** | Create/Edit user form | Модальная форма |
| **Switch** | Active/Inactive toggle | Включение/выключение |

### Global

| Компонент | Зачем | Какую задачу решает |
|---|---|---|
| **Breadcrumb** | Навигационный контекст | Показывает путь |
| **Sonner** | Toast notifications | Уведомления о действиях |
| **Tooltip** | Icon buttons | Контекстная помощь |
| **Command** | Global search (Cmd+K) | Palette-style поиск |
| **Separator** | Toolbar dividers | Визуальное разделение |
| **Avatar** | User menu | Отображение пользователя |
| **Dropdown Menu** | User menu actions | Logout, profile |

---

# 38. Frontend / Backend Team Separation

## 38.1 Разделение ответственности

### Frontend команда отвечает за:

- SPA разработка (Vue 3 + TypeScript)
- UI/UX дизайн
- Интеграция с REST API
- Keycloak OIDC (Frontend часть)
- bpmn-js интеграция
- Состояние приложения (Pinia)
- Тестирование UI

### Frontend команда НЕ отвечает за:

- BPMN Engine (Java)
- Business Logic (Java)
- Database (PostgreSQL)
- Security Implementation (Spring Security)
- Keycloak Configuration (Server-side)
- REST API Implementation (Spring MVC)
- Docker / Deployment

## 38.2 Коммуникация

- **API контракты**: OpenAPI/Swagger
- **Mock data**: Frontend использует mock данные пока Backend не готов
- **Sync meetings**: еженедельно для обсуждения API changes

---

# 39. Required Backend Services

## 39.1 Existing Services (уже работают)

| Service | Endpoint | Назначение |
|---|---|---|
| ProcessDefinitionService | `GET/POST /process-definitions` | CRUD определений |
| RuntimeService | `POST /process-instances`, `POST /user-tasks/{id}/complete`, `POST /incidents/{id}/resolve` | Запуск, завершение, разрешение |
| QueryService | `GET /process-instances`, `GET /user-tasks`, `GET /service-tasks`, `GET /incidents`, `GET /variables` | Запросы данных |
| BpmnStructureService | `GET /process-definitions/{id}/structure` | Структура BPMN |
| FileService | `GET /process-definitions/{id}/xml` | BPMN XML |

## 39.2 Missing Services (нужно реализовать)

| Service | Endpoint | Назначение | Приоритет |
|---|---|---|---|
| AuthService | `GET /auth/me` | Информация о текущем пользователе | P1 |
| UserService | `GET/POST/PUT /admin/users` | CRUD пользователей | P1 |
| ProcessInstanceDetailService | `GET /process-instances/{id}` | Детали с processDefinitionKey/version/name | P2 |
| DashboardStatsService | `GET /dashboard/stats` | KPI для Dashboard | P3 |

## 39.3 Nice To Have Services

| Service | Endpoint | Назначение |
|---|---|---|
| TimerService | `GET /timer-jobs` | Список таймеров |
| MessageService | `GET /message-subscriptions` | Список подписок |
| AnalyticsService | `GET /analytics/processes` | Статистика по процессам |
| ExportService | `GET /export/process-instances` | Экспорт в CSV |

---

# 40. API First Approach

## 40.1 Принцип

Frontend разрабатывается через API контракты. Бизнес-логика НЕ переносится на Frontend.

## 40.2 Process

1. Backend команда предоставляет OpenAPI specification
2. Frontend команда генерирует TypeScript types из OpenAPI
3. Frontend команда разрабатывает UI на основе mock данных
4. Интеграция с реальным Backend после готовности endpoints

## 40.3 Правила

- ❌ Не вычислять агрегаты на клиенте (если нет API)
- ❌ Не дублировать бизнес-логику на Frontend
- ❌ Не хранить чувствительные данные в localStorage
- ✅ Использовать API для всех данных
- ✅ Mock данные для разработки
- ✅ TypeScript types = API contract

---

# 41. Backend Backlog

## 41.1 Backend Requirements For MVP

### P1 — Критично

| # | Endpoint | Зачем нужен | Кто использует | Какой экран зависит |
|---|---|---|---|---|
| B1 | `GET /auth/me` | Получение информации о текущем пользователе | Все пользователи | Все экраны (авторизация) |
| B2 | `GET /admin/users` | Список пользователей | Administrator | Admin → Users |
| B3 | `POST /admin/users` | Создание пользователя | Administrator | Admin → Users |
| B4 | `GET /admin/users/{id}` | Детали пользователя | Administrator | Admin → User Form |
| B5 | `PUT /admin/users/{id}` | Редактирование пользователя | Administrator | Admin → User Form |

### P2 — Важно

| # | Endpoint | Зачем нужен | Кто использует | Какой экран зависит |
|---|---|---|---|---|
| B6 | `GET /process-instances/{id}` | Детали с processDefinitionKey/version/name | BPM Developer, Operator | Process Instance Details |
| B7 | `GET /user-tasks` с `processDefinitionName` в response | Имя процесса в списке задач | Operator | Task List |
| B8 | `GET /incidents` с `processDefinitionName` в response | Имя процесса в списке инцидентов | Operator, Admin | Incident List |

### P3 — Желательно

| # | Endpoint | Зачем нужен | Кто использует | Какой экран зависит |
|---|---|---|---|---|
| B9 | `GET /dashboard/stats` | KPI для Dashboard | Все | Dashboard |
| B10 | `GET /process-definitions/{id}/instances` | Инстансы конкретного определения | BPM Developer | Process Definition Details |

---

# 42. Sprint Planning

## Sprint 1 (недели 1-2): Foundation + Auth + Users

### Страницы

| Страница | URL |
|---|---|
| Login redirect | `/login` |
| Auth callback | `/auth/callback` |
| Access denied | `/access-denied` |
| Main Layout | `/_layout` |
| Administration → Users | `/admin/users` |

### Компоненты

- `MainLayout` (sidebar + header)
- `SidebarNav`
- `UserMenu`
- `AuthGuard`
- `UserList`
- `UserForm` (Dialog)
- `DataTable` (shared)
- `Badge` (shared)
- `Skeleton` (shared)
- `Sonner` (notifications)

### API

- `GET /auth/me` (B1)
- `GET /admin/users` (B2)
- `POST /admin/users` (B3)
- `GET /admin/users/{id}` (B4)
- `PUT /admin/users/{id}` (B5)

### Зависимости

- Keycloak configuration (Client ID, Realm)
- Backend: JWT validation + /auth/me endpoint
- Backend: users CRUD endpoints

### Критерии готовности

- [ ] Пользователь аутентифицируется через Keycloak
- [ ] После аутентификации показывается MainLayout
- [ ] Если пользователь не в white list → Access Denied
- [ ] Administrator может просматривать список пользователей
- [ ] Administrator может создавать/редактировать/блокировать пользователей

---

## Sprint 2 (недели 3-4): Dashboard + Processes

### Страницы

| Страница | URL |
|---|---|
| Dashboard | `/` |
| Process Definitions List | `/processes/definitions` |
| Process Definition Details | `/processes/definitions/:id` |
| Process Instances List | `/processes/instances` |
| Process Instance Details | `/processes/instances/:id` |

### Компоненты

- `Dashboard` (page)
- `KpiCards` (widget)
- `RecentIncidents` (widget)
- `MyOpenTasks` (widget)
- `ProcessDefinitionList` (page)
- `ProcessDefinitionDetail` (page)
- `ProcessInstanceList` (page)
- `ProcessInstanceDetail` (page)
- `VariablesTable` (widget)
- `StatusBadge` (widget)
- `StartProcessDialog` (widget)

### API

- `GET /process-definitions` (существует)
- `GET /process-definitions/{id}` (существует)
- `GET /process-definitions/{id}/structure` (существует)
- `GET /process-instances` (существует)
- `POST /process-instances` (существует)
- `GET /variables` (существует)
- `GET /user-tasks` (существует)
- `GET /service-tasks` (существует)
- `GET /incidents` (существует)

### Зависимости

- Sprint 1 готов (Layout, Auth)
- Mock данные для Dashboard stats

### Критерии готовности

- [ ] Dashboard показывает KPI cards
- [ ] Dashboard показывает recent incidents и my tasks
- [ ] Список process definitions загружается и фильтруется
- [ ] Клик по definition → details с tabs
- [ ] Список process instances загружается
- [ ] Клик по instance → details с variables, tasks, incidents
- [ ] "Start Process" работает через modal

---

## Sprint 3 (недели 5-6): Tasks + Incidents

### Страницы

| Страница | URL |
|---|---|
| My Tasks | `/tasks` |
| All Tasks | `/tasks/all` |
| Task Details | `/tasks/:id` |
| Incidents | `/incidents` |
| Incident Details | `/incidents/:id` |

### Компоненты

- `TaskList` (page)
- `TaskDetail` (page)
- `IncidentList` (page)
- `IncidentDetail` (page)
- `ResolveIncidentDialog` (widget)
- `CompleteTaskButton` (widget)
- `Breadcrumb` (shared)

### API

- `GET /user-tasks` (существует)
- `POST /user-tasks/{id}/complete` (существует)
- `GET /incidents` (существует)
- `POST /incidents/{id}/resolve` (существует)
- `GET /variables` (существует)

### Зависимости

- Sprint 2 готов (Processes, Layout)

### Критерии готовности

- [ ] Список моих задач загружается
- [ ] Клик по задаче → details с variables
- [ ] "Complete Task" работает
- [ ] Список инцидентов загружается
- [ ] Клик по инциденту → details с message, process context
- [ ] "Resolve Incident" работает

---

## Sprint 4 (недели 7-8): BPMN Viewer

### Страницы

| Страница | URL |
|---|---|
| Process Definition Details (с BPMN viewer) | `/processes/definitions/:id` |
| Process Instance Details (с BPMN viewer) | `/processes/instances/:id` |

### Компоненты

- `BpmnViewer` (widget)
- `BpmnToolbar` (widget)
- `BpmnElementDetails` (widget)
- `BpmnHighlighter` (composable)

### API

- `GET /process-definitions/{id}/xml` (существует)
- `GET /process-definitions/{id}/structure` (существует)
- `GET /user-tasks?processInstanceId={id}` (существует)
- `GET /incidents?processInstanceId={id}` (существует)

### Зависимости

- bpmn-js npm package
- Sprint 2 готов (Process Details)

### Критерии готовности

- [ ] BPMN viewer рендерит XML
- [ ] Zoom in/out работает
- [ ] Fit to viewport работает
- [ ] Minimap отображается
- [ ] Активные элементы подсвечиваются
- [ ] Элементы с инцидентами подсвечиваются
- [ ] Клик по элементу показывает details panel

---

# 43. Final Architecture Review

## 43.1 Слабые места

| Проблема | Описание | Решение |
|---|---|---|
| Нет real-time обновлений | Dashboard и списки нужно обновлять вручную | Добавить auto-refresh (polling) или SSE |
| Нет optimistic updates | При завершении задачи UI обновляется только после ответа сервера | Добавить optimistic UI для common actions |
| Нет offline support | При потере сети приложение неработоспособно | Добавить service worker (P3) |
| Нет error boundary | Ошибка в одном компоненте может ронять всё приложение | Добавить Error Boundary на level pages |

## 43.2 Спорные решения

| Решение | Аргументы за | Аргументы против |
|---|---|---|
| Pinia (не Vuex) | TypeScript-first, проще API | Меньше community examples |
| shadcn-vue (не Vuetify) | Полный контроль, нет vendor lock-in | Нужно вручную кастомизировать |
| Tailwind (не CSS modules) | Быстрая разработка, consistent design | Классы в template могут выглядеть грязно |
| Axios (не ky/fetch) | Interceptors, cancellation | Дополнительная зависимость |

## 43.3 Риски

| Риск | Вероятность | Влияние | Митигация |
|---|---|---|---|
| Backend API delays | Высокая | Высокое | Mock данные, API-first approach |
| Keycloak configuration delays | Средняя | Высокое | Mock auth для development |
| bpmn-js complexity | Средняя | Среднее | Start with simple viewer |
| Performance на больших данных | Низкая | Среднее | Virtual scrolling, pagination |
| Team velocity underestimate | Средняя | Среднее | Buffer time, iterative approach |

## 43.4 Ограничения

| Ограничение | Описание |
|---|---|
| Single instance only | Backend работает в одном экземпляре |
| No WebSocket | Нет real-time обновлений |
| No RBAC | Только white list модель |
| No export | Нет экспорта данных |
| No i18n | Только русский/английский язык |
| No dark mode | Только light theme |

## 43.5 Что сделал бы иначе архитектор уровня Camunda

1. **WebSocket/SSE для real-time**: Camunda использует WebSocket для live обновлений задач и инцидентов. ZorroBPM должен добавить это в Этап 2.

2. **Event sourcing**: Camunda хранит полную историю событий. ZorroBPM хранит только текущее состояние. Для analytics нужна event log.

3. **Process versioning UI**: Camunda показывает diff между версиями. ZorroBPM должен добавить version comparison.

4. **Decision table editor**: Camunda включает DMN editor. ZorroBPM должен добавить это в Этап 5.

5. **Form builder**: Camunda Form Builder для user tasks. ZorroBPM должен добавить form generation из variables.

6. **Multi-tenancy**: Camunda поддерживает multi-tenancy. ZorroBPM — single tenant.

7. **Audit log**: Camunda хранит audit trail. ZorroBPM должен добавить это для compliance.

8. **GraphQL API**: Camunda использует GraphQL для гибких запросов. ZorroBPM использует REST с фиксированными queries.
