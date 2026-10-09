// @vitest-environment jsdom
/**
 * WO-UI-25 критерий 2 — task-стор обрабатывает user-task.assigned/unassigned.
 * RED на коде до WO-UI-25: handleEvent знает только created/completed.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useTaskStore } from './task'
import * as taskService from '@/services/taskService'
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

function envelope(type: string): EventEnvelope {
  return { sequence: 1, id: 'e-1', type, version: 1, occurredAt: '2026-10-09T00:00:00Z', data: {} }
}

describe('WO-UI-25 criterion 2: task store handles assign/unassign events', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  // WO-UI-26 Доп.4: assign/unassign — адресный патч одним GET сущности
  // (assignee из data), список — нет.
  it('user-task.assigned patches one row via single-entity GET, no list refetch', async () => {
    const store = useTaskStore()
    vi.mocked(taskService.getUserTask).mockResolvedValue({
      id: 'a1', code: 'c', name: 'A', processInstanceId: 'pi-1', processDefinitionId: 'pd-1',
      formKey: null, status: 'CREATED', createdAt: '2026-10-09T00:00:00Z', completedAt: null,
      assignee: 'petrov',
    } as never)
    store.handleEvent({ ...envelope('user-task.assigned'), data: { activityId: 'a1' } })
    await vi.waitFor(() => expect(taskService.getUserTask).toHaveBeenCalledWith('a1'))
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
  })

  it('user-task.unassigned patches one row via single-entity GET, no list refetch', async () => {
    const store = useTaskStore()
    vi.mocked(taskService.getUserTask).mockResolvedValue({
      id: 'a1', code: 'c', name: 'A', processInstanceId: 'pi-1', processDefinitionId: 'pd-1',
      formKey: null, status: 'CREATED', createdAt: '2026-10-09T00:00:00Z', completedAt: null,
    })
    store.handleEvent({ ...envelope('user-task.unassigned'), data: { activityId: 'a1' } })
    await vi.waitFor(() => expect(taskService.getUserTask).toHaveBeenCalledWith('a1'))
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
  })

  it('assign/unassign do not touch the service-task list', async () => {
    const store = useTaskStore()
    store.handleEvent(envelope('user-task.assigned'))
    store.handleEvent(envelope('user-task.unassigned'))
    await vi.dynamicImportSettled()
    expect(taskService.getServiceTasks).not.toHaveBeenCalled()
  })
})
