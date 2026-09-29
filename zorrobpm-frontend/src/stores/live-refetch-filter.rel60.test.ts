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

  it('task: user-task.completed refetch повторяет completed:false страницы', async () => {
    const store = useTaskStore()
    await store.fetchUserTasks({ pageIndex: 0, pageSize: 10, completed: false })
    expect(taskService.getUserTasks).toHaveBeenLastCalledWith({ pageIndex: 0, pageSize: 10, completed: false })

    store.handleEvent(envelope('user-task.completed'))
    await vi.waitFor(() => expect(taskService.getUserTasks).toHaveBeenCalledTimes(2))
    // P-67: ассерт на КОНКРЕТНЫЙ query (а не «вызван хоть как-то») — голый
    // fetchUserTasks() дал бы {} и тест покраснел бы именно здесь.
    expect(taskService.getUserTasks).toHaveBeenLastCalledWith({ pageIndex: 0, pageSize: 10, completed: false })
  })

  it('task: user-task.created refetch тоже повторяет фильтр (та же ветка)', async () => {
    const store = useTaskStore()
    await store.fetchUserTasks({ pageIndex: 2, pageSize: 10, completed: false })

    store.handleEvent(envelope('user-task.created'))
    await vi.waitFor(() => expect(taskService.getUserTasks).toHaveBeenCalledTimes(2))
    expect(taskService.getUserTasks).toHaveBeenLastCalledWith({ pageIndex: 2, pageSize: 10, completed: false })
  })

  it('task: service-task.created refetch повторяет фильтр service-списка', async () => {
    const store = useTaskStore()
    await store.fetchServiceTasks({ pageIndex: 1, pageSize: 10, completed: true })

    store.handleEvent(envelope('service-task.created'))
    await vi.waitFor(() => expect(taskService.getServiceTasks).toHaveBeenCalledTimes(2))
    expect(taskService.getServiceTasks).toHaveBeenLastCalledWith({ pageIndex: 1, pageSize: 10, completed: true })
  })

  it('incident: incident.resolved refetch повторяет resolved:false страницы', async () => {
    const store = useIncidentStore()
    await store.fetchIncidents({ pageIndex: 0, pageSize: 10, resolved: false })

    store.handleEvent(envelope('incident.resolved'))
    await vi.waitFor(() => expect(incidentService.getIncidents).toHaveBeenCalledTimes(2))
    expect(incidentService.getIncidents).toHaveBeenLastCalledWith({ pageIndex: 0, pageSize: 10, resolved: false })
  })

  it('process: process-instance.completed refetch повторяет фильтр ключа', async () => {
    const store = useProcessStore()
    await store.fetchInstances({ pageIndex: 0, pageSize: 10, processDefinitionKey: 'order' })

    store.handleEvent(envelope('process-instance.completed'))
    await vi.waitFor(() => expect(instanceService.getProcessInstances).toHaveBeenCalledTimes(2))
    expect(instanceService.getProcessInstances).toHaveBeenLastCalledWith({
      pageIndex: 0,
      pageSize: 10,
      processDefinitionKey: 'order',
    })
  })

  it('без предшествующего fetch refetch идёт с пустым query (дефолт, не взрыв)', async () => {
    const store = useTaskStore()
    store.handleEvent(envelope('user-task.completed'))
    await vi.waitFor(() => expect(taskService.getUserTasks).toHaveBeenCalledTimes(1))
    expect(taskService.getUserTasks).toHaveBeenLastCalledWith({})
  })
})
