# syntax=docker/dockerfile:1

# --- Build stage: compile the Vue SPA ---
FROM node:22-alpine AS builder

WORKDIR /build

# Install dependencies from the lockfile (reproducible) before copying sources for better layer caching
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite picks up .env.production automatically in production mode (relative /api, origin-based redirect)
RUN npm run build


# --- Runtime stage: serve static assets + reverse-proxy /api to the backend ---
FROM nginx:1.27-alpine

# SPA fallback + /api proxy to the backend container (resolved by Docker DNS, not a hardcoded IP)
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD wget -qO- http://localhost/healthz || exit 1
