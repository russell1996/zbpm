// @vitest-environment jsdom
/**
 * WO-UI-39: список определений процессов помнит поиск и страницу при переходе
 * в карточку определения и возврате «Назад».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import ProcessDefinitionList from './ProcessDefinitionList.vue'

const DEFINITIONS = [
  { id: 'd1', key: 'vacation', name: 'Vacation Leave', version: 1, sha256: 'a', createdAt: '2026-01-01', startFormKey: null, archived: false },
  { id: 'd2', key: 'invoice', name: 'Invoice Approval', version: 1, sha256: 'b', createdAt: '2026-01-05', startFormKey: null, archived: false },
]

vi.mock('@/stores/process', async () => {
  const { reactive } = await import('vue')
  const store = reactive({
    definitions: null as { data: unknown[]; totalElements: number } | null,
    instances: null,
    loading: false,
    error: null as string | null,
    fetchDefinitions: vi.fn(),
    fetchInstances: vi.fn(),
  })
  return { useProcessStore: () => store }
})

vi.mock('@/services/adminService', () => ({ getMyMemberships: vi.fn().mockResolvedValue([]) }))
vi.mock('@/services/processService', () => ({ archiveProcess: vi.fn(), unarchiveProcess: vi.fn() }))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v, formatDate: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }),
}))

import { useProcessStore } from '@/stores/process'

const store = useProcessStore() as unknown as {
  definitions: { data: unknown[]; totalElements: number } | null
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
          { path: 'processes/definitions', name: 'process-definitions', component: ProcessDefinitionList },
          {
            path: 'processes/definitions/:id',
            name: 'process-definition-detail',
            component: defineComponent({ setup: () => () => h('div', { 'data-testid': 'detail' }, 'detail') }),
          },
        ],
      },
    ],
  })
}

describe('WO-UI-39: ProcessDefinitionList помнит поиск и страницу после «Назад»', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    document.body.innerHTML = ''
    store.definitions = null
    store.fetchDefinitions.mockImplementation(async () => {
      store.definitions = { data: DEFINITIONS, totalElements: 25 }
      return store.definitions
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('CRIT-1: поиск → карточка → «Назад» → поиск и страница на месте', async () => {
    const router = makeRouter()
    await router.push('/processes/definitions')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchDefinitions.mock.calls.length).toBeGreaterThan(0))

    // 1) Печатаем в поиск
    const search = wrapper.find('input[type="text"]')
    expect(search.exists()).toBe(true)
    await search.setValue('invoice')
    await vi.waitFor(
      () => {
        const last = store.fetchDefinitions.mock.calls[store.fetchDefinitions.mock.calls.length - 1][0]
        expect(last.name).toBe('invoice')
      },
      { timeout: 3000 },
    )

    // 2) Листаем на вторую страницу
    const nextBtn = wrapper.findAll('button').find((b) => b.text() === 'next')
    expect(nextBtn).toBeDefined()
    await nextBtn!.trigger('click')
    await vi.waitFor(() => {
      const last = store.fetchDefinitions.mock.calls[store.fetchDefinitions.mock.calls.length - 1][0]
      expect(last.pageIndex).toBe(1)
    })

    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['definitions.search']).toBe('invoice')
      expect(router.currentRoute.value.query['definitions.page']).toBe('2')
    })

    // 3) Уход в карточку определения
    await wrapper.find('tbody tr').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="detail"]').exists()).toBe(true))

    // 4) «Назад» — поиск и страница на месте
    router.back()
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('tbody tr').exists()).toBe(true))
    await vi.waitFor(
      () => {
        const last = store.fetchDefinitions.mock.calls[store.fetchDefinitions.mock.calls.length - 1][0]
        expect(last.name).toBe('invoice')
        expect(last.pageIndex).toBe(1)
      },
      { timeout: 3000 },
    )
    expect(router.currentRoute.value.query['definitions.search']).toBe('invoice')
    expect(router.currentRoute.value.query['definitions.page']).toBe('2')

    wrapper.unmount()
  })

  it('CRIT-2: дефолты не попадают в URL (чистый URL)', async () => {
    const router = makeRouter()
    await router.push('/processes/definitions')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchDefinitions.mock.calls.length).toBeGreaterThan(0))
    expect(router.currentRoute.value.query['definitions.search']).toBeUndefined()
    expect(router.currentRoute.value.query['definitions.latest']).toBeUndefined()
    expect(router.currentRoute.value.query['definitions.page']).toBeUndefined()
    wrapper.unmount()
  })

  it('CRIT-3: битый параметр из URL → дефолт', async () => {
    const router = makeRouter()
    await router.push('/processes/definitions?definitions.page=abc&definitions.archived=maybe')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router, createPinia()] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(store.fetchDefinitions.mock.calls.length).toBeGreaterThan(0))
    const last = store.fetchDefinitions.mock.calls[store.fetchDefinitions.mock.calls.length - 1][0]
    expect(last.pageIndex).toBe(0)
    expect(last.includeArchived).toBeUndefined()
    await vi.waitFor(() => {
      expect(router.currentRoute.value.query['definitions.page']).toBeUndefined()
      expect(router.currentRoute.value.query['definitions.archived']).toBeUndefined()
    })
    wrapper.unmount()
  })
})
