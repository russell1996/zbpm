// @vitest-environment jsdom
/**
 * WO-UI-39: список задач помнит фильтр «показать завершённые» и страницу при
 * переходе в карточку задачи и возврате «Назад».
 *
 * Как и в ProcessInstanceList.ui39.test.ts: настоящий компонент + настоящий
 * роутер, список живёт в <router-view> — переход в карточку реально
 * размонтирует список, «Назад» реально монтирует заново.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import TaskList from './TaskList.vue'

const TASKS = [
  { id: 't-1', name: 'Approve invoice', code: 'approve', status: 'ACTIVE', processInstanceId: 'pi-1', createdAt: '2026-01-01T10:00:00Z', completedAt: null },
  { id: 't-2', name: 'Second task', code: 'second', status: 'ACTIVE', processInstanceId: 'pi-2', createdAt: '2026-01-02T10:00:00Z', completedAt: null },
]

vi.mock('@/stores/task', async () => {
  const { reactive } = await import('vue')
  const store = reactive({
    userTasks: null as { data: unknown[]; totalElements: number } | null,
    serviceTasks: null,
    loading: false,
    error: null as string | null,
    fetchUserTasks: vi.fn(),
    fetchServiceTasks: vi.fn(),
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
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }),
}))

import { useTaskStore } from '@/stores/task'

const store = useTaskStore() as unknown as {
  userTasks: { data: unknown[]; totalElements: number } | null
  fetchUserTasks: ReturnType<typeof vi.fn>
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'tasks', name: 'my-tasks', component: TaskList },
          {
            path: 'tasks/:id',
            name: 'task-detail',
            component: defineComponent({ setup: () => () => h('div', { 'data-testid': 'detail' }, 'detail') }),
          },
        ],
      },
    ],
  })
}

async function mountList(router: ReturnType<typeof makeRouter>) {
  const wrapper = mount(
    defineComponent({ setup: () => () => h(RouterView) }),
    { global: { plugins: [router, createPinia()] } },
  )
  await flushPromises()
  return wrapper
}

describe('WO-UI-39: TaskList помнит фильтр и страницу после «Назад»', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    document.body.innerHTML = ''
    store.userTasks = null
    store.fetchUserTasks.mockImplementation(async () => {
      store.userTasks = { data: TASKS, totalElements: 25 }
      return store.userTasks
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('CRIT-1: фильтр → карточка → «Назад» → фильтр и страница на месте', async () => {
    const router = makeRouter()
    await router.push('/tasks')
    await router.isReady()
    const wrapper = await mountList(router)
    await vi.waitFor(() => expect(store.fetchUserTasks.mock.calls.length).toBeGreaterThan(0))

    // 1) Включаем «показать завершённые»
    const checkbox = wrapper.find('input[type="checkbox"]')
    expect(checkbox.exists()).toBe(true)
    await checkbox.setValue(true)
    await vi.waitFor(
      () => {
        const last = store.fetchUserTasks.mock.calls[store.fetchUserTasks.mock.calls.length - 1][0]
        expect(last.completed).toBe(true)
      },
      { timeout: 3000 },
    )

    // 2) Листаем на вторую страницу
    const nextBtn = wrapper.findAll('button').find((b) => b.text() === 'next')
    expect(nextBtn).toBeDefined()
    await nextBtn!.trigger('click')
    await vi.waitFor(() => {
      const last = store.fetchUserTasks.mock.calls[store.fetchUserTasks.mock.calls.length - 1][0]
      expect(last.pageIndex).toBe(1)
    })

    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['tasks.completed']).toBe('1')
      expect(router.currentRoute.value.query['tasks.page']).toBe('2')
    })

    // 3) Уход в карточку задачи
    await wrapper.find('tbody tr').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="detail"]').exists()).toBe(true))
    expect(router.currentRoute.value.name).toBe('task-detail')

    // 4) «Назад» — фильтр и страница на месте
    router.back()
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('tbody tr').exists()).toBe(true))
    await vi.waitFor(
      () => {
        const last = store.fetchUserTasks.mock.calls[store.fetchUserTasks.mock.calls.length - 1][0]
        expect(last.completed).toBe(true)
        expect(last.pageIndex).toBe(1)
      },
      { timeout: 3000 },
    )
    expect(router.currentRoute.value.query['tasks.completed']).toBe('1')
    expect(router.currentRoute.value.query['tasks.page']).toBe('2')

    wrapper.unmount()
  })

  it('CRIT-2: дефолты не попадают в URL (чистый URL)', async () => {
    const router = makeRouter()
    await router.push('/tasks')
    await router.isReady()
    const wrapper = await mountList(router)
    await vi.waitFor(() => expect(store.fetchUserTasks.mock.calls.length).toBeGreaterThan(0))
    expect(router.currentRoute.value.query['tasks.completed']).toBeUndefined()
    expect(router.currentRoute.value.query['tasks.page']).toBeUndefined()
    wrapper.unmount()
  })

  it('CRIT-3: битый параметр из URL → дефолт', async () => {
    const router = makeRouter()
    await router.push('/tasks?tasks.completed=maybe&tasks.page=xyz')
    await router.isReady()
    const wrapper = await mountList(router)
    await vi.waitFor(() => expect(store.fetchUserTasks.mock.calls.length).toBeGreaterThan(0))
    const last = store.fetchUserTasks.mock.calls[store.fetchUserTasks.mock.calls.length - 1][0]
    expect(last.completed).toBe(false)
    expect(last.pageIndex).toBe(0)
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['tasks.completed']).toBeUndefined()
      expect(router.currentRoute.value.query['tasks.page']).toBeUndefined()
    })
    wrapper.unmount()
  })
})
