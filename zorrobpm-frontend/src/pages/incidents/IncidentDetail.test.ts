// @vitest-environment jsdom
/**
 * WO-ACL-16 criterion 5 — IncidentDetail:
 *  - the card shows process name and element (name or bpmn element id);
 *  - the process instance id is a link to the instance card
 *    (/processes/instances/:id);
 *  - a missing processInstanceId renders a dash, not a dead link.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import IncidentDetail from './IncidentDetail.vue'

const mocks = vi.hoisted(() => ({
  currentIncident: {
    id: 'inc-1',
    activityId: 'act-1',
    message: 'Task failed',
    createdAt: '2026-01-01',
    completedAt: null,
    processName: 'Vacation Request',
    processInstanceId: 'pi-1234567890',
    bpmnElementId: 'Activity_1abc',
    elementName: 'Approve Order',
  } as {
    id: string
    activityId: string
    message: string
    createdAt: string
    completedAt: string | null
    processName: string | null
    processInstanceId: string | null
    bpmnElementId: string | null
    elementName: string | null
  },
}))

vi.mock('@/stores/incident', () => ({
  useIncidentStore: () => ({
    incidents: { data: [], totalElements: 0 },
    loading: false,
    error: null,
    currentIncident: mocks.currentIncident,
    fetchIncident: vi.fn().mockResolvedValue(undefined),
    resolveIncident: vi.fn().mockResolvedValue(undefined),
  }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))
vi.mock('@/composables/useBreadcrumbLabel', () => ({ useBreadcrumbLabel: () => {} }))

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
          { path: 'processes/instances/:id', name: 'process-instance-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-ACL-16 criterion 5: incident card context + link to the process instance', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('shows process name and element name instead of the raw activity id', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = makeRouter()
    await router.push('/incidents/inc-1')
    await router.isReady()
    const wrapper = mount(IncidentDetail, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Vacation Request')
    expect(wrapper.text()).toContain('Approve Order')
    expect(wrapper.text()).not.toContain('act-1')
  })

  it('process instance id is a link that opens the instance card', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = makeRouter()
    await router.push('/incidents/inc-1')
    await router.isReady()
    const wrapper = mount(IncidentDetail, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const link = wrapper.find('button.font-mono')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('pi-12345')
    await link.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/processes/instances/pi-1234567890')
  })

  it('without a process instance id renders a dash and no link', async () => {
    mocks.currentIncident = { ...mocks.currentIncident, processInstanceId: null }
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = makeRouter()
    await router.push('/incidents/inc-1')
    await router.isReady()
    const wrapper = mount(IncidentDetail, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.find('button.font-mono').exists()).toBe(false)
    const piRow = wrapper.findAll('div.grid > div').find((d) => d.text().includes('processInstance'))
    expect(piRow?.text()).toContain('—')
  })
})
