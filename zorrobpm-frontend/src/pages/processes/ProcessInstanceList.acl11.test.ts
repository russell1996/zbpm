// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 6, 9 — ProcessInstanceList:
 *  6 — the whole row opens the instance card on click;
 *  9 — focus + Enter opens it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ProcessInstanceList from './ProcessInstanceList.vue'

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    instances: {
      data: [
        { id: 'pi-1', processName: 'Vacation', processKey: 'vacation', processVersion: 1, completedAt: null, startedAt: '2026-01-01' },
      ],
      totalElements: 1,
    },
    // WO-UI-21: компонент грузит definitions для дропдауна на mount.
    definitions: {
      data: [
        { id: 'def-1', key: 'vacation', name: 'Vacation', version: 1, sha256: 'a', createdAt: '2026-01-01', startFormKey: null },
      ],
      totalElements: 1,
    },
    loading: false,
    error: null,
    fetchInstances: vi.fn().mockResolvedValue(undefined),
    fetchDefinitions: vi.fn().mockResolvedValue(undefined),
  }),
}))
vi.mock('@/composables/usePagination', () => ({
  usePagination: () => ({ page: 0, pageSize: 20, nextPage: vi.fn(), prevPage: vi.fn(), hasNext: false, hasPrev: false, resetPage: vi.fn() }),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'processes/instances', name: 'process-instances', component: { template: '<div />' } },
          { path: 'processes/instances/:id', name: 'process-instance-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-ACL-11 criteria 6/9: ProcessInstanceList rows', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('criterion 6: clicking the row opens the instance card', async () => {
    const router = makeRouter()
    await router.push('/processes/instances')
    await router.isReady()
    const wrapper = mount(ProcessInstanceList, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/processes/instances/pi-1')
  })

  it('criterion 9: pressing Enter on the focused row opens the instance card', async () => {
    const router = makeRouter()
    await router.push('/processes/instances')
    await router.isReady()
    const wrapper = mount(ProcessInstanceList, { global: { plugins: [router] } })
    await flushPromises()

    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/processes/instances/pi-1')
  })
})