// @vitest-environment jsdom
/**
 * WO-UI-39: список инцидентов помнит фильтр «показать решённые» и страницу при
 * переходе в карточку инцидента и возврате «Назад».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import IncidentList from './IncidentList.vue'

const INCIDENTS = [
  { id: 'i-1', message: 'Job failed', activityId: 'a1', processName: 'Vacation', bpmnElementId: 'a1', completedAt: null, createdAt: '2026-01-01T10:00:00Z' },
  { id: 'i-2', message: 'Timeout', activityId: 'a2', processName: 'Invoice', bpmnElementId: 'a2', completedAt: null, createdAt: '2026-01-02T10:00:00Z' },
]

vi.mock('@/stores/incident', async () => {
  const { reactive } = await import('vue')
  const store = reactive({
    incidents: null as { data: unknown[]; totalElements: number } | null,
    loading: false,
    error: null as string | null,
    fetchIncidents: vi.fn(),
  })
  return { useIncidentStore: () => store }
})

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

import { useIncidentStore } from '@/stores/incident'

const store = useIncidentStore() as unknown as {
  incidents: { data: unknown[]; totalElements: number } | null
  fetchIncidents: ReturnType<typeof vi.fn>
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'incidents', name: 'incidents', component: IncidentList },
          {
            path: 'incidents/:id',
            name: 'incident-detail',
            component: defineComponent({ setup: () => () => h('div', { 'data-testid': 'detail' }, 'detail') }),
          },
        ],
      },
    ],
  })
}

describe('WO-UI-39: IncidentList помнит фильтр и страницу после «Назад»', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    document.body.innerHTML = ''
    store.incidents = null
    store.fetchIncidents.mockImplementation(async () => {
      store.incidents = { data: INCIDENTS, totalElements: 25 }
      return store.incidents
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('CRIT-1: фильтр → карточка → «Назад» → фильтр и страница на месте', async () => {
    const router = makeRouter()
    await router.push('/incidents')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchIncidents.mock.calls.length).toBeGreaterThan(0))

    const checkbox = wrapper.find('input[type="checkbox"]')
    expect(checkbox.exists()).toBe(true)
    await checkbox.setValue(true)
    await vi.waitFor(
      () => {
        const last = store.fetchIncidents.mock.calls[store.fetchIncidents.mock.calls.length - 1][0]
        expect(last.resolved).toBeUndefined()
      },
      { timeout: 3000 },
    )

    const nextBtn = wrapper.findAll('button').find((b) => b.text() === 'next')
    expect(nextBtn).toBeDefined()
    await nextBtn!.trigger('click')
    await vi.waitFor(() => {
      const last = store.fetchIncidents.mock.calls[store.fetchIncidents.mock.calls.length - 1][0]
      expect(last.pageIndex).toBe(1)
    })

    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['incidents.resolved']).toBe('1')
      expect(router.currentRoute.value.query['incidents.page']).toBe('2')
    })

    await wrapper.find('tbody tr').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="detail"]').exists()).toBe(true))

    router.back()
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('tbody tr').exists()).toBe(true))
    await vi.waitFor(
      () => {
        const last = store.fetchIncidents.mock.calls[store.fetchIncidents.mock.calls.length - 1][0]
        expect(last.resolved).toBeUndefined()
        expect(last.pageIndex).toBe(1)
      },
      { timeout: 3000 },
    )
    expect(router.currentRoute.value.query['incidents.resolved']).toBe('1')
    expect(router.currentRoute.value.query['incidents.page']).toBe('2')

    wrapper.unmount()
  })

  it('CRIT-3: битый параметр из URL → дефолт', async () => {
    const router = makeRouter()
    await router.push('/incidents?incidents.resolved=maybe&incidents.page=xyz')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchIncidents.mock.calls.length).toBeGreaterThan(0))
    const last = store.fetchIncidents.mock.calls[store.fetchIncidents.mock.calls.length - 1][0]
    expect(last.resolved).toBe(false)
    expect(last.pageIndex).toBe(0)
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['incidents.resolved']).toBeUndefined()
      expect(router.currentRoute.value.query['incidents.page']).toBeUndefined()
    })
    wrapper.unmount()
  })
})
