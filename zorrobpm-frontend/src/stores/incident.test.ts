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

  it('incident.raised refreshes incident list', async () => {
    const store = useIncidentStore()
    store.handleEvent({ sequence: 1, id: 't1', type: 'incident.raised', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    await vi.dynamicImportSettled()
    expect(incidentService.getIncidents).toHaveBeenCalled()
  })

  it('incident.resolved refreshes incident list', async () => {
    const store = useIncidentStore()
    store.handleEvent({ sequence: 2, id: 't2', type: 'incident.resolved', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    await vi.dynamicImportSettled()
    expect(incidentService.getIncidents).toHaveBeenCalled()
  })

  it('unrelated event does not trigger refresh', () => {
    const store = useIncidentStore()
    store.handleEvent({ sequence: 3, id: 't3', type: 'process-instance.completed', version: 1, occurredAt: '2026-07-20T10:00:00Z', data: {} })
    expect(incidentService.getIncidents).not.toHaveBeenCalled()
  })
})
