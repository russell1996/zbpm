// @vitest-environment jsdom
/**
 * WO-UI-32 — страница инстанса: переменные обновляются при смене состояния
 * и при открытии вкладки.
 *
 * Было (корень по коду, ProcessInstanceDetail.vue):
 *  1. onTabChange: для variables запрос только если currentVariables.length === 0
 *     (hasData) — один раз загрузили, дальше вкладка показывает кэш;
 *  2. handleLiveEvent: ни одно событие не освежает переменные — значения на
 *     открытой вкладке «Переменные» устаревают после завершения задачи/активности.
 *
 * Стало:
 *  (а) открытие вкладки variables (и tasks/serviceTasks/incidents) ВСЕГДА тянет
 *      СВОЙ источник тихо (1 запрос на клик, остальные источники не трогаются,
 *      без loading-мигания); повторное открытие тянет снова (hasData-кэша нет);
 *  (б) burst N событий activity.completed/user-task.completed/incident.resolved
 *      при активной вкладке variables → 1 склеенный getVariables (счёт вызовов,
 *      не wall-clock), новое значение в DOM, loading 0;
 *  (в) событие при НЕАКТИВНОЙ вкладке variables и без данных → 0 запросов
 *      (не грузить лишнего);
 *  (г) process-instance.completed при активной вкладке → переменные перечитаны,
 *      итоговое значение в DOM (без шторма: ≤2 запроса на терминальное событие).
 *
 * Мутации (см. отчёт): вернуть hasData-кэш в onTabChange → (а) RED;
 * убрать ветку variables в handleLiveEvent → (б) RED.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { watch } from 'vue'
import ProcessInstanceDetail from './ProcessInstanceDetail.vue'
import { publishRealtimeEvent, resetRealtimeBusForTest } from '@/services/realtimeBus'
import { resetRealtimeSchedulerForTest } from '@/services/realtimeScheduler'
import { useProcessStore } from '@/stores/process'
import { useTaskStore } from '@/stores/task'
import { useIncidentStore } from '@/stores/incident'
import type { EventEnvelope, ProcessVariable } from '@/types/api'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' }, path: '/instances/pi-1', query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn().mockResolvedValue(undefined) }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u1', username: 'op' }, isSuperAdmin: true, myMembership: null }),
}))

vi.mock('@/stores/breadcrumb', () => ({
  useBreadcrumbStore: () => ({ setCrumbLabel: vi.fn() }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
}))

vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))

vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
}))

const mockGetProcessInstance = vi.hoisted(() => vi.fn())
const mockGetActivitiesPaged = vi.hoisted(() => vi.fn())
const mockGetProcessInstances = vi.hoisted(() => vi.fn())
const mockGetUserTasks = vi.hoisted(() => vi.fn())
const mockGetServiceTasks = vi.hoisted(() => vi.fn())
const mockGetIncidents = vi.hoisted(() => vi.fn())
const mockGetVariables = vi.hoisted(() => vi.fn())

vi.mock('@/services/processService', () => ({
  getProcessDefinitionXml: vi.fn().mockResolvedValue('<definitions id="test" />'),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue({
    id: 'pd-1', key: 'test', version: 1, name: 'Test', documentation: null, nodes: [], flows: [],
  }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 10 }),
}))

vi.mock('@/services/instanceService', () => ({
  getProcessInstance: mockGetProcessInstance,
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: mockGetActivitiesPaged,
  getProcessInstances: mockGetProcessInstances,
  startProcessInstance: vi.fn(),
  cancelProcessInstance: vi.fn(),
}))

vi.mock('@/services/taskService', () => ({
  getUserTasks: mockGetUserTasks,
  getUserTask: vi.fn().mockResolvedValue(null),
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: mockGetServiceTasks,
  getServiceTask: vi.fn().mockResolvedValue(null),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
  failServiceTask: vi.fn().mockResolvedValue(undefined),
  throwServiceTaskError: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/services/incidentService', () => ({
  getIncidents: mockGetIncidents,
  getIncident: vi.fn().mockResolvedValue(null),
  resolveIncident: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: mockGetVariables,
}))

vi.mock('@/services/formService', () => ({
  getTaskForm: vi.fn().mockResolvedValue(null),
  getStartForm: vi.fn().mockResolvedValue(null),
}))

const stubs = { teleport: true, BpmnViewer: { template: '<div class="bpmn-stub" />' } }

function envelope(partial: Partial<EventEnvelope> & { type: string }): EventEnvelope {
  return {
    sequence: 1,
    id: `e-${Math.random()}`,
    version: 1,
    occurredAt: '2026-10-14T00:00:00Z',
    processInstanceId: 'pi-1',
    data: {},
    ...partial,
  } as EventEnvelope
}

const piRow = {
  id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1',
  processName: 'Test', processKey: 'test', processVersion: 1,
  startedAt: '2026-01-01', completedAt: null, cancelled: false,
}

function activityRow(id: string, elementId: string, status: string) {
  return { id, bpmnElementId: elementId, type: 'userTask', status, createdAt: '2026-10-14', completedAt: null }
}

function varRow(value: string): ProcessVariable {
  return { name: 'answer', type: 'STRING', value }
}

/** Снимок числа вызовов всех источников для сравнения после действия. */
function snapshot() {
  return {
    instance: mockGetProcessInstance.mock.calls.length,
    activities: mockGetActivitiesPaged.mock.calls.length,
    userTasks: mockGetUserTasks.mock.calls.length,
    serviceTasks: mockGetServiceTasks.mock.calls.length,
    incidents: mockGetIncidents.mock.calls.length,
    variables: mockGetVariables.mock.calls.length,
  }
}

