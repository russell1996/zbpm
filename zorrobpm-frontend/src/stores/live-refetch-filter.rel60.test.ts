// @vitest-environment jsdom
/**
 * WO-REL-60 — live-refetch обязан повторять ТЕКУЩИЙ фильтр страницы.
 *
 * Корневая причина ночного красного E2E 27–28.09 (доказана живым CDP-кадром +
 * network-логом traced-прогона): TaskList грузит список с completed:false, а
 * handleEvent на user-task.completed звал голый fetchUserTasks() без query —
 * refetch отвечал БЕЗ completed=false и возвращал только что завершённую
 * задачу обратно в список. Строка не исчезала, хотя SSE-кадр долетел.
 *
 * POF-мутация (настоящая, бьёт в прод-код): вернуть в handleEvent голый вызов
 * fetchUserTasks() / fetchServiceTasks() / fetchIncidents() / fetchInstances()
 * без lastQuery — ровно эти тесты краснеют (URL без фильтра / вызов без query).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useTaskStore } from '@/stores/task'
import { useIncidentStore } from '@/stores/incident'
import { useProcessStore } from '@/stores/process'
import * as taskService from '@/services/taskService'
import * as incidentService from '@/services/incidentService'
import * as instanceService from '@/services/instanceService'
import type { EventEnvelope } from '@/types/api'

vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getUserTask: vi.fn().mockResolvedValue(null),
  completeUserTask: vi.fn(),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getServiceTask: vi.fn().mockResolvedValue(null),
  completeServiceTask: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getIncident: vi.fn().mockResolvedValue(null),
  resolveIncident: vi.fn(),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessInstance: vi.fn().mockResolvedValue(null),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/processService', () => ({
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
}))

function envelope(type: string): EventEnvelope {
  return { sequence: 6, id: 'e-6', type, version: 1, occurredAt: '2026-09-29T00:00:00Z', data: {} }
}

describe('WO-REL-60 live-refetch keeps the page filter', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  // WO-UI-26 Доп.4: события идут адресными патчами; запасной refetch
  // (разрыв sequence) повторяет ТЕКУЩИЙ фильтр страницы — тот же инвариант
  // REL-60, но на тихом пути refreshUserTasksQuiet (без loading).
  it('task: разрыв sequence после completed refetch повторяет completed:false страницы', async () => {
    const store = useTaskStore()
    await store.fetchUserTasks({ pageIndex: 0, pageSize: 10, completed: false })
    expect(taskService.getUserTasks).toHaveBeenLastCalledWith({ pageIndex: 0, pageSize: 10, completed: false })

    // sequence 1 затем разрыв на 5 → запасной склеенный refetch с фильтром
    store.handleEvent({ ...envelope('user-task.completed'), sequence: 1, id: 'e-gap-1', data: { activityId: 'alien' } })
    store.handleEvent({ ...envelope('user-task.completed'), sequence: 5, id: 'e-gap-5', data: { activityId: 'alien' } })
    await vi.waitFor(() => expect(taskService.getUserTasks).toHaveBeenCalledTimes(2), { timeout: 3000 })
    // P-67: ассерт на КОНКРЕТНЫЙ query (а не «вызван хоть как-то») — голый
    // fetch дал бы {} и тест покраснел бы именно здесь.
    expect(taskService.getUserTasks).toHaveBeenLastCalledWith({ pageIndex: 0, pageSize: 10, completed: false })
    expect(store.loading).toBe(false)
  })

  it('task: created без activityId идёт запасным путём с фильтром страницы', async () => {
    const store = useTaskStore()
    await store.fetchUserTasks({ pageIndex: 2, pageSize: 10, completed: false })
    vi.clearAllMocks()

    // разрыв sequence без адреса сущности → склеенный refetch с фильтром
    store.handleEvent({ ...envelope('user-task.created'), sequence: 1, id: 'e-f1', data: {} })
    store.handleEvent({ ...envelope('user-task.created'), sequence: 9, id: 'e-f9', data: {} })
    await vi.waitFor(() => expect(taskService.getUserTasks).toHaveBeenCalledTimes(1), { timeout: 3000 })
    expect(taskService.getUserTasks).toHaveBeenLastCalledWith({ pageIndex: 2, pageSize: 10, completed: false })
    expect(store.loading).toBe(false)
  })

  it('task: service-task.created без разрыва — тихий refetch по расписанию, без loading', async () => {
    const store = useTaskStore()
    await store.fetchServiceTasks({ pageIndex: 1, pageSize: 10, completed: true })
    vi.clearAllMocks()

    store.handleEvent({ ...envelope('service-task.created'), sequence: 2, id: 'e-s2', data: {} })
    await vi.waitFor(() => expect(taskService.getServiceTasks).toHaveBeenCalledTimes(1), { timeout: 3000 })
    expect(taskService.getServiceTasks).toHaveBeenLastCalledWith({ pageIndex: 1, pageSize: 10, completed: true })
    expect(store.loading).toBe(false)
  })

  it('incident: разрыв sequence refetch повторяет resolved:false страницы', async () => {
    const store = useIncidentStore()
    await store.fetchIncidents({ pageIndex: 0, pageSize: 10, resolved: false })
    vi.clearAllMocks()

    store.handleEvent({ ...envelope('incident.resolved'), sequence: 1, id: 'e-i1', data: {} })
    store.handleEvent({ ...envelope('incident.resolved'), sequence: 7, id: 'e-i7', data: {} })
    await vi.waitFor(() => expect(incidentService.getIncidents).toHaveBeenCalledTimes(1), { timeout: 3000 })
    expect(incidentService.getIncidents).toHaveBeenLastCalledWith({ pageIndex: 0, pageSize: 10, resolved: false })
    expect(store.loading).toBe(false)
  })

  it('process: разрыв sequence refetch повторяет фильтр ключа', async () => {
    const store = useProcessStore()
    await store.fetchInstances({ pageIndex: 0, pageSize: 10, processDefinitionKey: 'order' })
    vi.clearAllMocks()

    store.handleEvent({ ...envelope('process-instance.completed'), sequence: 1, id: 'e-p1', processInstanceId: 'pi-x', data: {} })
    store.handleEvent({ ...envelope('process-instance.completed'), sequence: 8, id: 'e-p8', processInstanceId: 'pi-x', data: {} })
    await vi.waitFor(() => expect(instanceService.getProcessInstances).toHaveBeenCalledTimes(1), { timeout: 3000 })
    expect(instanceService.getProcessInstances).toHaveBeenLastCalledWith({
      pageIndex: 0,
      pageSize: 10,
      processDefinitionKey: 'order',
    })
    expect(store.loading).toBe(false)
  })

  it('без разрыва и без адреса — патч невозможен, тихий refetch с lastQuery', async () => {
    const store = useTaskStore()
    await store.fetchUserTasks({ pageIndex: 0, pageSize: 10, completed: false })
    vi.clearAllMocks()

    // одиночное событие без activityId: патч невозможен → запасной refetch
    store.handleEvent({ ...envelope('user-task.created'), sequence: 50, id: 'e-solo', data: {} })
    await vi.waitFor(() => expect(taskService.getUserTasks).toHaveBeenCalledTimes(1), { timeout: 3000 })
    expect(taskService.getUserTasks).toHaveBeenLastCalledWith({ pageIndex: 0, pageSize: 10, completed: false })
  })
})
