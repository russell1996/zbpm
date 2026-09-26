/**
 * WO-TEST-12 — сквозной E2E login → complete против РЕАЛЬНОГО backend.
 *
 * Что реально (не мок): backend — собранный из текущего дерева app-образ в
 * docker compose (postgres:16 + rabbitmq:4.1, prod-профиль); frontend —
 * собранный из текущего дерева бандл за nginx (`VITE_API_URL=/api`,
 * `/api/ → app:8080`); браузер — настоящий Chromium; сеть — настоящая
 * (nginx → app → PG/RabbitMQ). API используется ТОЛЬКО как setup
 * (deploy + start — сценарий пользователя начинается с логина); все
 * утверждения о пути пользователя — через UI-assertions в браузере.
 *
 * Структура сценария (один тест, шаги последовательные):
 *  1. UI-логин bootstrap-admin (+ обязательная смена пароля —
 *     forcePasswordChange у fresh bootstrap).
 *  2. API-setup: deploy фикстуры + старт инстанса #1 → user task #1.
 *  3. UI: /ui/tasks показывает задачу (fetch-путь).
 *  4. API-complete задачи #1 → строка ИСЧЕЗАЕТ БЕЗ перезагрузки
 *     (SSE `user-task.completed` → live-refetch; NEW-09/UI-18 класс).
 *  5. API-старт инстанса #2 → строка ПОЯВЛЯЕТСЯ БЕЗ перезагрузки
 *     (SSE `user-task.created`).
 *  6. UI-complete задачи #2 (клик по строке → карточка → кнопка) +
 *     API-подтверждение completedAt.
 *
 * POF-якорь (критерий 2): шаги 4–5 проходят ТОЛЬКО через
 * `addEventListener`-подписки `useRealtimeEvents.openSource` — убери их,
 * и оба шага краснеют таймаутом, а остальные остаются зелёными.
 */
import { test, expect, request } from 'playwright/test'
import { readFileSync } from 'fs'
import { join, dirname } from 'path'
import { fileURLToPath } from 'url'

const FRONTEND = process.env.E2E_FRONTEND_URL || 'http://localhost:58081'
const API = `${FRONTEND}/api`
// E2E-пароли ОБЯЗАНЫ отличаться от bootstrap-пароля стенда: стенд
// пересоздаётся, но rate-limit бакет login — персистентный в пределах
// запущенного app (capacity 5/min), а PBKDF2-600k дорогой — каждый лишний
// логин жрёт окно. Скрипт гарантирует свежий bootstrap (down -v), спек
// делает РОВНО ОДИН логин (этот) + одну смену — в окно укладываемся.
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'E2eAdminStr0ng!Pass'
const ADMIN_NEW_PASSWORD = process.env.E2E_ADMIN_NEW_PASSWORD || 'E2eNewStr0ng!Pass12'

const LIVE_TIMEOUT = 30_000

