// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 6, 8, 9 — TaskList:
 *  6 — the whole row opens the task card on click;
 *  8 — clicking the row checkbox (interactive) does NOT open the card;
 *  9 — focus + Enter opens it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import TaskList from './TaskList.vue'

vi.mock('@/stores/task', () => ({
  useTaskStore: () => ({
    userTasks: {
      data: [
        { id: 't-1', name: 'Review', code: 'review', status: 'ACTIVE', completedAt: null, createdAt: '2026-01-01', processInstanceId: 'pi-1' },
        { id: 't-2', name: 'Done task', code: 'done', status: 'COMPLETED', completedAt: '2026-01-02', createdAt: '2026-01-02', processInstanceId: 'pi-1' },
      ],
      totalElements: 2,
    },
    loading: false,
    error: null,
    fetchUserTasks: vi.fn().mockResolvedValue(undefined),
    completeUserTask: vi.fn().mockResolvedValue(undefined),
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
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
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
          { path: 'tasks', name: 'tasks', component: { template: '<div />' } },
          { path: 'tasks/:id', name: 'task-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-ACL-11 criteria 6/8/9: TaskList rows', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('criterion 6: clicking the row opens the task card', async () => {
    const router = makeRouter()
    await router.push('/tasks')
    await router.isReady()
    const wrapper = mount(TaskList, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/tasks/t-1')
  })

  it('criterion 8: clicking the row checkbox does NOT open the card', async () => {
    const router = makeRouter()
    await router.push('/tasks')
    await router.isReady()
    const wrapper = mount(TaskList, { global: { plugins: [router] } })
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    const checkbox = firstRow.find('input[type="checkbox"]')
    expect(checkbox.exists()).toBe(true)
    await checkbox.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/tasks')
  })

  it('criterion 9: pressing Enter on the focused row opens the task card', async () => {
    const router = makeRouter()
    await router.push('/tasks')
    await router.isReady()
    const wrapper = mount(TaskList, { global: { plugins: [router] } })
    await flushPromises()

    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/tasks/t-1')
  })
})