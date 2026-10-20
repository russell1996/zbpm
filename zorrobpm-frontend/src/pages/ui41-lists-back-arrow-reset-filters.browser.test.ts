/**
 * WO-UI-41 — реальный браузер (Chromium через Playwright, `npm run test:browser`).
 *
 * Жалоба владельца (2026-10-21): «фильтры всё равно не фиксируются, плюс где
 * стирание выбранных фильтров».
 *
 * Сценарии (реальные страницы приложения, реальный роутер, настоящий
 * query-механизм `useListState` из WO-UI-39; замокано только СЕТЬ):
 *  1) список инстансов → выставить фильтр → открыть карточку кликом →
 *     СТРЕЛКА «назад» в приложении → фильтр НА МЕСТЕ;
 *  2) то же для списка задач;
 *  3) «Назад» БРАУЗЕРА (не стрелка приложения) → фильтр на месте (регресс
 *     WO-UI-39 — не должен сломаться);
 *  4) кнопка «Сбросить фильтры»: появляется, когда состояние не дефолтное,
 *     клик возвращает дефолты и прячет кнопку.
 *
 * Роутер — `createWebHistory` (как в проде): только у web-истории есть
 * `state.back` (предыдущая запись), и стрелка «назад» идёт по ИСТОРИИ.
 * В юнит-тестах (`createMemoryHistory`) этой записи нет — там стрелка
 * работает по «push»-ветке, поэтому браузерный сценарий — единственный,
 * который проверяет боевой путь.
 *
 * jsdom не умеет ни кликать по portal-меню reka-Select, ни мерить геометрию —
 * здесь нужен настоящий Chromium.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { page } from 'vitest/browser'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createWebHistory, RouterView, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import ProcessInstanceList from '@/pages/processes/ProcessInstanceList.vue'
import ProcessInstanceDetail from '@/pages/processes/ProcessInstanceDetail.vue'
import TaskList from '@/pages/tasks/TaskList.vue'
import TaskDetail from '@/pages/tasks/TaskDetail.vue'
import ServiceTaskList from '@/pages/tasks/ServiceTaskList.vue'
import ServiceTaskDetail from '@/pages/tasks/ServiceTaskDetail.vue'
import IncidentList from '@/pages/incidents/IncidentList.vue'
import IncidentDetail from '@/pages/incidents/IncidentDetail.vue'
import ProcessDefinitionList from '@/pages/processes/ProcessDefinitionList.vue'
import ProcessDefinitionDetail from '@/pages/processes/ProcessDefinitionDetail.vue'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'
import '@/style.css'

const { mockInstance, mockTask, mockServiceTask, mockIncident, mockDefinition } = vi.hoisted(() => ({
  mockInstance: {
    id: 'pi-1',
    processDefinitionId: 'pd-1',
    processName: 'Vacation Leave',
    processKey: 'vacation',
    processVersion: 1,
    startedAt: '2026-01-01T00:00:00Z',
    completedAt: null,
    cancelled: false,
  },
  mockTask: {
    id: 'task-1',
    name: 'Approve vacation',
    code: 'approve',
    processInstanceId: 'pi-1',
    completedAt: null,
    createdAt: '2026-01-01T00:00:00Z',
  },
  mockServiceTask: {
    id: 'st-1',
    name: 'Call ERP',
    code: 'call-erp',
    processInstanceId: 'pi-1',
    completedAt: null,
    createdAt: '2026-01-01T00:00:00Z',
  },
  mockIncident: {
    id: 'inc-1',
    activityId: 'act-1',
    message: 'Task failed',
    createdAt: '2026-01-01T00:00:00Z',
    completedAt: null,
    processName: 'Vacation Leave',
    processInstanceId: 'pi-1',
    bpmnElementId: 'Activity_1',
    elementName: 'Approve vacation',
  },
  mockDefinition: {
    id: 'pd-1',
    key: 'vacation',
    name: 'Vacation Leave',
    version: 1,
    sha256: 'a',
    createdAt: '2026-01-01T00:00:00Z',
    startFormKey: null,
  },
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u1', username: 'op' }, isSuperAdmin: true, isAuthenticated: true }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))
vi.mock('@/services/adminService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/adminService')>()),
  listMembers: vi.fn().mockResolvedValue([]),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
  getMyMemberships: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/instanceService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/instanceService')>()),
  getProcessInstances: vi.fn().mockResolvedValue({
    data: [mockInstance], totalElements: 1, pageIndex: 0, pageSize: 20,
  }),
  getProcessInstance: vi.fn().mockResolvedValue(mockInstance),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({
    data: [], totalElements: 0, pageIndex: 0, pageSize: 100,
  }),
  startProcessInstance: vi.fn(),
  cancelProcessInstance: vi.fn(),
}))
// Мок через importOriginal: модуль отдаёт ещё и deployProcessDefinition,
// addProcessDefinitionVersion, archive/unarchive — их импортирует виджет
// загрузки схемы; без spread'а импорт падал бы «no export named ...».
vi.mock('@/services/processService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/processService')>()),
  getProcessDefinitions: vi.fn().mockResolvedValue({
    data: [mockDefinition],
    totalElements: 1, pageIndex: 0, pageSize: 200,
  }),
  getProcessDefinition: vi.fn().mockResolvedValue(mockDefinition),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
  getProcessDefinitionXml: vi.fn().mockResolvedValue(''),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue(null),
}))
vi.mock('@/services/taskService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/taskService')>()),
  getUserTasks: vi.fn().mockResolvedValue({
    data: [mockTask], totalElements: 1, pageIndex: 0, pageSize: 20,
  }),
  getUserTask: vi.fn().mockResolvedValue(mockTask),
  getServiceTasks: vi.fn().mockResolvedValue({
    data: [mockServiceTask], totalElements: 1, pageIndex: 0, pageSize: 20,
  }),
  getServiceTask: vi.fn().mockResolvedValue(mockServiceTask),
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn().mockResolvedValue({
    data: [mockIncident], totalElements: 1, pageIndex: 0, pageSize: 20,
  }),
  getIncident: vi.fn().mockResolvedValue(mockIncident),
  resolveIncident: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/services/variableService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/variableService')>()),
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
  getVariablesByScope: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/formService', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/formService')>()),
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
  getSchemaMap: vi.fn().mockResolvedValue({ processDefinitionKey: 'vacation', version: 1, elements: [] }),
}))

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en, ru, kz } })
}

async function makeRouter(initialUrl: string): Promise<Router> {
  const router = createRouter({
    history: createWebHistory(''),
    routes: [
      { path: '/processes/instances', name: 'process-instances', component: ProcessInstanceList },
      { path: '/processes/instances/:id', name: 'process-instance-detail', component: ProcessInstanceDetail },
      { path: '/processes/definitions', name: 'process-definitions', component: ProcessDefinitionList },
      { path: '/processes/definitions/:id', name: 'process-definition-detail', component: ProcessDefinitionDetail },
      { path: '/tasks', name: 'my-tasks', component: TaskList },
      { path: '/tasks/:id', name: 'task-detail', component: TaskDetail },
      { path: '/service-tasks', name: 'service-tasks', component: ServiceTaskList },
      { path: '/service-tasks/:id', name: 'service-task-detail', component: ServiceTaskDetail },
      { path: '/incidents', name: 'incidents', component: IncidentList },
      { path: '/incidents/:id', name: 'incident-detail', component: IncidentDetail },
    ],
  })
  await router.push(initialUrl)
  await router.isReady()
  return router
}

const AppHost = defineComponent({
  setup: () => () => h('div', { 'data-scroll-container': '', style: 'height:600px; overflow:auto' }, h(RouterView)),
})

let mounted: VueWrapper[] = []
let hosts: HTMLElement[] = []

function mountHost(): HTMLElement {
  const host = document.createElement('div')
  host.style.position = 'absolute'
  host.style.top = '0'
  host.style.left = '0'
  host.style.width = '1280px'
  host.style.height = '720px'
  document.body.appendChild(host)
  hosts.push(host)
  return host
}

async function mountApp(router: Router): Promise<void> {
  const wrapper = mount(AppHost, {
    attachTo: mountHost(),
    global: { plugins: [createPinia(), makeI18n(), router] },
  })
  mounted.push(wrapper)
  await flushPromises()
}

async function until(cond: () => boolean, timeout = 15000, what = 'condition'): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const start = Date.now()
    const tick = () => {
      try {
        if (cond()) return resolve()
      } catch {
        /* DOM ещё не готов */
      }
      if (Date.now() - start > timeout) return reject(new Error(`timeout waiting for ${what}`))
      requestAnimationFrame(tick)
    }
    tick()
  })
}

