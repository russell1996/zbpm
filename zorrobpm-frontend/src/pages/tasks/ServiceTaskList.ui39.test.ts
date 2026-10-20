// @vitest-environment jsdom
/**
 * WO-UI-39: список сервисных задач помнит фильтр и страницу при переходе в
 * карточку задачи и возврате «Назад».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import ServiceTaskList from './ServiceTaskList.vue'

const SERVICE_TASKS = [
  { id: 'st-1', name: 'Send email', code: 'send', job: 'mail', status: 'ACTIVE', processInstanceId: 'pi-1', createdAt: '2026-01-01T10:00:00Z', completedAt: null },
  { id: 'st-2', name: 'Call API', code: 'call', job: 'http', status: 'ACTIVE', processInstanceId: 'pi-2', createdAt: '2026-01-02T10:00:00Z', completedAt: null },
]

vi.mock('@/stores/task', async () => {
  const { reactive } = await import('vue')
  const store = reactive({
    serviceTasks: null as { data: unknown[]; totalElements: number } | null,
    userTasks: null,
    loading: false,
    error: null as string | null,
    fetchServiceTasks: vi.fn(),
    fetchUserTasks: vi.fn(),
    completeUserTask: vi.fn(),
  })
  return { useTaskStore: () => store }
})

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

import { useTaskStore } from '@/stores/task'

const store = useTaskStore() as unknown as {
  serviceTasks: { data: unknown[]; totalElements: number } | null
  fetchServiceTasks: ReturnType<typeof vi.fn>
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'service-tasks', name: 'service-tasks', component: ServiceTaskList },
          {
            path: 'service-tasks/:id',
            name: 'service-task-detail',
            component: defineComponent({ setup: () => () => h('div', { 'data-testid': 'detail' }, 'detail') }),
          },
        ],
      },
    ],
  })
}

describe('WO-UI-39: ServiceTaskList помнит фильтр и страницу после «Назад»', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    document.body.innerHTML = ''
    store.serviceTasks = null
    store.fetchServiceTasks.mockImplementation(async () => {
      store.serviceTasks = { data: SERVICE_TASKS, totalElements: 25 }
      return store.serviceTasks
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('CRIT-1: фильтр → карточка → «Назад» → фильтр и страница на месте', async () => {
    const router = makeRouter()
    await router.push('/service-tasks')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchServiceTasks.mock.calls.length).toBeGreaterThan(0))

    const checkbox = wrapper.find('input[type="checkbox"]')
    expect(checkbox.exists()).toBe(true)
    await checkbox.setValue(true)
    await vi.waitFor(
      () => {
        const last = store.fetchServiceTasks.mock.calls[store.fetchServiceTasks.mock.calls.length - 1][0]
        expect(last.completed).toBe(true)
      },
      { timeout: 3000 },
    )

    const nextBtn = wrapper.findAll('button').find((b) => b.text() === 'next')
    expect(nextBtn).toBeDefined()
    await nextBtn!.trigger('click')
    await vi.waitFor(() => {
      const last = store.fetchServiceTasks.mock.calls[store.fetchServiceTasks.mock.calls.length - 1][0]
      expect(last.pageIndex).toBe(1)
    })

    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['serviceTasks.completed']).toBe('1')
      expect(router.currentRoute.value.query['serviceTasks.page']).toBe('2')
    })

    await wrapper.find('tbody tr').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="detail"]').exists()).toBe(true))

    router.back()
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('tbody tr').exists()).toBe(true))
    await vi.waitFor(
      () => {
        const last = store.fetchServiceTasks.mock.calls[store.fetchServiceTasks.mock.calls.length - 1][0]
        expect(last.completed).toBe(true)
        expect(last.pageIndex).toBe(1)
      },
      { timeout: 3000 },
    )
    expect(router.currentRoute.value.query['serviceTasks.completed']).toBe('1')
    expect(router.currentRoute.value.query['serviceTasks.page']).toBe('2')

    wrapper.unmount()
  })

  it('CRIT-3: битый параметр из URL → дефолт', async () => {
    const router = makeRouter()
    await router.push('/service-tasks?serviceTasks.completed=maybe&serviceTasks.page=xyz')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchServiceTasks.mock.calls.length).toBeGreaterThan(0))
    const last = store.fetchServiceTasks.mock.calls[store.fetchServiceTasks.mock.calls.length - 1][0]
    expect(last.completed).toBe(false)
    expect(last.pageIndex).toBe(0)
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['serviceTasks.completed']).toBeUndefined()
      expect(router.currentRoute.value.query['serviceTasks.page']).toBeUndefined()
    })
    wrapper.unmount()
  })
})
