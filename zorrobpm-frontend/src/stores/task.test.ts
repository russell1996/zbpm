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

  it('user-task.created refreshes task list', async () => {
    const store = useTaskStore()
    store.handleEvent({ sequence: 1, id: 't1', type: 'user-task.created', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    await vi.dynamicImportSettled()
    expect(taskService.getUserTasks).toHaveBeenCalled()
  })

  it('user-task.completed refreshes task list', async () => {
    const store = useTaskStore()
    store.handleEvent({ sequence: 2, id: 't2', type: 'user-task.completed', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    await vi.dynamicImportSettled()
    expect(taskService.getUserTasks).toHaveBeenCalled()
  })

  it('service-task.created refreshes service task list', async () => {
    const store = useTaskStore()
    store.handleEvent({ sequence: 3, id: 't3', type: 'service-task.created', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    await vi.dynamicImportSettled()
    expect(taskService.getServiceTasks).toHaveBeenCalled()
  })

  it('unrelated event does not trigger refresh', () => {
    const store = useTaskStore()
    store.handleEvent({ sequence: 4, id: 't4', type: 'process-instance.completed', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    expect(taskService.getUserTasks).not.toHaveBeenCalled()
    expect(taskService.getServiceTasks).not.toHaveBeenCalled()
  })
})