/** Дождаться появления элемента и вернуть его. */
async function untilEl(selector: string, timeout = 15000, what = selector): Promise<HTMLElement> {
  let found: HTMLElement | null = null
  await until(() => {
    found = document.querySelector<HTMLElement>(selector)
    return !!found
  }, timeout, what)
  return found!
}

beforeEach(() => {
  setActivePinia(createPinia())
  sessionStorage.clear()
  vi.clearAllMocks()
})

afterEach(() => {
  for (const w of mounted) {
    try { w.unmount() } catch { /* уже размонтирован */ }
  }
  mounted = []
  for (const h of hosts) h.remove()
  hosts = []
  document.body.innerHTML = ''
})

/** Стрелка «назад» в шапке карточки: ищем по testid, иначе — по ссылке на список. */
function backArrow(listPath: string): HTMLAnchorElement {
  const el =
    document.querySelector<HTMLAnchorElement>('[data-testid="back-to-list"]') ??
    document.querySelector<HTMLAnchorElement>(`a[href^="${listPath}"]`)
  if (!el) throw new Error(`back arrow to ${listPath} not found`)
  return el
}

/** Позиция в браузерной истории (vue-router считает её в state.position). */
function historyPosition(router: Router): number {
  const state = router.options.history.state as { position?: number } | undefined
  return state?.position ?? -1
}

