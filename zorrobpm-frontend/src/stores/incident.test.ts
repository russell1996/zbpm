import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useIncidentStore } from './incident'
import * as incidentService from '@/services/incidentService'

vi.mock('@/services/incidentService', () => ({
  getIncidents: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getIncident: vi.fn().mockResolvedValue(null),
  resolveIncident: vi.fn().mockResolvedValue(undefined),
}))

describe('incidentStore handleEvent', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  // WO-UI-26 Доп.4: событие → адресный патч (один GET сущности), список — нет.
  it('incident.raised patches one row via single-entity GET, no list refetch', async () => {
    const store = useIncidentStore()
    vi.mocked(incidentService.getIncident).mockResolvedValue({
      id: 'inc9', activityId: 'a9', message: 'm', createdAt: '2026-07-20T10:00:00Z',
      completedAt: null, processName: null, processInstanceId: 'pi-1', bpmnElementId: 'el-9', elementName: null,
    })
    store.handleEvent({ sequence: 1, id: 't1', type: 'incident.raised', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: { incidentId: 'inc9' } })
    await vi.waitFor(() => expect(incidentService.getIncident).toHaveBeenCalledWith('inc9'))
    expect(incidentService.getIncidents).not.toHaveBeenCalled()
    expect(store.loading).toBe(false)
  })

  it('incident.resolved patches one row via single-entity GET, no list refetch', async () => {
    const store = useIncidentStore()
    vi.mocked(incidentService.getIncident).mockResolvedValue({
      id: 'inc9', activityId: 'a9', message: 'm', createdAt: '2026-07-20T10:00:00Z',
      completedAt: '2026-07-20T10:00:00Z', processName: null, processInstanceId: 'pi-1', bpmnElementId: 'el-9', elementName: null,
    })
    store.handleEvent({ sequence: 2, id: 't2', type: 'incident.resolved', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: { incidentId: 'inc9' } })
    await vi.waitFor(() => expect(incidentService.getIncident).toHaveBeenCalledWith('inc9'))
    expect(incidentService.getIncidents).not.toHaveBeenCalled()
    expect(store.loading).toBe(false)
  })

  it('unrelated event does not trigger refresh', () => {
    const store = useIncidentStore()
    store.handleEvent({ sequence: 3, id: 't3', type: 'process-instance.completed', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    expect(incidentService.getIncidents).not.toHaveBeenCalled()
  })
})
