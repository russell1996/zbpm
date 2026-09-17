# syntax=docker/dockerfile:1

# --- Build stage: compile the Vue SPA ---
# Debian (bookworm-slim), not alpine: `playwright install --with-deps` has no
# alpine support (it would install ubuntu deps via apt). Only the builder grows;
# the runtime stage below stays nginx:alpine.
FROM node:22-bookworm-slim@sha256:d649c27dae7ba0137b3cef5dd75baa422c08dc3d9e3fc0c23dfb172dc3cc6436 AS builder

WORKDIR /build

# Install dependencies from the lockfile (reproducible) before copying sources for better layer caching
COPY package.json package-lock.json ./
RUN npm ci

# WO-TEST-6: the browser-geometry suite (vitest browser mode) needs a real
# Chromium. --with-deps pulls the alpine packages the browser requires; the
# builder runs as root, so the launch args add --no-sandbox (vitest.browser.config.ts).
RUN npx playwright install --with-deps chromium

COPY . .

# WO-QW-1 F34: standalone topology support. Default build targets the external
# prod proxy (VITE_API_URL=/ — domain root goes straight to the backend, no
# "/api" location there). For a standalone `docker compose up` (frontend nginx
# proxies /api/ → app:8080), build with --build-arg VITE_API_URL=/api.
ARG VITE_API_URL=/
ENV VITE_API_URL=${VITE_API_URL}

# Vite picks up .env.production automatically in production mode (relative /api, origin-based redirect)
RUN npm run build

# WO-OPS-4a (C-01): frontend tests never ran in CI — build the same way locally and
# in the pipeline, but make a failing test fail the builder stage (and therefore CI).
# `--run` is mandatory: plain `vitest` would enter watch mode and hang until timeout.
RUN npm test -- --run

# WO-TEST-6: the browser-geometry checks run in the same builder stage, so a
# regression in the six visual criteria fails CI exactly like any other test.
RUN npm run test:browser


# --- Runtime stage: serve static assets + reverse-proxy /api to the backend ---
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10

# SPA fallback + /api proxy to the backend container (resolved by Docker DNS, not a hardcoded IP)
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY snippets /etc/nginx/snippets
COPY --from=builder /build/dist /usr/share/nginx/html

# Fail the image build (in CI) if the nginx config is syntactically invalid
RUN nginx -t

EXPOSE 80
ENV TZ=Asia/Almaty

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD wget -qO- http://127.0.0.1/healthz || exit 1
