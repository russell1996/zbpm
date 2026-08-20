// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 6, 8, 9 — IncidentList:
 *  6 — the whole row opens the incident card on click;
 *  8 — clicking an interactive element inside the row (CopyableId) does NOT open it;
 *  9 — the row is keyboard-accessible: focus + Enter opens it.
 * WO-ACL-16 criterion 5 — the list shows process and element instead of the raw activity UUID.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import IncidentList from './IncidentList.vue'

vi.mock('@/stores/incident', () => ({
  useIncidentStore: () => ({
    incidents: {
      data: [
        { id: 'inc-1', activityId: 'act-1', message: 'Task failed', completedAt: null, createdAt: '2026-01-01', processName: 'Vacation Request', processInstanceId: 'pi-1', bpmnElementId: 'Activity_1abc', elementName: 'Approve Order' },
        { id: 'inc-2', activityId: 'act-2', message: 'Fixed', completedAt: '2026-01-02', createdAt: '2026-01-02', processName: null, processInstanceId: null, bpmnElementId: 'Activity_2xyz', elementName: null },
      ],
      totalElements: 2,
    },
    loading: false,
    error: null,
    fetchIncidents: vi.fn().mockResolvedValue(undefined),
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
          { path: 'incidents', name: 'incidents', component: { template: '<div />' } },
          { path: 'incidents/:id', name: 'incident-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-ACL-11 criteria 6/8/9: IncidentList rows', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('criterion 6: clicking the row opens the incident card', async () => {
    const router = makeRouter()
    await router.push('/incidents')
    await router.isReady()
    const wrapper = mount(IncidentList, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/incidents/inc-1')
  })

  it('criterion 9: pressing Enter on the focused row opens the incident card', async () => {
    const router = makeRouter()
    await router.push('/incidents')
    await router.isReady()
    const wrapper = mount(IncidentList, { global: { plugins: [router] } })
    await flushPromises()

    const row = wrapper.findAll('tbody tr')[1]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/incidents/inc-2')
  })

  it('criterion 8: clicking CopyableId (interactive) does NOT open the card', async () => {
    const router = makeRouter()
    await router.push('/incidents')
    await router.isReady()
    const wrapper = mount(IncidentList, { global: { plugins: [router] } })
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    const copyable = firstRow.find('span.group')
    expect(copyable.exists()).toBe(true)
    await copyable.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/incidents')
  })
})

describe('WO-ACL-16 criterion 5: the list shows process and element instead of the raw activity UUID', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders processName and elementName in the row, no raw activityId cell', async () => {
    const router = makeRouter()
    await router.push('/incidents')
    await router.isReady()
    const wrapper = mount(IncidentList, { global: { plugins: [router] } })
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    expect(firstRow.text()).toContain('Vacation Request')
    expect(firstRow.text()).toContain('Approve Order')
    // raw UUID of the activity is not shown as a cell anymore
    expect(firstRow.text()).not.toContain('act-1')
  })

  it('falls back to bpmnElementId when elementName is missing, and to a dash when processName is missing', async () => {
    const router = makeRouter()
    await router.push('/incidents')
    await router.isReady()
    const wrapper = mount(IncidentList, { global: { plugins: [router] } })
    await flushPromises()

    const secondRow = wrapper.findAll('tbody tr')[1]
    // no processName → dash for the process column
    expect(secondRow.text()).toContain('—')
    // no elementName → the bpmn element id itself is shown
    expect(secondRow.text()).toContain('Activity_2xyz')
  })
})