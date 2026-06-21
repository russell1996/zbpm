# MiMo Memory — ZorroBPM Project

## Project Purpose
ZorroBPM CE — lightweight BPMN 2.0 engine on Spring Boot 4.0.5 / Java 21 / PostgreSQL. Multi-module Maven project executing BPMN processes via token-based graph traversal.

## Architecture

### Backend (10 Maven modules)
- **zorrobpm-contract** — REST API interfaces + DTOs (CRITICAL — breaking changes ripple everywhere)
- **zorrobpm-engine** — Core: BPMN parsing (JAXB), token execution, persistence (JPA), timers, DMN (CRITICAL)
- **zorrobpm-rest** — REST controllers implementing contracts
- **zorrobpm-rabbitmq** — RabbitMQ integration (service task routing)
- **zorrobpm-event** — Domain events (14 Spring ApplicationEvent classes)
- **zorrobpm-exchange** — MQ message models (6 DTO classes)
- **zorrobpm-client** — Java REST client SDK
- **zorrobpm-job-handler-spring-boot-starter** — Worker SDK (JobHandler interface)
- **zorrobpm-ce** — Bootstrap app (scans engine, rest, rabbitmq)
- **zorrobpm-test** — Test helpers

### Frontend (Vue 3 SPA)
- **Stack**: Vue 3 + Vite 8 + TypeScript 6 + Tailwind CSS 4 + Pinia + Vue Router + vue-i18n
- **Auth**: Keycloak OIDC (currently mocked)
- **API**: Axios with `/api` base (nginx-proxied)
- **i18n**: RU (default) / EN / KZ
- **Theme**: Light/Dark via CSS variables
- **Build**: `npm run build` = `tsc && vite build`

## Key Services
- **ActivityService** — core execution (execute, complete tasks, signal, correlate messages, fire timers)
- **RuntimeService** — public entry point
- **DBService** — persistence abstraction (186 lines interface, scoped variables, gateway tracking)
- **BpmnService** — BPMN model cache
- **ScriptService** — FEEL expression evaluation (Camunda feel-engine 1.19.3)

## Database
PostgreSQL 16, 38 Liquibase migrations. Key tables: process_definitions, process_instances, activities, tokens, variables (scoped), user_tasks, service_tasks, incidents, timer_jobs, message/signal subscriptions, parallel_gateways, dmn_definitions.

## REST API
3 controllers: ProcessDefinitionResource, RuntimeResource (@Transactional), QueryResource. Endpoints: process-definitions (CRUD+XML+structure), process-instances (start), user-tasks/service-tasks (complete), incidents (resolve), queries (paginated).

## RabbitMQ
Queues: `zorrobpm.jobs.<jobType>` (per-type, auto-declared), `zorrobpm.complete-service-task` (completion with DLQ).

## CI/CD
GitLab CI: test → build → deploy → rollback. Docker builds, SHA-tagged images. Production: `https://zorro.i-smet.kz`.

## Frontend State
- All core pages functional (Dashboard, Processes, Tasks, Service Tasks, Incidents)
- CopyableId component for all IDs
- i18n (RU/EN/KZ) with language switcher
- Light/Dark theme, collapsible sidebar
- Lazy loading in ProcessInstanceDetail
- Auth mocked, some pages use mock data (timers, messages, dmn, users)

## Rules
- Frontend-only sessions: only modify `zorrobpm-frontend/**`, `docs/**`, `README.md`
- Never modify backend modules, CI/CD, Docker, infrastructure
- Backend API gaps → document in `docs/BACKEND_REQUIRED.md`
- Build verification: `npm run build` after every task
- Entity names in English, actions/buttons use i18n

## Memory Files
Local persistent memory at `.mimocode/memory/`:
- `project-memory.md` — full architecture reference
- `project-rules.md` — all rules and constraints
- `project-workflow.md` — development workflows
- `project-history.md` — project timeline
- `project-map.md` — complete file structure
- `backend-gaps.md` — API gaps for frontend
- `current-focus.md` — current status and next steps
