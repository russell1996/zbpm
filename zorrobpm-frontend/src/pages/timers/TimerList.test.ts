// @vitest-environment jsdom
/**
 * WO-FE-19 (regression): timers and their process-instance navigation.
 * WO-ACL-11 criteria 6, 9, 10 — TimerList:
 *  6  — the row navigates to the process instance on click (no timer detail page);
 *  9  — focus + Enter does the same;
 *  10 — the full id is visible/copyable via CopyableId, never truncated to `4394c7b1…`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import TimerList from './TimerList.vue'

const mockGetTimerJobs = vi.fn()

vi.mock('@/services/timerService', () => ({
  getTimerJobs: (...args: any[]) => mockGetTimerJobs(...args),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ error: vi.fn(), success: vi.fn() }),
}))

vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v, formatDate: (v: string) => v }),
}))

vi.mock('@/shared/lib/export', () => ({
  exportToCsv: vi.fn(),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'timers', name: 'timers', component: { template: '<div />' } },
          { path: 'processes/instances/:id', name: 'process-instance-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

const LONG_ID = '4394c7b1-aaaa-4000-8000-000000000001'
const TIMERS = [
  { id: LONG_ID, processInstanceId: 'inst-aaa-bbb-ccc', activityId: 'act-1', dueAt: '2026-01-01', fired: false, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-01' },
  { id: 'timer-2', processInstanceId: null, activityId: 'act-only', dueAt: '2026-01-02', fired: true, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-02' },
  { id: 'timer-3', processInstanceId: null, activityId: null, dueAt: '2026-01-03', fired: false, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-03' },
]

describe('TimerList: instance navigation and full id (WO-ACL-11 criteria 6/9/10)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockGetTimerJobs.mockResolvedValue({ data: TIMERS, totalElements: 3 })
  })

  it('criterion 6: timer row with processInstanceId navigates to the instance on click', async () => {
    const router = makeRouter()
    await router.push('/timers')
    await router.isReady()

    const wrapper = mount(TimerList, { global: { plugins: [router, createPinia()] } })
    await flushPromises()

    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/processes/instances/inst-aaa-bbb-ccc')
  })

  it('criterion 9: pressing Enter on the timer row navigates to the instance', async () => {
    const router = makeRouter()
    await router.push('/timers')
    await router.isReady()

    const wrapper = mount(TimerList, { global: { plugins: [router, createPinia()] } })
    await flushPromises()

    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/processes/instances/inst-aaa-bbb-ccc')
  })

  it('timer without processInstanceId is not navigable (no detail page)', async () => {
    const router = makeRouter()
    await router.push('/timers')
    await router.isReady()

    const wrapper = mount(TimerList, { global: { plugins: [router, createPinia()] } })
    await flushPromises()

    const noInstanceRow = wrapper.findAll('tbody tr')[1]
    await noInstanceRow.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/timers')
  })

  it('timer with neither processInstanceId nor activityId shows dash', async () => {
    const wrapper = mount(TimerList, { global: { plugins: [makeRouter(), createPinia()] } })
    await flushPromises()

    const spans = wrapper.findAll('span')
    const dashSpan = spans.find((s) => s.text() === '—')
    expect(dashSpan).toBeDefined()
  })

  it('criterion 10: the long id is truncated in display but the full value appears on click (CopyableId)', async () => {
    const wrapper = mount(TimerList, { global: { plugins: [makeRouter(), createPinia()] } })
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    const idSpan = firstRow.find('span.group')
    expect(idSpan.exists()).toBe(true)
    // display is truncated (length=8)
    expect(idSpan.text()).not.toContain(LONG_ID)
    expect(idSpan.text()).toContain('...')
    // clicking the CopyableId reveals the FULL id without navigating
    await idSpan.trigger('click')
    await flushPromises()
    expect(idSpan.text()).toContain(LONG_ID)
  })
})