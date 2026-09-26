import { defineConfig } from 'playwright/test'

// WO-TEST-12: сквозной E2E против РЕАЛЬНОГО backend (docker compose стенд,
// тот же профиль, что PG/Rabbit IT). Запуск — ТОЛЬКО через
// ci/e2e-login-complete.sh (поднимает стек, ждёт healthy, гоняет спек,
// роняет стек). Напрямую (`npx playwright test`) — только когда стек уже
// поднят вручную, base — E2E_FRONTEND_URL.
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  // Один воркер: сценарий последовательный (логин → задачи → complete),
  // параллельность здесь — только гонка за shared backend.
  workers: 1,
  // Outbox-поллер (2с) + SSE-доставка + сборки: generous, но конечный.
  timeout: 180_000,
  retries: 0,
  use: {
    baseURL: process.env.E2E_FRONTEND_URL || 'http://localhost:58081',
    launchOptions: { args: ['--no-sandbox'] },
  },
  reporter: [['list']],
})
