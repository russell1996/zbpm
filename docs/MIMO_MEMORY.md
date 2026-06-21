# MiMo Memory - ZorroBPM Frontend

## Project Purpose
ZorroBPM CE - lightweight BPMN 2.0 engine on Spring Boot. Frontend is a Vue 3 + Vite + TypeScript SPA combining Operate, Tasklist, and Cockpit functionality.

## Architecture
- **Frontend**: Vue 3 + Vite + TypeScript + Tailwind CSS 4
- **State Management**: Pinia
- **Routing**: Vue Router
- **UI Components**: Radix Vue + Lucide Icons
- **Internationalization**: vue-i18n (RU/EN/KZ)
- **BPMN Viewer**: bpmn-js
- **Build**: `npm run build` (tsc + vite build)

## Main Modules
- Dashboard
- Processes (Definitions, Instances, Deploy)
- Tasks (User Tasks, Service Tasks)
- Incidents
- Timers
- Messages
- DMN
- Analytics
- Admin (Users)

## Found Skills
- `.mimocode/` directory exists but no skills found in subdirectories

## Found Commands
- None found in `.mimocode/commands/`

## Current Frontend State
- ✅ Dashboard with clickable cards (Processes, Tasks, Service Tasks, Incidents)
- ✅ Service Tasks pages (ServiceTaskList, ServiceTaskDetail)
- ✅ CopyableId component for full ID display with tooltip/copy
- ✅ i18n setup (RU/EN/KZ) with language switcher in HeaderBar
- ✅ Light/Dark theme support in style.css
- ✅ Refresh buttons on lists
- ✅ Process Instance Detail with tabs (User Tasks, Service Tasks)
- ✅ DESC sorting (new records on top)

## Current Backend State
- Backend API exists at `/api`
- Endpoints available: process-definitions, process-instances, user-tasks, service-tasks, incidents, variables

## Accepted Architecture Decisions
1. Single SPA with unified navigation
2. No hardcoded URLs - relative `/api` path
3. Light/Dark theme via CSS variables
4. i18n with vue-i18n
5. CopyableId component for all IDs

## Open Tasks
- [x] Service Tasks pages
- [x] Full ID display with tooltip/copy
- [x] Process Name display
- [x] DESC sorting
- [x] Light theme sidebar fix
- [x] Process Instance Detail tabs
- [x] Refresh functionality
- [x] Dashboard navigation
- [x] i18n setup (RU/EN/KZ)
- [x] Variable forms in ProcessInstanceDetail modal
- [x] New logo (BPM) - favicon, sidebar, login page

## Git Status (Current Session)
- Modified files: 30 frontend files
- New files: 6 (i18n, locales, ServiceTaskList, ServiceTaskDetail, CopyableId, test-routes.mjs)
- All changes in zorrobpm-frontend/** only

## Build Status
- `npm run build` passes successfully
- No TypeScript errors
- No build errors

## Known Limitations
- Backend API gaps documented in `docs/frontend-api-gaps.md`
- No WebSocket/SSE for real-time updates
- No BPMN designer (only viewer)
