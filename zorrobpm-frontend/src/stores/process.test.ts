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

  // WO-UI-26 Доп.4: событие → адресный патч, список — только запасным путём.
  it('process-instance.started patches one row via single-entity GET, no list refetch', async () => {
    const store = useProcessStore()
    store.instances = { data: [], totalElements: 0, pageIndex: 0, pageSize: 10 }
    vi.mocked(instanceService.getProcessInstance).mockResolvedValue({
      id: 'pi-9', parentActivityId: null, processDefinitionId: 'pd-1', processName: 'P9',
      processKey: 'k9', processVersion: 1, startedAt: '2026-07-20T10:00:00Z', completedAt: null, cancelled: false,
    })
    store.handleEvent({ sequence: 1, id: 't1', type: 'process-instance.started', version: 1, occurredAt: '2026-07-20T10:00:00Z', processInstanceId: 'pi-9', data: {} })
    await vi.waitFor(() => expect(instanceService.getProcessInstance).toHaveBeenCalledWith('pi-9'))
    expect(instanceService.getProcessInstances).not.toHaveBeenCalled()
    expect(store.instances?.data.map((p) => p.id)).toContain('pi-9')
    expect(store.loading).toBe(false)
  })

  it('process-instance.completed patches status without requests', () => {
    const store = useProcessStore()
    store.instances = {
      data: [{ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', processName: 'P', processKey: 'k', processVersion: 1, startedAt: '2026-07-20T10:00:00Z', completedAt: null, cancelled: false }],
      totalElements: 1, pageIndex: 0, pageSize: 10,
    }
    store.handleEvent({ sequence: 2, id: 't2', type: 'process-instance.completed', version: 1, occurredAt: '2026-07-20T10:00:00Z', processInstanceId: 'pi-1', data: {} })
    expect(instanceService.getProcessInstances).not.toHaveBeenCalled()
    expect(store.instances?.data[0].completedAt).toBeTruthy()
  })

  it('process-instance.cancelled patches via single GET when row exists', async () => {
    const store = useProcessStore()
    store.instances = {
      data: [{ id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', processName: 'P', processKey: 'k', processVersion: 1, startedAt: '2026-07-20T10:00:00Z', completedAt: null, cancelled: false }],
      totalElements: 1, pageIndex: 0, pageSize: 10,
    }
    vi.mocked(instanceService.getProcessInstance).mockResolvedValue({
      id: 'pi-1', parentActivityId: null, processDefinitionId: 'pd-1', processName: 'P',
      processKey: 'k', processVersion: 1, startedAt: '2026-07-20T10:00:00Z', completedAt: null, cancelled: true,
    })
    store.handleEvent({ sequence: 3, id: 't3', type: 'process-instance.cancelled', version: 1, occurredAt: '2026-07-20T10:00:00Z', processInstanceId: 'pi-1', data: {} })
    await vi.waitFor(() => expect(instanceService.getProcessInstance).toHaveBeenCalledWith('pi-1'))
    expect(instanceService.getProcessInstances).not.toHaveBeenCalled()
    expect(store.instances?.data[0].cancelled).toBe(true)
  })

  it('unrelated event does not trigger refresh', () => {
    const store = useProcessStore()
    store.handleEvent({ sequence: 4, id: 't4', type: 'user-task.created', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    expect(instanceService.getProcessInstances).not.toHaveBeenCalled()
  })
})
