// @vitest-environment jsdom
/**
 * WO-UI-26 Б-1 — точечное обновление страницы инстанса (главная жалоба владельца).
 *
 * Было: refreshLive делал 7 ЯВНЫХ fetch с loading=true на КАЖДОЕ событие
 * (ProcessInstanceDetail.vue:544-556 до фикса) → мерцание.
 * Стало: событие → патч только затронутой сущности (0 запросов при локальном
 * патче, иначе 1 тихий по-сущностный refresh, склеенный), loading — только
 * первичная загрузка, тихие — через `refreshing`.
 *
 * - серия N activity.completed по известным elementId → 0 запросов, loading 0;
 * - серия N activity.completed по неизвестным → ≤1 тихий activities-fetch,
 *   0 остальных сущностей, loading 0;
 * - серия N user-task.completed по известным строкам → 0 запросов (локальное
 *   удаление), loading 0;
 * - мутация «вернуть явные fetch с loading=true» → красный (loadingCycles>0).
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
import type { EventEnvelope } from '@/types/api'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'pi-1' }, path: '/instances/pi-1', query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
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
    occurredAt: '2026-10-09T00:00:00Z',
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
  return { id, bpmnElementId: elementId, type: 'userTask', status, createdAt: '2026-10-09', completedAt: null }
}

function taskRow(id: string) {
  return {
    id, code: `code-${id}`, name: `Task ${id}`, status: 'CREATED' as const,
    createdAt: '2026-10-09', completedAt: null, processInstanceId: 'pi-1',
    processDefinitionId: 'pd-1', formKey: null,
  }
}

describe('ProcessInstanceDetail точечное обновление (WO-UI-26 Б-1)', () => {
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

  afterEach(() => {
    resetRealtimeBusForTest()
    resetRealtimeSchedulerForTest()
  })

  it('серия N activity.completed по известным элементам → 0 запросов, loading 0', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await flushPromises()
    await new Promise((r) => setTimeout(r, 100))
    await flushPromises()

    const processStore = useProcessStore()
    const taskStore = useTaskStore()
    const incidentStore = useIncidentStore()
    // Известные живые activities — локальный патч возможен.
    processStore.currentActivities = [
      activityRow('a-1', 'el-1', 'IN_PROGRESS'),
      activityRow('a-2', 'el-2', 'IN_PROGRESS'),
      activityRow('a-3', 'el-3', 'CREATED'),
    ] as never

    let loadingCycles = 0
    const stops = [
      watch(() => processStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => taskStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => incidentStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
    ]
    const callsBefore = {
      instance: mockGetProcessInstance.mock.calls.length,
      activities: mockGetActivitiesPaged.mock.calls.length,
      userTasks: mockGetUserTasks.mock.calls.length,
      serviceTasks: mockGetServiceTasks.mock.calls.length,
      incidents: mockGetIncidents.mock.calls.length,
      variables: mockGetVariables.mock.calls.length,
    }

    const N = 3
    const els = ['el-1', 'el-2', 'el-3']
    for (let i = 0; i < N; i++) {
      publishRealtimeEvent(envelope({
        type: 'activity.completed', sequence: 100 + i, id: `e-live-${i}`,
        elementId: els[i],
      }))
    }
    await new Promise((r) => setTimeout(r, 800))
    await flushPromises()

    // Локальные патчи — ни одного запроса сверх начальной загрузки.
    expect(mockGetProcessInstance.mock.calls.length).toBe(callsBefore.instance)
    expect(mockGetActivitiesPaged.mock.calls.length).toBe(callsBefore.activities)
    expect(mockGetUserTasks.mock.calls.length).toBe(callsBefore.userTasks)
    expect(mockGetServiceTasks.mock.calls.length).toBe(callsBefore.serviceTasks)
    expect(mockGetIncidents.mock.calls.length).toBe(callsBefore.incidents)
    expect(mockGetVariables.mock.calls.length).toBe(callsBefore.variables)
    expect(loadingCycles).toBe(0)
    // Статусы перевёрнуты локально (BPMN-маркеры обновятся реактивно).
    expect(processStore.currentActivities.map((a: { status: string | null }) => a.status))
      .toEqual(['COMPLETED', 'COMPLETED', 'COMPLETED'])
    for (const s of stops) s()
    wrapper.unmount()
  })

  it('серия N activity.completed по неизвестным → ≤1 тихий activities-fetch, loading 0', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await flushPromises()
    await new Promise((r) => setTimeout(r, 100))
    await flushPromises()

    const processStore = useProcessStore()
    const taskStore = useTaskStore()
    const incidentStore = useIncidentStore()
    processStore.currentActivities = [activityRow('a-1', 'el-known', 'IN_PROGRESS')] as never

    let loadingCycles = 0
    const stops = [
      watch(() => processStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => taskStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => incidentStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
    ]
    const callsBefore = {
      instance: mockGetProcessInstance.mock.calls.length,
      activities: mockGetActivitiesPaged.mock.calls.length,
      userTasks: mockGetUserTasks.mock.calls.length,
      serviceTasks: mockGetServiceTasks.mock.calls.length,
      incidents: mockGetIncidents.mock.calls.length,
    }

    const N = 10
    for (let i = 0; i < N; i++) {
      publishRealtimeEvent(envelope({
        type: 'activity.completed', sequence: 200 + i, id: `e-unk-${i}`,
        elementId: `el-unknown-${i}`,
      }))
    }
    await new Promise((r) => setTimeout(r, 1200))
    await flushPromises()

    // Склеенный тихий refresh только activities — ровно 1, остальные — 0.
    const activitiesDelta = mockGetActivitiesPaged.mock.calls.length - callsBefore.activities
    expect(activitiesDelta).toBeLessThanOrEqual(1)
    expect(activitiesDelta).toBeGreaterThanOrEqual(1)
    expect(mockGetProcessInstance.mock.calls.length).toBe(callsBefore.instance)
    expect(mockGetUserTasks.mock.calls.length).toBe(callsBefore.userTasks)
    expect(mockGetServiceTasks.mock.calls.length).toBe(callsBefore.serviceTasks)
    expect(mockGetIncidents.mock.calls.length).toBe(callsBefore.incidents)
    expect(loadingCycles).toBe(0)
    for (const s of stops) s()
    wrapper.unmount()
  })

  it('серия N user-task.completed по известным строкам → 0 запросов (локальное удаление)', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await flushPromises()
    await new Promise((r) => setTimeout(r, 100))
    await flushPromises()

    const taskStore = useTaskStore()
    const processStore = useProcessStore()
    const incidentStore = useIncidentStore()
    taskStore.userTasks = {
      data: [taskRow('t-1'), taskRow('t-2'), taskRow('t-3')] as never,
      totalElements: 3, pageIndex: 0, pageSize: 100,
    }
    let loadingCycles = 0
    const stops = [
      watch(() => processStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => taskStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
      watch(() => incidentStore.loading, (v) => { if (v) loadingCycles++ }, { flush: 'sync' }),
    ]
    const userCallsBefore = mockGetUserTasks.mock.calls.length
    const instanceCallsBefore = mockGetProcessInstance.mock.calls.length

    for (let i = 1; i <= 3; i++) {
      publishRealtimeEvent(envelope({
        type: 'user-task.completed', sequence: 300 + i, id: `e-done-${i}`,
        data: { activityId: `t-${i}` },
      }))
    }
    await new Promise((r) => setTimeout(r, 800))
    await flushPromises()

    expect(mockGetUserTasks.mock.calls.length).toBe(userCallsBefore)
    expect(mockGetProcessInstance.mock.calls.length).toBe(instanceCallsBefore)
    expect(loadingCycles).toBe(0)
    expect(taskStore.userTasks?.data).toHaveLength(0)
    for (const s of stops) s()
    wrapper.unmount()
  })

  it('чужой инстанс → 0 запросов, 0 ререндеров данных', async () => {
    const wrapper = mount(ProcessInstanceDetail, {
      global: { stubs, plugins: [createPinia()] },
    })
    await flushPromises()
    await new Promise((r) => setTimeout(r, 100))
    await flushPromises()

    const callsBefore = {
      instance: mockGetProcessInstance.mock.calls.length,
      activities: mockGetActivitiesPaged.mock.calls.length,
      userTasks: mockGetUserTasks.mock.calls.length,
    }
    publishRealtimeEvent(envelope({
      type: 'activity.completed', sequence: 500, id: 'e-alien',
      processInstanceId: 'pi-OTHER', elementId: 'el-1',
    }))
    await new Promise((r) => setTimeout(r, 600))
    await flushPromises()
    expect(mockGetProcessInstance.mock.calls.length).toBe(callsBefore.instance)
    expect(mockGetActivitiesPaged.mock.calls.length).toBe(callsBefore.activities)
    expect(mockGetUserTasks.mock.calls.length).toBe(callsBefore.userTasks)
    wrapper.unmount()
  })
})