describe('WO-UI-41: стрелка «назад» сохраняет фильтры списка (реальный браузер)', () => {
  it('инстансы: фильтр → карточка → стрелка «назад» → фильтр на месте', async () => {
    const router = await makeRouter('/processes/instances')
    await mountApp(router)

    // Список отрисован, строка инстанса на месте.
    await until(() => !!document.querySelector('tbody tr'), 15000, 'список инстансов')
    expect(router.currentRoute.value.query['instances.key']).toBeUndefined()

    // Фильтр по процессу — настоящий dropdown (reka Select в portal).
    const trigger = document.querySelector<HTMLElement>('[data-testid="definition-filter"]')
    expect(trigger, 'фильтр по процессу отрисован').toBeTruthy()
    await page.elementLocator(trigger!).click()
    const option = await untilEl('[data-testid="definition-filter-vacation"]', 15000, 'опция фильтра')
    await page.elementLocator(option).click()
    await until(() => router.currentRoute.value.query['instances.key'] === 'vacation', 5000, 'фильтр в URL')
    expect(router.currentRoute.value.query['instances.key']).toBe('vacation')

    // Карточка открыта кликом по строке.
    const row = document.querySelector<HTMLElement>('tbody tr')
    await page.elementLocator(row!).click()
    await until(() => router.currentRoute.value.path === '/processes/instances/pi-1', 10000, 'карточка инстанса')
    await until(() => !!backArrow('/processes/instances'), 10000, 'стрелка «назад» в карточке')

    // Стрелка идёт ПО ИСТОРИИ (не «push»): позиция в истории возвращается
    // назад, а не растёт — так возвращает ровно туда, откуда пришёл.
    const positionOnCard = historyPosition(router)

    // СТРЕЛКА «назад» в приложении — фильтр обязан остаться.
    await page.elementLocator(backArrow('/processes/instances')).click()
    await until(() => router.currentRoute.value.path === '/processes/instances', 10000, 'возврат к списку')
    expect(
      router.currentRoute.value.query['instances.key'],
      'фильтр списка сохранился после стрелки «назад»',
    ).toBe('vacation')
    expect(
      historyPosition(router),
      'стрелка вернула по ИСТОРИИ (позиция не выросла)',
    ).toBeLessThan(positionOnCard)
  })

  it('задачи: фильтр → карточка → стрелка «назад» → фильтр на месте', async () => {
    const router = await makeRouter('/tasks')
    await mountApp(router)
    await until(() => !!document.querySelector('tbody tr'), 15000, 'список задач')

    // Фильтр «показать завершённые» — нативный checkbox в строке фильтров.
    const label = [...document.querySelectorAll('label')].find((l) =>
      (l.textContent ?? '').includes('Show completed'),
    )
    const checkbox = label?.querySelector<HTMLInputElement>('input[type="checkbox"]')
    expect(checkbox, 'фильтр «показать завершённые» найден').toBeTruthy()
    checkbox!.click()
    await until(() => router.currentRoute.value.query['tasks.completed'] === '1', 5000, 'фильтр задач в URL')
    expect(router.currentRoute.value.query['tasks.completed']).toBe('1')

    const row = document.querySelector<HTMLElement>('tbody tr')
    await page.elementLocator(row!).click()
    await until(() => router.currentRoute.value.path === '/tasks/task-1', 10000, 'карточка задачи')
    await until(() => !!backArrow('/tasks'), 10000, 'стрелка «назад» в карточке')

    await page.elementLocator(backArrow('/tasks')).click()
    await until(() => router.currentRoute.value.path === '/tasks', 10000, 'возврат к списку задач')
    expect(
      router.currentRoute.value.query['tasks.completed'],
      'фильтр списка задач сохранился после стрелки «назад»',
    ).toBe('1')
  })

  it('«Назад» браузера тоже возвращает список с фильтром (регресс WO-UI-39)', async () => {
    const router = await makeRouter('/tasks')
    await mountApp(router)
    await until(() => !!document.querySelector('tbody tr'), 15000, 'список задач')

    const label = [...document.querySelectorAll('label')].find((l) =>
      (l.textContent ?? '').includes('Show completed'),
    )
    label?.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await until(() => router.currentRoute.value.query['tasks.completed'] === '1', 5000, 'фильтр задач в URL')

    const row = document.querySelector<HTMLElement>('tbody tr')
    await page.elementLocator(row!).click()
    await until(() => router.currentRoute.value.path === '/tasks/task-1', 10000, 'карточка задачи')

    router.back()
    await until(() => router.currentRoute.value.path === '/tasks', 10000, '«Назад» браузера')
    expect(router.currentRoute.value.query['tasks.completed']).toBe('1')
  })

  it('карточка, открытая по прямой ссылке: стрелка ведёт на список с сохранёнными фильтрами', async () => {
    // Пользователь раньше был в списке (фильтр сохранён), потом открыл карточку
    // по прямой ссылке — истории «список → карточка» нет, стрелка обязана
    // привести на список С ФИЛЬТРАМИ (а не выкинуть из приложения).
    const first = await makeRouter('/tasks')
    await mountApp(first)
    await until(() => !!document.querySelector('tbody tr'), 15000, 'список задач')
    const label = [...document.querySelectorAll('label')].find((l) =>
      (l.textContent ?? '').includes('Show completed'),
    )
    label?.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await until(() => first.currentRoute.value.query['tasks.completed'] === '1', 5000, 'фильтр задач в URL')

    // Новый «сеанс» в той же вкладке: прямой адрес карточки, истории списка нет.
    for (const w of mounted) w.unmount()
    mounted = []
    for (const h of hosts) h.remove()
    hosts = []
    document.body.innerHTML = ''
    const direct = await makeRouter('/tasks/task-1')
    await mountApp(direct)
    await until(() => !!backArrow('/tasks'), 10000, 'стрелка «назад» в карточке')

    await page.elementLocator(backArrow('/tasks')).click()
    await until(() => direct.currentRoute.value.path === '/tasks', 10000, 'возврат к списку')
    expect(
      direct.currentRoute.value.query['tasks.completed'],
      'стрелка восстановила сохранённый фильтр списка',
    ).toBe('1')
  })

  it('кнопка «Сбросить фильтры»: появляется, чистит фильтр и прячется', async () => {
    const router = await makeRouter('/tasks')
    await mountApp(router)
    await until(() => !!document.querySelector('tbody tr'), 15000, 'список задач')

    // В дефолтном состоянии кнопки нет.
    expect(document.querySelector('[data-testid="reset-filters"]'), 'в дефолтном виде кнопки нет').toBeNull()

    const label = [...document.querySelectorAll('label')].find((l) =>
      (l.textContent ?? '').includes('Show completed'),
    )
    label?.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await until(() => router.currentRoute.value.query['tasks.completed'] === '1', 5000, 'фильтр задач в URL')
    await until(() => !!document.querySelector('[data-testid="reset-filters"]'), 5000, 'кнопка сброса появилась')

    await page.elementLocator(document.querySelector<HTMLElement>('[data-testid="reset-filters"]')!).click()
    await until(() => router.currentRoute.value.query['tasks.completed'] === undefined, 5000, 'фильтр сброшен')
    expect(router.currentRoute.value.query['tasks.completed']).toBeUndefined()
    await until(() => !document.querySelector('[data-testid="reset-filters"]'), 5000, 'кнопка сброса спряталась')
    expect(document.querySelector('[data-testid="reset-filters"]')).toBeNull()
  })

  /**
   * Остальные пары «список ↔ карточка» — тот же контракт стрелки.
   */
  it.each([
    ['сервисные задачи', '/service-tasks', '/service-tasks', 'serviceTasks.completed', 'Show completed'],
    ['инциденты', '/incidents', '/incidents', 'incidents.resolved', 'Show completed'],
  ] as const)('%s: фильтр → карточка → стрелка «назад» → фильтр на месте', async (
    _label, listPath, listHrefPrefix, queryParam, checkboxLabel,
  ) => {
    const router = await makeRouter(listPath)
    await mountApp(router)
    await until(() => !!document.querySelector('tbody tr'), 15000, `список ${listPath}`)

    const label = [...document.querySelectorAll('label')].find((l) =>
      (l.textContent ?? '').includes(checkboxLabel),
    )
    const checkbox = label?.querySelector<HTMLInputElement>('input[type="checkbox"]')
    expect(checkbox, `фильтр списка ${listPath} найден`).toBeTruthy()
    checkbox!.click()
    await until(() => router.currentRoute.value.query[queryParam] !== undefined, 5000, `фильтр ${queryParam}`)
    expect(router.currentRoute.value.query[queryParam]).toBe('1')

    const row = document.querySelector<HTMLElement>('tbody tr')
    await page.elementLocator(row!).click()
    await until(() => router.currentRoute.value.path !== listPath, 10000, 'карточка открылась')
    await until(() => !!backArrow(listHrefPrefix), 10000, 'стрелка «назад» в карточке')

    await page.elementLocator(backArrow(listHrefPrefix)).click()
    await until(() => router.currentRoute.value.path === listPath, 10000, 'возврат к списку')
    expect(
      router.currentRoute.value.query[queryParam],
      `фильтр списка ${listPath} сохранился после стрелки «назад»`,
    ).toBe('1')
  })

  it('схемы процессов: поиск → карточка → стрелка «назад» → поиск на месте', async () => {
    const router = await makeRouter('/processes/definitions')
    await mountApp(router)
    await until(() => !!document.querySelector('tbody tr'), 15000, 'список схем процессов')

    // Поиск — текстовое поле в строке фильтров.
    const search = document.querySelector<HTMLInputElement>('input[type="text"]')
    expect(search, 'поле поиска найдено').toBeTruthy()
    await page.elementLocator(search!).fill('vacation')
    await until(() => router.currentRoute.value.query['definitions.search'] === 'vacation', 5000, 'поиск в URL')
    expect(router.currentRoute.value.query['definitions.search']).toBe('vacation')

    const row = document.querySelector<HTMLElement>('tbody tr')
    await page.elementLocator(row!).click()
    await until(() => router.currentRoute.value.path === '/processes/definitions/pd-1', 10000, 'карточка схемы')
    await until(() => !!backArrow('/processes/definitions'), 10000, 'стрелка «назад» в карточке')

    await page.elementLocator(backArrow('/processes/definitions')).click()
    await until(() => router.currentRoute.value.path === '/processes/definitions', 10000, 'возврат к списку')
    expect(
      router.currentRoute.value.query['definitions.search'],
      'поиск по списку схем сохранился после стрелки «назад»',
    ).toBe('vacation')
  })
})
