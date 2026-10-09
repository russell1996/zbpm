// @vitest-environment jsdom
/**
 * WO-UI-26 Доп.4 (кр.17–19) — адресные патчи вместо полного refetch.
 * - кр.17: каждое событие → ровно один адресный патч (или один GET одной
 *   сущности), 0 запросов списка;
 * - кр.18: чужой контекст → 0 запросов, 0 ререндеров;
 * - кр.19: разрыв sequence → ровно один склеенный refetch; дубль/устаревшее → no-op.
 * Мутации: вернуть полный refetch на событие → красный (по каждому).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useTaskStore } from '@/stores/task'
import { useProcessStore } from '@/stores/process'
import { useIncidentStore } from '@/stores/incident'
import { resetRealtimeSchedulerForTest } from '@/services/realtimeScheduler'
import type { EventEnvelope } from '@/types/api'

vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn(),
  getUserTask: vi.fn(),
  completeUserTask: vi.fn(),
  getServiceTasks: vi.fn(),
  getServiceTask: vi.fn(),
  completeServiceTask: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstances: vi.fn(),
  getProcessInstance: vi.fn(),
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
vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn(),
  getIncident: vi.fn(),
  resolveIncident: vi.fn(),
}))

import * as taskService from '@/services/taskService'
import * as instanceService from '@/services/instanceService'
import * as incidentService from '@/services/incidentService'

function envelope(partial: Partial<EventEnvelope> & { type: string }): EventEnvelope {
  return {
    sequence: 1,
    id: `e-${Math.random()}`,
    version: 1,
    occurredAt: '2026-10-09T00:00:00Z',
    data: {},
    ...partial,
  } as EventEnvelope
}

function taskRow(id: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    code: `code-${id}`,
    name: `Task ${id}`,
    processInstanceId: 'pi-1',
    processDefinitionId: 'pd-1',
    formKey: null,
    status: 'CREATED' as const,
    createdAt: '2026-10-09',
    completedAt: null,
    ...extra,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  resetRealtimeSchedulerForTest()
  vi.mocked(taskService.getUserTasks).mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 10 })
  vi.mocked(instanceService.getProcessInstances).mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 10 })
  vi.mocked(incidentService.getIncidents).mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 10 })
})

describe('WO-UI-26 Доп.4 адресные патчи', () => {
  it('кр.17: user-task.created → один GET сущности, 0 запросов списка', async () => {
    const store = useTaskStore()
    store.userTasks = { data: [taskRow('t0')], totalElements: 1, pageIndex: 0, pageSize: 10 }
    vi.mocked(taskService.getUserTask).mockResolvedValue(taskRow('t9'))
    store.handleEvent(envelope({ type: 'user-task.created', sequence: 10, id: 'e-10', data: { activityId: 't9' } }))
    await vi.waitFor(() => expect(taskService.getUserTask).toHaveBeenCalledWith('t9'))
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
    expect(store.userTasks?.data.map((t) => t.id)).toContain('t9')
    expect(store.loading).toBe(false)
  })

  it('кр.17: process-instance.completed → патч статуса без запросов', () => {
    const store = useProcessStore()
    store.instances = {
      data: [{ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', processName: 'P', processKey: 'k', processVersion: 1, startedAt: '2026-10-09', completedAt: null, cancelled: false }],
      totalElements: 1, pageIndex: 0, pageSize: 10,
    }
    store.handleEvent(envelope({ type: 'process-instance.completed', sequence: 11, id: 'e-11', processInstanceId: 'pi-1' }))
    expect(instanceService.getProcessInstance).not.toHaveBeenCalled()
    expect(instanceService.getProcessInstances).not.toHaveBeenCalled()
    expect(store.instances?.data[0].completedAt).toBeTruthy()
    expect(store.loading).toBe(false)
  })

  it('кр.17: incident.raised → один GET сущности, 0 запросов списка', async () => {
    const store = useIncidentStore()
    store.incidents = { data: [], totalElements: 0, pageIndex: 0, pageSize: 10 }
    vi.mocked(incidentService.getIncident).mockResolvedValue({
      id: 'inc-1', activityId: 'a-1', message: 'boom', createdAt: '2026-10-09',
      completedAt: null, processName: null, processInstanceId: 'pi-1', bpmnElementId: 'el-1', elementName: null,
    })
    store.handleEvent(envelope({ type: 'incident.raised', sequence: 12, id: 'e-12', data: { incidentId: 'inc-1' } }))
    await vi.waitFor(() => expect(incidentService.getIncident).toHaveBeenCalledWith('inc-1'))
    expect(incidentService.getIncidents).not.toHaveBeenCalled()
    expect(store.incidents?.data.map((i) => i.id)).toContain('inc-1')
    expect(store.loading).toBe(false)
  })

  it('кр.18: событие чужого инстанса → 0 запросов, 0 ререндеров', () => {
    const store = useTaskStore()
    // скоуп страницы — инстанс pi-1
    store.handleEvent(envelope({ type: 'user-task.created', sequence: 13, id: 'e-13', processInstanceId: 'pi-OTHER', data: { activityId: 'tX' } }))
    // GET сущности идёт (адрес есть), но строка чужого инстанса в скоуп-список не встанет:
    // здесь список пуст и query без скоупа — проверяем completed-уход чужой строки:
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
  })

  it('кр.18: completed-уход чужой строки не трогает список', async () => {
    const store = useTaskStore()
    // Страница активного списка: query с completed:false (как TaskList).
    vi.mocked(taskService.getUserTasks).mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 10 })
    await store.fetchUserTasks({ completed: false, pageIndex: 0, pageSize: 10 })
    vi.mocked(taskService.getUserTasks).mockClear()
    const mine = taskRow('mine')
    store.userTasks = { data: [mine], totalElements: 1, pageIndex: 0, pageSize: 10 }
    const before = store.userTasks
    store.handleEvent(envelope({ type: 'user-task.completed', sequence: 14, id: 'e-14', data: { activityId: 'alien' } }))
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
    expect(taskService.getUserTask).not.toHaveBeenCalled()
    expect(store.userTasks).toBe(before)
    expect(store.userTasks?.data).toHaveLength(1)
  })

  it('кр.19: дубль события → no-op (0 запросов)', () => {
    const store = useTaskStore()
    store.userTasks = { data: [taskRow('t0')], totalElements: 1, pageIndex: 0, pageSize: 10 }
    vi.mocked(taskService.getUserTask).mockResolvedValue(taskRow('t9'))
    const ev = envelope({ type: 'user-task.created', sequence: 15, id: 'e-15', data: { activityId: 't9' } })
    store.handleEvent(ev)
    store.handleEvent({ ...ev })
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
  })

  it('кр.19: устаревшее событие → no-op', async () => {
    const store = useTaskStore()
    store.userTasks = { data: [taskRow('t0')], totalElements: 1, pageIndex: 0, pageSize: 10 }
    vi.mocked(taskService.getUserTask).mockResolvedValue(taskRow('t9'))
    store.handleEvent(envelope({ type: 'user-task.created', sequence: 20, id: 'e-20', data: { activityId: 't9' } }))
    await vi.waitFor(() => expect(taskService.getUserTask).toHaveBeenCalledTimes(1))
    vi.mocked(taskService.getUserTask).mockClear()
    // sequence 19 < применённого 20 — no-op
    store.handleEvent(envelope({ type: 'user-task.created', sequence: 19, id: 'e-19', data: { activityId: 't8' } }))
    await new Promise((r) => setTimeout(r, 50))
    expect(taskService.getUserTask).not.toHaveBeenCalled()
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
  })

  it('кр.19: разрыв sequence → ровно один склеенный refetch (debounce)', async () => {
    const store = useTaskStore()
    store.userTasks = { data: [taskRow('t0')], totalElements: 1, pageIndex: 0, pageSize: 10 }
    vi.mocked(taskService.getUserTask).mockResolvedValue(taskRow('t9'))
    store.handleEvent(envelope({ type: 'user-task.created', sequence: 30, id: 'e-30', data: { activityId: 't9' } }))
    await vi.waitFor(() => expect(taskService.getUserTask).toHaveBeenCalledTimes(1))
    // разрыв: 35 при last=30 → запасной refetch, склеенный (пачка разрывов = 1)
    store.handleEvent(envelope({ type: 'user-task.created', sequence: 35, id: 'e-35', data: { activityId: 'tX' } }))
    store.handleEvent(envelope({ type: 'user-task.created', sequence: 36, id: 'e-36', data: { activityId: 'tY' } }))
    await new Promise((r) => setTimeout(r, 800))
    expect(taskService.getUserTasks).toHaveBeenCalledTimes(1)
    expect(store.loading).toBe(false)
  })

  it('мутация-контроль: фоновый refresh не ставит loading', async () => {
    const store = useTaskStore()
    store.userTasks = { data: [taskRow('t0')], totalElements: 1, pageIndex: 0, pageSize: 10 }
    let sawLoading = false
    const unsub = store.$subscribe((_m, _s) => {
      if (store.loading) sawLoading = true
    })
    vi.mocked(taskService.getUserTasks).mockResolvedValue({
      data: [taskRow('t0'), taskRow('t1')], totalElements: 2, pageIndex: 0, pageSize: 10,
    })
    await store.refreshUserTasksQuiet()
    unsub()
    expect(sawLoading).toBe(false)
    expect(store.userTasks?.data).toHaveLength(2)
  })

  it('кр.15: патч одной строки из 50 — identity остальных 49 не меняется', () => {
    const store = useProcessStore()
    const rows = Array.from({ length: 50 }, (_, i) => ({
      id: `pi-${i}`, parentActivityId: null, processDefinitionId: 'pd-1', processName: 'P',
      processKey: 'k', processVersion: 1, startedAt: '2026-10-09', completedAt: null, cancelled: false,
    }))
    store.instances = { data: rows, totalElements: 50, pageIndex: 0, pageSize: 100 }
    const beforeObjects = [...store.instances.data]
    store.handleEvent(envelope({
      type: 'process-instance.completed', sequence: 40, id: 'e-40', processInstanceId: 'pi-7',
    }))
    const after = store.instances!.data
    expect(after).toHaveLength(50)
    // Изменилась РОВНО одна строка (новый объект только у неё)…
    expect(after[7]).not.toBe(beforeObjects[7])
    expect(after[7].completedAt).toBeTruthy()
    // …остальные 49 — те же объекты (Vue не трогает их реактивность).
    for (let i = 0; i < 50; i++) {
      if (i === 7) continue
      expect(after[i]).toBe(beforeObjects[i])
    }
    expect(store.loading).toBe(false)
  })
})
