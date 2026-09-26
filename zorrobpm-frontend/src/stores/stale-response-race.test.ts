// @vitest-environment jsdom
/**
 * WO-UI-18 часть B (критерий 5) — гонка устаревших ответов.
 *
 * Управляемый порядок разрешения: второй запрос разрешается РАНЬШЕ первого.
 * Без request-id guard список показывает данные устаревшего (первого) фильтра
 * поверх актуального — POF-мутация (убрать guard) роняет оба теста.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useTaskStore } from './task'
import { useIncidentStore } from './incident'
import { useProcessStore } from './process'
import * as taskService from '@/services/taskService'
import * as incidentService from '@/services/incidentService'
import * as processService from '@/services/processService'
import * as instanceService from '@/services/instanceService'
import * as variableService from '@/services/variableService'

vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn(),
  getUserTask: vi.fn().mockResolvedValue(null),
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getServiceTask: vi.fn().mockResolvedValue(null),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getIncident: vi.fn().mockResolvedValue(null),
  resolveIncident: vi.fn(),
}))
vi.mock('@/services/processService', () => ({
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessInstance: vi.fn().mockResolvedValue(null),
  getProcessInstanceActivities: vi.fn().mockResolvedValue([]),
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  startProcessInstance: vi.fn(),
}))

function deferred<T>(example?: T) {
  void example
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

describe('stale-response race (WO-UI-18 B)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('task store: late first response does not overwrite the second filter data', async () => {
    const first = deferred({ data: [{ id: 'stale' }], totalElements: 1 })
    const second = deferred({ data: [{ id: 'fresh' }], totalElements: 1 })
    vi.mocked(taskService.getUserTasks)
      .mockReturnValueOnce(first.promise as never)
      .mockReturnValueOnce(second.promise as never)

    const store = useTaskStore()
    const p1 = store.fetchUserTasks({ completed: false })
    const p2 = store.fetchUserTasks({ completed: true })
    // второй (актуальный) разрешается раньше первого (устаревшего):
    second.resolve({ data: [{ id: 'fresh' }], totalElements: 1 } as never)
    await p2
    first.resolve({ data: [{ id: 'stale' }], totalElements: 1 } as never)
    await p1

    expect(store.userTasks?.data.map((t) => t.id)).toEqual(['fresh'])
  })

  it('incident store: late first response does not overwrite the second filter data', async () => {
    const first = deferred({ data: [{ id: 'stale' }], totalElements: 1 })
    const second = deferred({ data: [{ id: 'fresh' }], totalElements: 1 })
    vi.mocked(incidentService.getIncidents)
      .mockReturnValueOnce(first.promise as never)
      .mockReturnValueOnce(second.promise as never)

    const store = useIncidentStore()
    const p1 = store.fetchIncidents({})
    const p2 = store.fetchIncidents({ resolved: false })
    second.resolve({ data: [{ id: 'fresh' }], totalElements: 1 } as never)
    await p2
    first.resolve({ data: [{ id: 'stale' }], totalElements: 1 } as never)
    await p1

    expect(store.incidents?.data.map((i) => i.id)).toEqual(['fresh'])
  })

  it('process store: late first response does not overwrite the second filter data', async () => {
    const first = deferred({ data: [{ id: 'stale' }], totalElements: 1 })
    const second = deferred({ data: [{ id: 'fresh' }], totalElements: 1 })
    vi.mocked(instanceService.getProcessInstances)
      .mockReturnValueOnce(first.promise as never)
      .mockReturnValueOnce(second.promise as never)

    const store = useProcessStore()
    const p1 = store.fetchInstances({})
    const p2 = store.fetchInstances({ processDefinitionKey: 'order' })
    second.resolve({ data: [{ id: 'fresh' }], totalElements: 1 } as never)
    await p2
    first.resolve({ data: [{ id: 'stale' }], totalElements: 1 } as never)
    await p1

    expect(store.instances?.data.map((i) => i.id)).toEqual(['fresh'])
  })

  it('service-task list: late first response does not overwrite the second filter data', async () => {
    const first = deferred({ data: [{ id: 'stale' }], totalElements: 1 })
    const second = deferred({ data: [{ id: 'fresh' }], totalElements: 1 })
    vi.mocked(taskService.getServiceTasks)
      .mockReturnValueOnce(first.promise as never)
      .mockReturnValueOnce(second.promise as never)

    const store = useTaskStore()
    const p1 = store.fetchServiceTasks({})
    const p2 = store.fetchServiceTasks({ completed: true })
    second.resolve({ data: [{ id: 'fresh' }], totalElements: 1 } as never)
    await p2
    first.resolve({ data: [{ id: 'stale' }], totalElements: 1 } as never)
    await p1

    expect(store.serviceTasks?.data.map((t) => t.id)).toEqual(['fresh'])
  })

  it('definitions list: late first response does not overwrite the second filter data', async () => {
    const first = deferred({ data: [{ id: 'stale' }], totalElements: 1 })
    const second = deferred({ data: [{ id: 'fresh' }], totalElements: 1 })
    vi.mocked(processService.getProcessDefinitions)
      .mockReturnValueOnce(first.promise as never)
      .mockReturnValueOnce(second.promise as never)

    const store = useProcessStore()
    const p1 = store.fetchDefinitions({})
    const p2 = store.fetchDefinitions({ name: 'order' })
    second.resolve({ data: [{ id: 'fresh' }], totalElements: 1 } as never)
    await p2
    first.resolve({ data: [{ id: 'stale' }], totalElements: 1 } as never)
    await p1

    expect(store.definitions?.data.map((d) => d.id)).toEqual(['fresh'])
  })
})
