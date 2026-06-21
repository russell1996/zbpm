---
description: "Develop, build and containerize the zorrobpm-frontend SPA (Vue 3 + Vite + TS) for ZorroBPM"
---

# Frontend Build & Containerization

Workflow for the `zorrobpm-frontend` SPA. Stack: Vue 3 (Composition API), TypeScript, Vite 8, Pinia,
Vue Router, Tailwind 4, shadcn-vue (radix-vue), Axios, bpmn-js. Node 22.

## Local development

```powershell
cd "c:\Users\1\vscode\projects\zorro\zbpm\zorrobpm-frontend"
npm ci            # reproducible install from package-lock.json
npm run dev       # Vite dev server on :5173, proxies /api -> http://localhost:8080 (dev only)
```

## Build (also the typecheck gate)

```powershell
cd "c:\Users\1\vscode\projects\zorro\zbpm\zorrobpm-frontend"
npm run build     # = `tsc && vite build` -> fails on any TS error; output in dist/
```

`npm run build` is the authoritative validation: a TypeScript error fails the build (and therefore CI and
the Docker image build). There is no separate test runner — do **not** add Playwright runs unless asked.

## Production / Nginx contract (must hold)

- **No hardcoded `localhost` / `127.0.0.1` / internal IPs in `src/`.** API base is the relative route
  `import.meta.env.VITE_API_URL || '/api'` (see `src/services/api.ts`).
- OIDC redirect falls back to `window.location.origin` when `VITE_KEYCLOAK_REDIRECT_URI` is empty
  (`src/stores/auth.ts`) — production must leave it empty so it resolves to `https://zorro.i-smet.kz`.
- `vite.config.ts` `server.proxy` (-> localhost:8080) is **dev-only**; it never affects the build.
- `.env` is the dev profile; `.env.production` is what Vite inlines at build time (relative `/api`, empty
  redirect). Keep secrets out — Vite inlines `VITE_*` into the bundle.

## Container

`zorrobpm-frontend/Dockerfile` is multi-stage: `node:22-alpine` (`npm ci && npm run build`) ->
`nginx:1.27-alpine` serving `dist/`. `nginx.conf` serves the SPA (`try_files ... /index.html`) and proxies
`location /api/` -> `http://app:8080/` (Docker DNS service name `app`, **never** a hardcoded IP), stripping
the `/api` prefix to match the backend's root-context endpoints.

Built and versioned by `docker-compose.yml` (`frontend` service, image `zorrobpm-frontend:${FRONTEND_TAG}`)
and the `ci-cd-deploy` pipeline.

## Key files

| Purpose | Path |
|---|---|
| Axios instance (relative baseURL, auth interceptor) | `src/services/api.ts` |
| OIDC / auth store | `src/stores/auth.ts` |
| Dev env / prod env | `.env` / `.env.production` |
| Vite config (alias `@`, dev proxy) | `vite.config.ts` |
| Container | `Dockerfile`, `nginx.conf`, `.dockerignore` |

## Stopping condition

`npm run build` is green, no hardcoded hosts in `src/`, container builds, served behind nginx via relative
`/api`.
