import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useTaskStore } from './task'
import type { EventEnvelope } from '@/types/api'
import * as taskService from '@/services/taskService'

vi.mock('@/services/taskService', () => ({
  getUserTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getUserTask: vi.fn().mockResolvedValue(null),
  completeUserTask: vi.fn().mockResolvedValue(undefined),
  getServiceTasks: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getServiceTask: vi.fn().mockResolvedValue(null),
  completeServiceTask: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

describe('taskStore handleEvent', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  // WO-UI-26 Доп.4: событие → адресный патч (один GET сущности), а не
  // полный refetch списка. Старые ожидания getUserTasks заменены.
  it('user-task.created patches one row via single-entity GET, no list refetch', async () => {
    const store = useTaskStore()
    vi.mocked(taskService.getUserTask).mockResolvedValue({
      id: 'nt1', code: 'c', name: 'N', processInstanceId: 'pi-1', processDefinitionId: 'pd-1',
      formKey: null, status: 'CREATED', createdAt: '2026-07-20T10:00:00Z', completedAt: null,
    })
    store.handleEvent({ sequence: 1, id: 't1', type: 'user-task.created', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: { activityId: 'nt1' } })
    await vi.waitFor(() => expect(taskService.getUserTask).toHaveBeenCalledWith('nt1'))
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
  })

  it('user-task.completed removes the row without requests (active list)', async () => {
    const store = useTaskStore()
    vi.mocked(taskService.getUserTasks).mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 10 })
    await store.fetchUserTasks({ completed: false, pageIndex: 0, pageSize: 10 })
    vi.clearAllMocks()
    store.userTasks = {
      data: [{ id: 'done1', code: 'c', name: 'D', processInstanceId: 'pi-1', processDefinitionId: 'pd-1', formKey: null, status: 'CREATED', createdAt: '2026-07-20T10:00:00Z', completedAt: null }],
      totalElements: 1, pageIndex: 0, pageSize: 10,
    }
    store.handleEvent({ sequence: 2, id: 't2', type: 'user-task.completed', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: { activityId: 'done1' } })
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
    expect(taskService.getUserTask).not.toHaveBeenCalled()
    expect(store.userTasks?.data).toHaveLength(0)
  })

  it('service-task.created schedules quiet fallback, no loading', async () => {
    const store = useTaskStore()
    store.handleEvent({ sequence: 3, id: 't3', type: 'service-task.created', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    await new Promise((r) => setTimeout(r, 800))
    expect(taskService.getServiceTasks).toHaveBeenCalledTimes(1)
    expect(store.loading).toBe(false)
  })

  it('unrelated event does not trigger refresh', () => {
    const store = useTaskStore()
    store.handleEvent({ sequence: 4, id: 't4', type: 'process-instance.completed', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
    expect(taskService.getServiceTasks).not.toHaveBeenCalled()
  })
})