function delta(before: ReturnType<typeof snapshot>) {
  return {
    instance: mockGetProcessInstance.mock.calls.length - before.instance,
    activities: mockGetActivitiesPaged.mock.calls.length - before.activities,
    userTasks: mockGetUserTasks.mock.calls.length - before.userTasks,
    serviceTasks: mockGetServiceTasks.mock.calls.length - before.serviceTasks,
    incidents: mockGetIncidents.mock.calls.length - before.incidents,
    variables: mockGetVariables.mock.calls.length - before.variables,
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

describe('ProcessInstanceDetail: переменные при открытии вкладки и живых событиях (WO-UI-32)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    resetRealtimeBusForTest()
    resetRealtimeSchedulerForTest()
    mockGetProcessInstance.mockResolvedValue({ ...piRow })
    mockGetActivitiesPaged.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetProcessInstances.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetUserTasks.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetServiceTasks.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetIncidents.mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 })
    mockGetVariables.mockResolvedValue({ data: [] })
  })

  // Счётчики запросов глобальные на файл: упавший тест обязан оставлять
  // ПОЛЕ сдёрнутым (иначе живой компонент продолжает отвечать на события
  // следующих тестов и мутает их счётчики). unmount — в afterEach, а не
  // в хвосте теста: падение ассерта не должно пропускать сдёрку.
  let activeWrapper: ReturnType<typeof mount> | null = null

  afterEach(() => {
    activeWrapper?.unmount()
    activeWrapper = null
    resetRealtimeBusForTest()
    resetRealtimeSchedulerForTest()
  })

  async function mountPage(initialVariables: ProcessVariable[]) {
    mockGetVariables.mockResolvedValue({ data: initialVariables })
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    activeWrapper = wrapper
    await flushPromises()
    await sleep(100)
    await flushPromises()
    return wrapper
  }

  /** Клик по настоящей кнопке вкладки (TabsBar → selectTab → watch → onTabChange). */
  async function clickTab(wrapper: ReturnType<typeof mount>, id: string): Promise<void> {
    const tab = wrapper.findAll('button[role="tab"]').find((b) => b.text() === id)
    expect(tab, `tab button "${id}" exists`).toBeTruthy()
    await tab!.trigger('click')
    await flushPromises()
    await sleep(50)
    await flushPromises()
  }

  it('(а) открытие вкладки variables тянет getVariables и обновляет DOM; повторное открытие тянет снова; другие источники — 0', async () => {
    const wrapper = await mountPage([varRow('old-42')])
    const processStore = useProcessStore()
    expect(processStore.currentVariables.map((v) => v.value)).toEqual(['old-42'])

    // «Сервер» вернул свежие значения — страница ещё этого не знает.
    mockGetVariables.mockResolvedValue({ data: [varRow('new-42')] })

    const before = snapshot()
    await clickTab(wrapper, 'variables')
    const d1 = delta(before)
    // Один клик — ровно ОДИН запрос СВОЕГО источника (не два fetch, не loadTabData на всё).
    expect(d1.variables).toBe(1)
    expect(d1.instance).toBe(0)
    expect(d1.activities).toBe(0)
    expect(d1.userTasks).toBe(0)
    expect(d1.serviceTasks).toBe(0)
    expect(d1.incidents).toBe(0)
    // DOM обновлён поверх старых данных (тихо, без перезагрузки страницы).
    expect(wrapper.text()).toContain('new-42')
    expect(wrapper.text()).not.toContain('old-42')

    // Тот же дефект у соседних вкладок — одна правка: каждая тянет свой источник.
    mockGetUserTasks.mockResolvedValue({ data: [{ id: 't-1', code: 'task1', name: 'T1', status: 'CREATED' }], totalElements: 1, pageIndex: 0, pageSize: 100 })
    const before2 = snapshot()
    await clickTab(wrapper, 'tasks')
    const d2 = delta(before2)
    expect(d2.userTasks).toBe(1)
    expect(d2.variables).toBe(0)

    mockGetServiceTasks.mockResolvedValue({ data: [{ id: 's-1', code: 'svc1', name: 'S1', status: 'CREATED', job: 'job1' }], totalElements: 1, pageIndex: 0, pageSize: 100 })
    const before3 = snapshot()
    await clickTab(wrapper, 'serviceTasks')
    const d3 = delta(before3)
    expect(d3.serviceTasks).toBe(1)
    expect(d3.variables).toBe(0)

    mockGetIncidents.mockResolvedValue({ data: [{ id: 'i-1', message: 'boom', activityId: 'a-1', bpmnElementId: 'el-1', processInstanceId: 'pi-1' }], totalElements: 1, pageIndex: 0, pageSize: 100 })
    const before4 = snapshot()
    // Label вкладки — t('incidentsTab') (id 'incidents', t(k)=k в моке i18n).
    await clickTab(wrapper, 'incidentsTab')
    const d4 = delta(before4)
    expect(d4.incidents).toBe(1)
    expect(d4.variables).toBe(0)

    // ВОЗВРАТ на variables: hasData-кэш (данные уже были) НЕ должен блокировать
    // перечит — именно это и было дефектом владельца («при нажатии на раздел
    // переменные нужно заново дернуть апи»).
    mockGetVariables.mockResolvedValue({ data: [varRow('new-43')] })
    const before5 = snapshot()
    await clickTab(wrapper, 'variables')
    const d5 = delta(before5)
    expect(d5.variables).toBe(1)
    expect(wrapper.text()).toContain('new-43')
    expect(wrapper.text()).not.toContain('new-42')
  })

  it('(б) burst activity.completed + user-task.completed + incident.resolved при активной вкладке variables → 1 getVariables, новое значение в DOM, loading 0', async () => {
    const wrapper = await mountPage([varRow('v1')])
    // Вкладка активна, данные уже на экране (v1).
    mockGetVariables.mockResolvedValue({ data: [varRow('v2')] })
    await clickTab(wrapper, 'variables')
    expect(wrapper.text()).toContain('v2')

    const processStore = useProcessStore()
    const taskStore = useTaskStore()
    const incidentStore = useIncidentStore()
    let loadingCycles = 0
    const stops = [
      watch(() => processStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => taskStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => incidentStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
    ]

    const before = snapshot()
    // Шторм: N событий подряд (разные id/sequence, неизвестные сущности).
    const N = 5
    for (let i = 0; i < N; i++) {
      const type = i < 3 ? 'activity.completed' : i === 3 ? 'user-task.completed' : 'incident.resolved'
      publishRealtimeEvent(envelope({
        type,
        sequence: 300 + i,
        id: `e-ui32-${i}`,
        elementId: i < 3 ? `el-unknown-${i}` : undefined,
        data: i === 3 ? { activityId: 't-unknown' } : i === 4 ? { incidentId: 'inc-unknown' } : {},
      }))
    }
    // > 500 мс дебаунса планировщика; < 4 с фолбэк-опроса (не участвует).
    await sleep(900)
    await flushPromises()

    const d = delta(before)
    // N событий → ОДИН склеенный запрос переменных (счёт вызовов, не мс).
    expect(d.variables).toBe(1)
    // DOM держит свежее значение.
    expect(wrapper.text()).toContain('v2')
    // Ни мерцания, ни шторма на остальных источниках (по ≤1 склеенному).
    expect(loadingCycles).toBe(0)
    expect(d.instance).toBe(0)
    expect(d.activities).toBeLessThanOrEqual(1)
    expect(d.userTasks).toBeLessThanOrEqual(1)
    expect(d.incidents).toBeLessThanOrEqual(1)
    for (const s of stops) s()
  })

  it('(в) событие при НЕАКТИВНОЙ вкладке variables и без данных → 0 запросов variables', async () => {
    const wrapper = await mountPage([]) // загружено, но пусто: length === 0
    const processStore = useProcessStore()
    const taskStore = useTaskStore()
    const incidentStore = useIncidentStore()
    expect(processStore.currentVariables).toHaveLength(0)
    // Дефолтная вкладка — bpmn (label = t('bpmnFlow'), т(k)=k в моке i18n).
    expect(wrapper.find('button[role="tab"][aria-selected="true"]').text()).toBe('bpmnFlow')

    let loadingCycles = 0
    const stops = [
      watch(() => processStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => taskStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => incidentStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
    ]

    // Три РАЗНЫХ известных элемента по одному завершению каждый: каждое событие
    // ложится в локальный патч (patchActivityCompletedLocal), ни одно не даёт
    // «повторный completed по неактивной копии» → консервативный activities-refresh
    // не срабатывает. (Повторное событие по уже COMPLETED элементу честно даёт
    // d.activities = 1 — штатное поведение UI-26, не предмет этого теста.)
    processStore.currentActivities = [
      activityRow('a-1', 'el-1', 'IN_PROGRESS'),
      activityRow('a-2', 'el-2', 'IN_PROGRESS'),
      activityRow('a-3', 'el-3', 'IN_PROGRESS'),
    ] as never
    const before = snapshot()
    for (let i = 0; i < 3; i++) {
      publishRealtimeEvent(envelope({
        type: 'activity.completed', sequence: 400 + i, id: `e-no-${i}`, elementId: `el-${i + 1}`,
      }))
    }
    await sleep(800)
    await flushPromises()

    const d = delta(before)
    // Не грузим то, что пользователь не открыл и на чём нет данных.
    expect(d.variables).toBe(0)
    expect(d.instance).toBe(0)
    expect(d.activities).toBe(0)
    expect(d.userTasks).toBe(0)
    expect(d.serviceTasks).toBe(0)
    expect(d.incidents).toBe(0)
    expect(loadingCycles).toBe(0)
    for (const s of stops) s()
  })

  it('(в-bis) событие при НЕАКТИВНОЙ вкладке variables, но данные УЖЕ загружены → ровно 1 тихий refetch без loading', async () => {
    const wrapper = await mountPage([varRow('old')])
    // Держим пользователя на bpmn (defолтная вкладка); данные переменных
    // при этом загружены (mount/init) — пользователь их видел при заходе.
    expect(wrapper.find('button[role="tab"][aria-selected="true"]').text()).toBe('bpmnFlow')
    const processStore = useProcessStore()
    expect(processStore.currentVariables.map((v) => v.value)).toEqual(['old'])

    mockGetVariables.mockResolvedValue({ data: [varRow('changed')] })

    let loadingCycles = 0
    const stops = [watch(() => processStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' })]

    processStore.currentActivities = [activityRow('a-1', 'el-1', 'IN_PROGRESS')] as never
    const before = snapshot()
    publishRealtimeEvent(envelope({
      type: 'activity.completed', sequence: 400, id: 'e-no-active', elementId: 'el-1',
    }))
    await sleep(800)
    await flushPromises()

    const d = delta(before)
    // Хранилище обновлено одним склеенным запросом (WO: «тихим склеенным refetch») —
    // не на каждое событие, и не бесконечно: ровно 1.
    expect(d.variables).toBe(1)
    expect(processStore.currentVariables.map((v) => v.value)).toEqual(['changed'])
    expect(d.instance).toBe(0)
    expect(d.activities).toBe(0)
    expect(loadingCycles).toBe(0)
    for (const s of stops) s()
  })

  it('(г) process-instance.completed при активной вкладке variables → переменные перечитаны, итог в DOM, без шторма', async () => {
    const wrapper = await mountPage([varRow('running')])
    mockGetVariables.mockResolvedValue({ data: [varRow('final')] })
    await clickTab(wrapper, 'variables')
    expect(wrapper.text()).toContain('final')

    const before = snapshot()
    // «Сервер» уже зафиксировал завершение: финальный refresh (finalRefresh,
    // UI-26) перечитает инстанс и вернёт терминальную строку — иначе локальный
    // patch completedAt затрётся строкой с completedAt: null.
    mockGetProcessInstance.mockResolvedValue({ ...piRow, completedAt: '2026-10-14T10:00:00Z' })
    publishRealtimeEvent(envelope({
      type: 'process-instance.completed', sequence: 500, id: 'e-done',
      occurredAt: '2026-10-14T10:00:00Z',
    }))
    await sleep(900)
    await flushPromises()

    const d = delta(before)
    // Терминальное событие: финальный refresh (UI-26) + возможный склеенный
    // refetch планировщика — шторма нет (≤2), свежие значения на месте.
    expect(d.variables).toBeGreaterThanOrEqual(1)
    expect(d.variables).toBeLessThanOrEqual(2)
    expect(wrapper.text()).toContain('final')
    // Локальный патч терминального статуса применён.
    const processStore = useProcessStore()
    expect(processStore.currentInstance?.completedAt).toBeTruthy()
  })
})
