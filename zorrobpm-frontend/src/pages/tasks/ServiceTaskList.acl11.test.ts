// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 6, 9 — ServiceTaskList:
 *  6 — the whole row opens the service-task card on click;
 *  9 — focus + Enter opens it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ServiceTaskList from './ServiceTaskList.vue'

vi.mock('@/stores/task', () => ({
  useTaskStore: () => ({
    serviceTasks: {
      data: [
        { id: 'st-1', name: 'Send mail', code: 'send-mail', job: 'mail', status: 'ACTIVE', completedAt: null, createdAt: '2026-01-01', processInstanceId: 'pi-1' },
      ],
      totalElements: 1,
    },
    loading: false,
    error: null,
    fetchServiceTasks: vi.fn().mockResolvedValue(undefined),
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
          { path: 'service-tasks', name: 'service-tasks', component: { template: '<div />' } },
          { path: 'service-tasks/:id', name: 'service-task-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-ACL-11 criteria 6/9: ServiceTaskList rows', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('criterion 6: clicking the row opens the service-task card', async () => {
    const router = makeRouter()
    await router.push('/service-tasks')
    await router.isReady()
    const wrapper = mount(ServiceTaskList, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/service-tasks/st-1')
  })

  it('criterion 9: pressing Enter on the focused row opens the service-task card', async () => {
    const router = makeRouter()
    await router.push('/service-tasks')
    await router.isReady()
    const wrapper = mount(ServiceTaskList, { global: { plugins: [router] } })
    await flushPromises()

    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/service-tasks/st-1')
  })
})