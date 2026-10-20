// @vitest-environment jsdom
/**
 * WO-UI-39: список инстансов помнит фильтр и страницу при переходе в карточку
 * и возврате «Назад».
 *
 * Тест бьёт по НАСТОЯЩЕМУ компоненту страницы и НАСТОЯЩЕМУ роутеру
 * (createRouter + createMemoryHistory): список живёт внутри <router-view>,
 * поэтому переход в карточку РЕАЛЬНО размонтирует его, а «Назад» реально
 * монтирует заново — ровно то, что происходит в проде (и в чём состояние
 * раньше терялось).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import ProcessInstanceList from './ProcessInstanceList.vue'

// jsdom lacks Pointer Capture API — reka's SelectTrigger calls it on
// pointerdown (тот же polyfill, что в ProcessInstanceList.filterByKey.test.ts).
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}

const INSTANCES = [
  { id: 'pi-1', processKey: 'vacation', processName: 'Vacation', processVersion: 1, status: 'ACTIVE', startedAt: '2026-01-01T10:00:00Z', completedAt: null },
  { id: 'pi-2', processKey: 'onboarding', processName: 'Onboarding', processVersion: 1, status: 'ACTIVE', startedAt: '2026-01-02T10:00:00Z', completedAt: null },
]

const DEFINITIONS = {
  data: [
    { id: 'd1', key: 'vacation', name: 'Vacation Leave', version: 1, sha256: 'a', createdAt: '2026-01-01', startFormKey: null },
    { id: 'd2', key: 'onboarding', name: 'Onboarding', version: 1, sha256: 'b', createdAt: '2026-01-05', startFormKey: null },
  ],
  totalElements: 2,
}

vi.mock('@/stores/process', async () => {
  const { reactive } = await import('vue')
  const store = reactive({
    instances: null as { data: unknown[]; totalElements: number } | null,
    definitions: { data: [], totalElements: 0 } as { data: unknown[]; totalElements: number },
    loading: false,
    error: null as string | null,
    fetchInstances: vi.fn(),
    fetchDefinitions: vi.fn(),
  })
  return { useProcessStore: () => store }
})

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v, formatDate: (v: string) => v }),
}))

vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

import { useProcessStore } from '@/stores/process'

const store = useProcessStore() as unknown as {
  instances: { data: unknown[]; totalElements: number } | null
  definitions: { data: unknown[]; totalElements: number }
  fetchInstances: ReturnType<typeof vi.fn>
  fetchDefinitions: ReturnType<typeof vi.fn>
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'processes/instances', name: 'process-instances', component: ProcessInstanceList },
          {
            path: 'processes/instances/:id',
            name: 'process-instance-detail',
            component: defineComponent({ setup: () => () => h('div', { 'data-testid': 'detail' }, 'detail') }),
          },
        ],
      },
    ],
  })
}

async function openSelect(wrapper: ReturnType<typeof mount>, triggerTestid: string) {
  const trigger = wrapper.find(`[data-testid="${triggerTestid}"]`)
  expect(trigger.exists()).toBe(true)
  trigger.element.dispatchEvent(
    new MouseEvent('pointerdown', { button: 0, ctrlKey: false, bubbles: true, cancelable: true }),
  )
  await new Promise((r) => setTimeout(r, 0))
}

async function chooseSelectItem(itemTestid: string) {
  const item = document.querySelector(`[data-testid="${itemTestid}"]`)
  expect(item).not.toBeNull()
  ;(item as HTMLElement).dispatchEvent(new MouseEvent('pointerup', { bubbles: true, button: 0 }))
  await new Promise((r) => setTimeout(r, 0))
}

describe('WO-UI-39: ProcessInstanceList помнит фильтр и страницу после «Назад»', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    document.body.innerHTML = ''
    store.instances = null
    store.definitions = { data: [], totalElements: 0 }
    store.fetchInstances.mockImplementation(async () => {
      store.instances = { data: INSTANCES, totalElements: 25 }
      return store.instances
    })
    store.fetchDefinitions.mockImplementation(async () => {
      store.definitions = DEFINITIONS
      return store.definitions
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('CRIT-1: фильтр → карточка → «Назад» → фильтр и страница на месте', async () => {
    const router = makeRouter()
    await router.push('/processes/instances')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()

    // Первичная загрузка списка
    await vi.waitFor(() => expect(store.fetchInstances.mock.calls.length).toBeGreaterThan(0))

    // 1) Пользователь выбирает процесс в фильтре
    await openSelect(wrapper, 'definition-filter')
    await chooseSelectItem('definition-filter-vacation')
    await vi.waitFor(
      () => {
        const last = store.fetchInstances.mock.calls[store.fetchInstances.mock.calls.length - 1][0]
        expect(last.processDefinitionKey).toBe('vacation')
      },
      { timeout: 3000 },
    )

    // 2) Пользователь уходит на вторую страницу
    const nextBtn = wrapper.findAll('button').find((b) => b.text() === 'next')
    expect(nextBtn).toBeDefined()
    await nextBtn!.trigger('click')
    await vi.waitFor(() => {
      const last = store.fetchInstances.mock.calls[store.fetchInstances.mock.calls.length - 1][0]
      expect(last.pageIndex).toBe(1)
    })

    // Фильтр и страница живут в URL (ссылку можно скопировать)
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['instances.key']).toBe('vacation')
      expect(router.currentRoute.value.query['instances.page']).toBe('2')
    })

    // 3) Уход в карточку инстанса — список размонтируется
    await wrapper.find('tbody tr').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="detail"]').exists()).toBe(true))
    expect(router.currentRoute.value.name).toBe('process-instance-detail')

    // 4) «Назад» — список монтируется заново
    router.back()
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('tbody tr').exists()).toBe(true))
    await vi.waitFor(
      () => {
        const last = store.fetchInstances.mock.calls[store.fetchInstances.mock.calls.length - 1][0]
        expect(last.processDefinitionKey).toBe('vacation')
        expect(last.pageIndex).toBe(1)
      },
      { timeout: 3000 },
    )

    // URL на месте — состояние восстановлено из него
    expect(router.currentRoute.value.query['instances.key']).toBe('vacation')
    expect(router.currentRoute.value.query['instances.page']).toBe('2')

    wrapper.unmount()
  })

  it('CRIT-2: дефолты не попадают в URL (чистый URL)', async () => {
    const router = makeRouter()
    await router.push('/processes/instances')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchInstances.mock.calls.length).toBeGreaterThan(0))
    expect(router.currentRoute.value.query['instances.key']).toBeUndefined()
    expect(router.currentRoute.value.query['instances.page']).toBeUndefined()
    wrapper.unmount()
  })

  it('CRIT-3: битый параметр из URL → дефолт (список грузится, не падает)', async () => {
    const router = makeRouter()
    await router.push('/processes/instances?instances.key=nosuchkey&instances.page=abc')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchInstances.mock.calls.length).toBeGreaterThan(0))
    const last = store.fetchInstances.mock.calls[store.fetchInstances.mock.calls.length - 1][0]
    expect(last.pageIndex).toBe(0)
    // Нормализация URL: мусор убран
    await vi.waitFor(() => expect(router.currentRoute.value.query['instances.page']).toBeUndefined())
    wrapper.unmount()
  })

  it('CRIT-4: смена фильтра сбрасывает страницу (номер страницы уходит из URL)', async () => {
    const router = makeRouter()
    await router.push('/processes/instances?instances.page=3')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchInstances.mock.calls.length).toBeGreaterThan(0))
    // Прямая ссылка открыла 3-ю страницу
    const first = store.fetchInstances.mock.calls[0][0]
    expect(first.pageIndex).toBe(2)

    await openSelect(wrapper, 'definition-filter')
    await chooseSelectItem('definition-filter-vacation')
    await vi.waitFor(
      () => {
        const last = store.fetchInstances.mock.calls[store.fetchInstances.mock.calls.length - 1][0]
        expect(last.processDefinitionKey).toBe('vacation')
        expect(last.pageIndex).toBe(0)
      },
      { timeout: 3000 },
    )
    expect(router.currentRoute.value.query['instances.page']).toBeUndefined()
    wrapper.unmount()
  })
})
