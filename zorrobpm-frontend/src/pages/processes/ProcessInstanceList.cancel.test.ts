// @vitest-environment jsdom
/**
 * WO-UI-21 Раунд 2: отменённый instance показывает CANCELLED в списке,
 * а не Completed. Естественно завершённый остаётся Completed, активный —
 * Running. Дериватор — processInstanceStatus (cancelled-first).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ProcessInstanceList from './ProcessInstanceList.vue'

const fetchInstances = vi.hoisted(() => vi.fn())
const fetchDefinitions = vi.hoisted(() => vi.fn())

// cancelled-строка — как её реально отдаёт бэкенд: cancelled=true ПЛЮС
// completedAt (ProcessInstanceDbOperationsImpl ставит оба).
const INSTANCES_FIXTURE = {
  data: [
    { id: 'pi-cancelled', parentActivityId: null, processDefinitionId: 'pd-1', processName: 'Vacation', processKey: 'vacation', processVersion: 1, startedAt: '2026-01-01', completedAt: '2026-02-01', cancelled: true },
    { id: 'pi-completed', parentActivityId: null, processDefinitionId: 'pd-1', processName: 'Vacation', processKey: 'vacation', processVersion: 1, startedAt: '2026-01-01', completedAt: '2026-02-01', cancelled: false },
    { id: 'pi-running', parentActivityId: null, processDefinitionId: 'pd-1', processName: 'Vacation', processKey: 'vacation', processVersion: 1, startedAt: '2026-01-01', completedAt: null, cancelled: false },
  ],
  totalElements: 3,
}

vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    instances: INSTANCES_FIXTURE,
    definitions: { data: [], totalElements: 0 },
    loading: false,
    error: null,
    fetchInstances,
    fetchDefinitions,
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
          { path: 'processes/instances', name: 'process-instances', component: { template: '<div />' } },
          { path: 'processes/instances/:id', name: 'process-instance-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-UI-21 Round 2: ProcessInstanceList CANCELLED status', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  async function mountList() {
    const router = makeRouter()
    await router.push('/processes/instances')
    await router.isReady()
    const wrapper = mount(ProcessInstanceList, { global: { plugins: [router] } })
    await flushPromises()
    return wrapper
  }

  it('CRIT-1(list): cancelled=true renders CANCELLED, not Completed', async () => {
    const wrapper = await mountList()
    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(3)
    // t() возвращает ключ — StatusBadge CANCELLED рендерит t('statusCancelled').
    expect(rows[0].text()).toContain('statusCancelled')
    expect(rows[0].text()).not.toContain('statusCompleted')
  })

  it('CRIT-1(list): naturally completed stays Completed, active stays Running', async () => {
    const wrapper = await mountList()
    const rows = wrapper.findAll('tbody tr')
    expect(rows[1].text()).toContain('statusCompleted')
    expect(rows[1].text()).not.toContain('statusCancelled')
    expect(rows[2].text()).toContain('running')
  })
})
