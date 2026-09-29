import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useProcessStore } from './process'
import * as instanceService from '@/services/instanceService'

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
  getProcessInstanceActivitiesPaged: vi.fn().mockResolvedValue({ data: [], totalElements: 0, pageIndex: 0, pageSize: 100 }),
  startProcessInstance: vi.fn().mockResolvedValue({ id: 'new-id' }),
}))

vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

describe('processStore handleEvent', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('process-instance.started refreshes instance list', async () => {
    const store = useProcessStore()
    store.handleEvent({ sequence: 1, id: 't1', type: 'process-instance.started', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    await vi.dynamicImportSettled()
    expect(instanceService.getProcessInstances).toHaveBeenCalled()
  })

  it('process-instance.completed refreshes instance list', async () => {
    const store = useProcessStore()
    store.handleEvent({ sequence: 2, id: 't2', type: 'process-instance.completed', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    await vi.dynamicImportSettled()
    expect(instanceService.getProcessInstances).toHaveBeenCalled()
  })

  it('process-instance.cancelled refreshes instance list', async () => {
    const store = useProcessStore()
    store.handleEvent({ sequence: 3, id: 't3', type: 'process-instance.cancelled', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    await vi.dynamicImportSettled()
    expect(instanceService.getProcessInstances).toHaveBeenCalled()
  })

  it('unrelated event does not trigger refresh', () => {
    const store = useProcessStore()
    store.handleEvent({ sequence: 4, id: 't4', type: 'user-task.created', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    expect(instanceService.getProcessInstances).not.toHaveBeenCalled()
  })
})
