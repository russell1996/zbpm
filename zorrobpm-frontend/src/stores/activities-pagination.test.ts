// @vitest-environment jsdom
/**
 * WO-UI-18 часть C (критерий 7, jsdom-уровень) — activities идут постранично.
 * Browser-часть (кнопка «Показать ещё», реальная подгрузка в таблице) —
 * в `stores/realtime-events.browser.test.ts` (describe «activities pagination»).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useProcessStore } from './process'
import * as instanceService from '@/services/instanceService'
import * as processService from '@/services/processService'
import * as variableService from '@/services/variableService'

vi.mock('@/services/processService', () => ({
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessDefinition: vi.fn().mockResolvedValue(null),
  getProcessDefinitionStructure: vi.fn().mockResolvedValue(null),
  getProcessDefinitionVersions: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/instanceService', () => ({
  getProcessInstances: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  getProcessInstance: vi.fn().mockResolvedValue(null),
  getProcessInstanceActivities: vi.fn(),
  getProcessInstanceActivitiesPaged: vi.fn(),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

const page0 = { data: [{ id: 'a1' }, { id: 'a2' }], totalElements: 3, pageIndex: 0, pageSize: 100 }
const page1 = { data: [{ id: 'a3' }], totalElements: 3, pageIndex: 1, pageSize: 100 }

describe('activities pagination (WO-UI-18 C)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchActivities uses the paged endpoint and flags the remainder', async () => {
    vi.mocked(instanceService.getProcessInstanceActivitiesPaged).mockResolvedValue(page0 as never)
    const store = useProcessStore()
    await store.fetchActivities('pi-1')
    expect(instanceService.getProcessInstanceActivitiesPaged).toHaveBeenCalledWith('pi-1', 0, 100)
    expect(instanceService.getProcessInstanceActivities).not.toHaveBeenCalled()
    expect(store.currentActivities.map((a) => a.id)).toEqual(['a1', 'a2'])
    expect(store.hasMoreActivities).toBe(true)
    expect(store.currentActivitiesTotal).toBe(3)
  })

  it('fetchMoreActivities appends the next page and clears the flag at the end', async () => {
    vi.mocked(instanceService.getProcessInstanceActivitiesPaged)
      .mockResolvedValueOnce(page0 as never)
      .mockResolvedValueOnce(page1 as never)
    const store = useProcessStore()
    await store.fetchActivities('pi-1')
    await store.fetchMoreActivities('pi-1')
    expect(instanceService.getProcessInstanceActivitiesPaged).toHaveBeenLastCalledWith('pi-1', 1, 100)
    expect(store.currentActivities.map((a) => a.id)).toEqual(['a1', 'a2', 'a3'])
    expect(store.hasMoreActivities).toBe(false)
  })

  it('fetchMoreActivities is a no-op when nothing remains', async () => {
    vi.mocked(instanceService.getProcessInstanceActivitiesPaged).mockResolvedValue(page0 as never)
    const store = useProcessStore()
    await store.fetchActivities('pi-1')
    store.hasMoreActivities = false
    vi.clearAllMocks()
    await store.fetchMoreActivities('pi-1')
    expect(instanceService.getProcessInstanceActivitiesPaged).not.toHaveBeenCalled()
  })
})
