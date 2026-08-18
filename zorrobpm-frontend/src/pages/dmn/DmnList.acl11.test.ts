// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 6, 9 — DmnList:
 *  6 — the whole row opens the decision card on click;
 *  9 — focus + Enter opens it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import DmnList from './DmnList.vue'

const mockGetDecisions = vi.fn()
vi.mock('@/services/dmnService', () => ({
  getDecisions: (...args: any[]) => mockGetDecisions(...args),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ error: vi.fn(), success: vi.fn() }),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'dmn', name: 'dmn', component: { template: '<div />' } },
          { path: 'dmn/:id', name: 'dmn-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-ACL-11 criteria 6/9: DmnList rows', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetDecisions.mockResolvedValue([
      { id: 'dec-1', name: 'Loan decision', version: 2, hitPolicy: 'UNIQUE' },
    ])
  })

  it('criterion 6: clicking the row opens the decision card', async () => {
    const router = makeRouter()
    await router.push('/dmn')
    await router.isReady()
    const wrapper = mount(DmnList, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/dmn/dec-1')
  })

  it('criterion 9: pressing Enter on the focused row opens the decision card', async () => {
    const router = makeRouter()
    await router.push('/dmn')
    await router.isReady()
    const wrapper = mount(DmnList, { global: { plugins: [router] } })
    await flushPromises()

    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/dmn/dec-1')
  })

  it('criterion 7: the "view" button is gone from the table', async () => {
    const wrapper = mount(DmnList)
    await flushPromises()
    expect(wrapper.text()).not.toContain('view')
  })
})