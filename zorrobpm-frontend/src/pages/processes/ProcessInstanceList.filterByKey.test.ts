// @vitest-environment jsdom
/**
 * WO-FE-21: Instance filter by key (not UUID)
 *
 * - Filter sends processDefinitionKey, not processDefinitionId
 * - Placeholder says "key" not "definition ID"
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ProcessInstanceList from './ProcessInstanceList.vue'

const mockFetchInstances = vi.fn().mockResolvedValue({ data: [], totalElements: 0 })

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    instances: null,
    loading: false,
    error: null,
    fetchInstances: mockFetchInstances,
  }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v, formatDate: (v: string) => v }),
}))

vi.mock('@/shared/lib/export', () => ({
  exportToCsv: vi.fn(),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'processes/instances', name: 'process-instances', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-FE-21: ProcessInstanceList filter by key', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 1: filter accepts a key string
  // ─────────────────────────────────────────────────────────────
  it('CRIT-1: input uses processDefinitionKey in API query', async () => {
    const router = makeRouter()
    await router.push('/processes/instances')
    await router.isReady()

    const wrapper = mount(ProcessInstanceList, {
      global: { plugins: [router, createPinia()] },
    })
    await flushPromises()

    // Type a key into the filter
    const input = wrapper.find('input')
    await input.setValue('incomingCorrespondenceProcess')
    await flushPromises()

    // The store.fetchInstances should have been called with processDefinitionKey
    const calls = mockFetchInstances.mock.calls
    const lastCall = calls[calls.length - 1][0]
    expect(lastCall.processDefinitionKey).toBe('incomingCorrespondenceProcess')
    expect(lastCall.processDefinitionId).toBeUndefined()
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 2: empty filter sends undefined (no filter param)
  // ─────────────────────────────────────────────────────────────
  it('CRIT-2: empty filter sends undefined for processDefinitionKey', async () => {
    const router = makeRouter()
    await router.push('/processes/instances')
    await router.isReady()

    const wrapper = mount(ProcessInstanceList, {
      global: { plugins: [router, createPinia()] },
    })
    await flushPromises()

    // The initial load should have processDefinitionKey: undefined
    const calls = mockFetchInstances.mock.calls
    const firstCall = calls[0][0]
    expect(firstCall.processDefinitionKey).toBeUndefined()
  })
})
