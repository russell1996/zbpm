// @vitest-environment jsdom
/**
 * WO-UI-25 критерий 3 — refreshActivities перечитывает activities, НЕ сбрасывая
 * пагинацию «догрузить ещё» (загруженные страницы остаются загруженными).
 * RED на коде до WO-UI-25: метода refreshActivities нет.
 */
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
  getProcessInstanceActivitiesPaged: vi.fn(),
  startProcessInstance: vi.fn(),
}))
vi.mock('@/services/variableService', () => ({
  getVariables: vi.fn().mockResolvedValue({ data: [] }),
}))

function activityRow(i: number) {
  return {
    id: `act-${i}`, processInstanceId: 'pi-1', bpmnElementId: `Task_${i}`, type: 'userTask',
    status: 'COMPLETED', createdAt: '2026-10-09', completedAt: '2026-10-09',
  }
}

describe('WO-UI-25 criterion 3: refreshActivities preserves pagination', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('reloads the same page window the user already loaded (2 pages stay 2)', async () => {
    const paged = vi.mocked(instanceService.getProcessInstanceActivitiesPaged)
    const first100 = Array.from({ length: 100 }, (_, i) => activityRow(i))
    const last50 = Array.from({ length: 50 }, (_, i) => activityRow(100 + i))
    paged.mockImplementation((_id: string, pageIndex = 0) =>
      Promise.resolve(pageIndex === 0
        ? { data: first100, totalElements: 150, pageIndex: 0, pageSize: 100 }
        : { data: last50, totalElements: 150, pageIndex: 1, pageSize: 100 }),
    )
    const store = useProcessStore()
    await store.fetchActivities('pi-1')
    await store.fetchMoreActivities('pi-1')
    expect(store.currentActivities).toHaveLength(150)

    // Живое обновление: перечитать то же окно (0..1), не сбрасывая в 1 страницу.
    paged.mockClear()
    const refreshed = [...first100, ...last50]
    paged.mockImplementation((_id: string, pageIndex = 0) =>
      Promise.resolve(pageIndex === 0
        ? { data: refreshed.slice(0, 100), totalElements: 150, pageIndex: 0, pageSize: 100 }
        : { data: refreshed.slice(100), totalElements: 150, pageIndex: 1, pageSize: 100 }),
    )
    await store.refreshActivities('pi-1')
    expect(store.currentActivities).toHaveLength(150)
    expect(paged).toHaveBeenCalledWith('pi-1', 0, 100)
    expect(paged).toHaveBeenCalledWith('pi-1', 1, 100)
    expect(store.hasMoreActivities).toBe(false)
  })

  it('single-page instances refresh with exactly one request', async () => {
    const paged = vi.mocked(instanceService.getProcessInstanceActivitiesPaged)
    paged.mockResolvedValue({ data: [activityRow(0)], totalElements: 1, pageIndex: 0, pageSize: 100 })
    const store = useProcessStore()
    await store.fetchActivities('pi-1')
    paged.mockClear()
    await store.refreshActivities('pi-1')
    expect(paged).toHaveBeenCalledTimes(1)
    expect(store.currentActivities).toHaveLength(1)
  })
})