test('login → tasks → live SSE updates → complete', async ({ page }) => {
  // API-setup идёт под сессией страницы, но НЕ через page.request: в
  // playwright 1.62 page.request НЕ наследует cookie route-хендлерам/
  // sub-request'ам (проверено живьём: ctx.cookies() полон, а page.request
  // шлёт пустую Cookie → 403 на SUPER_ADMIN-guard). Поэтому — отдельный
  // API-контекст из storageState() страницы (те же httpOnly куки, честная
  // браузерная сессия, не второй логин и не мок).
  let api = page.request
  // --- 1. UI-логин -------------------------------------------------------
  await page.goto(`${FRONTEND}/ui/login`)
  await page.getByTestId('login-username').fill('admin')
  await page.getByTestId('login-password').fill(ADMIN_PASSWORD)
  await page.getByTestId('login-submit').click()

  // Fresh bootstrap-admin заперт forcePasswordChange → guard ведёт сюда.
  await expect(page).toHaveURL(/change-password/, { timeout: 15_000 })
  await page.getByTestId('change-current').fill(ADMIN_PASSWORD)
  await page.getByTestId('change-new').fill(ADMIN_NEW_PASSWORD)
  await page.getByTestId('change-confirm').fill(ADMIN_NEW_PASSWORD)
  await page.getByTestId('change-submit').click()

  // Смена ПРИНЯТА, но сессия за ней не переживает: changeOwnPassword бампит
  // tokenVersion (SEC-63 — смена пароля инвалидирует все access-токены) и
  // отзывает refresh-токены → refreshUser() в ChangePassword.vue 401-ит →
  // guard уводит с dashboard обратно на login. Это РЕАЛЬНЫЙ путь
  // пользователя (не баг спека): после смены — перелогиниться новым паролем.
  await expect(page).toHaveURL(/\/ui\/(login|$)/, { timeout: 15_000 })
  if (/login/.test(page.url())) {
    await page.getByTestId('login-username').fill('admin')
    await page.getByTestId('login-password').fill(ADMIN_NEW_PASSWORD)
    await page.getByTestId('login-submit').click()
  }

  // Второй логин (новым паролем) → dashboard, forcePasswordChange снят.
  await expect(page).toHaveURL(/\/ui\/$/, { timeout: 15_000 })
  await expect(page.locator('h1')).toBeVisible({ timeout: 15_000 })

  // Сессия страницы → API-контекст (см. комментарий выше).
  api = await request.newContext({
    storageState: await page.context().storageState(),
    // API-контекст обязан слать Origin (CORS-гейт SEC-44 за nginx режет
    // запросы без Origin 403 — живой факт, не теория: без Origin deploy
    // 403-ит даже под SUPER_ADMIN-сессией, с Origin — идёт в прод-логику).
    extraHTTPHeaders: { Origin: FRONTEND },
  })
  const meProbe = await api.get(`${API}/auth/me`)
  expect(meProbe.status(), 'API-контекст несёт сессию страницы').toBe(200)

  // --- 2. API-setup: deploy + старт #1 ------------------------------------
  const bpmn = readFileSync(
    join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'simple-user-task.bpmn'),
    'utf-8',
  )
  const deployRes = await api.post(`${API}/process-definitions`, { data: { bpmn } })
  expect(deployRes.status(), 'deploy фикстуры (реальный backend)').toBe(201)
  const definition = await deployRes.json()
  expect(definition.id, 'deploy возвращает id').toBeTruthy()

  const startRes = await api.post(`${API}/process-instances`, {
    data: { processDefinitionId: definition.id },
  })
  expect(startRes.status(), 'старт инстанса #1 (реальный backend)').toBe(201)
  const instance1 = await startRes.json()

  const tasksRes = await api.get(`${API}/user-tasks?pageIndex=0&pageSize=10`)
  expect(tasksRes.status(), 'список user tasks (реальный backend)').toBe(200)
  const tasks = await tasksRes.json()
  const task1 = (tasks.data as Array<{ id: string; processInstanceId: string }>).find(
    (t) => t.processInstanceId === instance1.id,
  )
  expect(task1, 'старт породил user task #1').toBeTruthy()

  // --- 3. UI: задача видна (fetch-путь) ------------------------------------
  await page.goto(`${FRONTEND}/ui/tasks`)
  const row1 = page.getByTestId(`task-row-${task1!.id}`)
  await expect(row1, 'задача #1 в списке (fetch)').toBeVisible({ timeout: 15_000 })

  // --- 4. API-complete #1 → строка исчезает БЕЗ перезагрузки (SSE) ---------
  const completeRes = await api.post(`${API}/user-tasks/${task1!.id}/complete`, {
    data: { variables: [] },
  })
  expect(completeRes.status(), 'complete #1 через API (реальный backend)').toBe(200)
  // Без SSE строка осталась бы до F5: realtime `user-task.completed`
  // триггерит refetch стора, и completed-задача выпадает из активного списка.
  await expect(row1, 'задача #1 исчезла live, без перезагрузки').toBeHidden({
    timeout: LIVE_TIMEOUT,
  })

  // --- 5. API-старт #2 → строка появляется БЕЗ перезагрузки (SSE) ----------
  const startRes2 = await api.post(`${API}/process-instances`, {
    data: { processDefinitionId: definition.id },
  })
  expect(startRes2.status(), 'старт инстанса #2 (реальный backend)').toBe(201)
  const instance2 = await startRes2.json()
  const tasksRes2 = await api.get(`${API}/user-tasks?pageIndex=0&pageSize=10`)
  const task2 = ((await tasksRes2.json()).data as Array<{
    id: string
    processInstanceId: string
  }>).find((t) => t.processInstanceId === instance2.id)
  expect(task2, 'старт #2 породил user task #2').toBeTruthy()
  // Без SSE строка появилась бы только после F5: realtime
  // `user-task.created` триггерит refetch стора.
  const row2 = page.getByTestId(`task-row-${task2!.id}`)
  await expect(row2, 'задача #2 появилась live, без перезагрузки').toBeVisible({
    timeout: LIVE_TIMEOUT,
  })

  // --- 6. UI-complete #2: строка → карточка → кнопка ------------------------
  await row2.click()
  await expect(page).toHaveURL(new RegExp(`/ui/tasks/${task2!.id}`), { timeout: 15_000 })
  await page.getByTestId('task-complete-btn').click()
  // useToast шлёт литерал (не i18n) — стабильный маркер успеха UI-пути.
  await expect(page.getByText('Task completed'), 'тост UI-complete').toBeVisible({
    timeout: 15_000,
  })

  // API-подтверждение: задача действительно завершена в backend.
  const finalRes = await api.get(`${API}/user-tasks/${task2!.id}`)
  expect(finalRes.status(), 'карточка задачи #2 (реальный backend)').toBe(200)
  expect((await finalRes.json()).completedAt, 'completedAt выставлен').toBeTruthy()
})
