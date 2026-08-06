// @vitest-environment jsdom
/**
 * WO-FE-19: Timer list — processInstanceId as clickable link
 *
 * - Timer with processInstanceId → <router-link> to process-instance-detail
 * - Timer without processInstanceId (activityId only) → plain text, no link
 * - Timer with neither → '—'
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

const TIMERS = [
  { id: 'timer-1', processInstanceId: 'inst-aaa-bbb-ccc', activityId: 'act-1', dueAt: '2026-01-01', fired: false, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-01' },
  { id: 'timer-2', processInstanceId: null, activityId: 'act-only', dueAt: '2026-01-02', fired: true, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-02' },
  { id: 'timer-3', processInstanceId: null, activityId: null, dueAt: '2026-01-03', fired: false, boundaryElementId: null, eventSubprocessId: null, createdAt: '2026-01-03' },
]

describe('WO-FE-19: TimerList process instance link', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockGetTimerJobs.mockResolvedValue({ data: TIMERS, totalElements: 3 })
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 1: timer with processInstanceId → clickable link
  // ─────────────────────────────────────────────────────────────
  it('CRIT-1: timer with processInstanceId renders router-link to process-instance-detail', async () => {
    const router = makeRouter()
    await router.push('/timers')
    await router.isReady()

    const wrapper = mount(TimerList, {
      global: { plugins: [router, createPinia()] },
    })
    await flushPromises()

    const links = wrapper.findAll('a')
    const instanceLink = links.find((a) => a.text().includes('inst-aaa'))
    expect(instanceLink).toBeDefined()
    expect(instanceLink!.attributes('href')).toContain('/processes/instances/inst-aaa-bbb-ccc')
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 2: timer without processInstanceId → plain text, no link
  // ─────────────────────────────────────────────────────────────
  it('CRIT-2: timer with activityId only renders plain text (no link)', async () => {
    const router = makeRouter()
    await router.push('/timers')
    await router.isReady()

    const wrapper = mount(TimerList, {
      global: { plugins: [router, createPinia()] },
    })
    await flushPromises()

    // The activityId-only timer should show as plain text
    const spans = wrapper.findAll('span')
    const actSpan = spans.find((s) => s.text().includes('act-only'))
    expect(actSpan).toBeDefined()
    // Should NOT be wrapped in an <a> tag
    expect(actSpan!.element.tagName).not.toBe('A')
  })

  // ─────────────────────────────────────────────────────────────
  // Criterion 3: timer with neither → dash
  // ─────────────────────────────────────────────────────────────
  it('CRIT-3: timer with no processInstanceId and no activityId shows dash', async () => {
    const router = makeRouter()
    await router.push('/timers')
    await router.isReady()

    const wrapper = mount(TimerList, {
      global: { plugins: [router, createPinia()] },
    })
    await flushPromises()

    const spans = wrapper.findAll('span')
    const dashSpan = spans.find((s) => s.text() === '—')
    expect(dashSpan).toBeDefined()
  })

  // ─────────────────────────────────────────────────────────────
  // Regression: link has correct route params
  // ─────────────────────────────────────────────────────────────
  it('REGRESSION: link includes full processInstanceId in route', async () => {
    const router = makeRouter()
    await router.push('/timers')
    await router.isReady()

    const wrapper = mount(TimerList, {
      global: { plugins: [router, createPinia()] },
    })
    await flushPromises()

    const link = wrapper.find('a')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toContain('inst-aaa-bbb-ccc')
  })
})
