// @vitest-environment jsdom
/**
 * WO-FE-19 (regression): timers and their process-instance navigation.
 * WO-ACL-11 criteria 6, 9, 10 — TimerList:
 *  6  — the row navigates to the process instance on click (no timer detail page);
 *  9  — focus + Enter does the same;
 *  10 — the full id is visible/copyable via CopyableId, never truncated to `4394c7b1…`.
 * WO-ACL-14 criteria 6, 17 — TimerList:
 *  6  — the status icon is rendered INSIDE StatusBadge (with-icon);
 *  17 — the id is shown in full in the cell (the length=8 truncation is gone:
 *       a UUID was already hard to read truncated, and activityId is a BPMN
 *       identifier like `Activity_1abc` — cutting it to 8 chars destroyed it).
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

  it('criterion 17: the id is shown in FULL in the cell (no length truncation), full value in title', async () => {
    const wrapper = mount(TimerList, { global: { plugins: [makeRouter(), createPinia()] } })
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    const idSpan = firstRow.find('span.group')
    expect(idSpan.exists()).toBe(true)
    // WO-ACL-14: display holds the WHOLE id, no "…" ellipsis
    expect(idSpan.text()).toContain(LONG_ID)
    expect(idSpan.text()).not.toContain('...')
    // and the title still carries the full value for the copy interaction
    expect(idSpan.attributes('title')).toBe(LONG_ID)
  })

  it('criterion 6: the status badge carries its icon inside (with-icon)', async () => {    const wrapper = mount(TimerList, { global: { plugins: [makeRouter(), createPinia()] } })
    await flushPromises()

    // row 0: fired=false → WAITING → clock icon inside the badge pill
    const waiting = wrapper.findAll('tbody tr')[0].find('span.rounded-full svg.lucide-clock')
    expect(waiting.exists()).toBe(true)
    // row 1: fired=true → FIRED → check-circle icon inside the pill
    const fired = wrapper.findAll('tbody tr')[1].find('span.rounded-full svg.lucide-circle-check-big')
    expect(fired.exists()).toBe(true)
  })

  it('WO-UI-17 F24: Export calls exportToCsv with the mapped rows (no shadowed-t crash)', async () => {
    // Regression: the map callback was named `t`, shadowing i18n `t`, so
    // `t('fired')` invoked the timer row as a function — every Export click
    // threw TypeError. vue-tsc caught it as TS2349; this test pins the runtime.
    const { exportToCsv } = await import('@/shared/lib/export')
    const wrapper = mount(TimerList, { global: { plugins: [makeRouter(), createPinia()] } })
    await flushPromises()

    const exportBtn = wrapper.findAll('button').find((b) => b.text() === 'export')
    expect(exportBtn?.exists()).toBe(true)
    await exportBtn!.trigger('click')
    await flushPromises()

    expect(exportToCsv).toHaveBeenCalledTimes(1)
    expect(exportToCsv).toHaveBeenCalledWith(
      [
        { id: LONG_ID, processInstanceId: 'inst-aaa-bbb-ccc', activityId: 'act-1', dueAt: '2026-01-01', status: 'pending', createdAt: '2026-01-01' },
        { id: 'timer-2', processInstanceId: '', activityId: 'act-only', dueAt: '2026-01-02', status: 'fired', createdAt: '2026-01-02' },
        { id: 'timer-3', processInstanceId: '', activityId: '', dueAt: '2026-01-03', status: 'pending', createdAt: '2026-01-03' },
      ],
      'timers.csv',
    )
  })
})