// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 6, 9, 10 — MessageList:
 *  6  — the row navigates to the process instance on click (no subscription detail page);
 *  9  — focus + Enter does the same;
 *  10 — the instance id is visible/copyable via CopyableId, never truncated text.
 * WO-ACL-14 criteria 6, 17 — MessageList:
 *  6  — the status icon is rendered INSIDE StatusBadge (with-icon);
 *  17 — the id is shown in full in the cell (length=8 truncation is gone).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import MessageList from './MessageList.vue'

const mockGetSubscriptions = vi.fn()
vi.mock('@/services/messageService', () => ({
  getMessageSubscriptions: (...args: any[]) => mockGetSubscriptions(...args),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ error: vi.fn(), success: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

const LONG_INSTANCE = 'inst-4394c7b1-aaaa-4000-8000-000000000001'
const MESSAGES = [
  { id: 'msg-1', messageName: 'order.created', processInstanceId: LONG_INSTANCE, correlationKey: 'k1', consumed: false, createdAt: '2026-01-01' },
  { id: 'msg-2', messageName: 'order.completed', processInstanceId: null, correlationKey: null, consumed: true, createdAt: '2026-01-02' },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [
          { path: 'messages', name: 'messages', component: { template: '<div />' } },
          { path: 'processes/instances/:id', name: 'process-instance-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-ACL-11 criteria 6/9/10: MessageList rows', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetSubscriptions.mockResolvedValue({ data: MESSAGES, totalElements: 2 })
  })

  it('criterion 6: row click navigates to the process instance', async () => {
    const router = makeRouter()
    await router.push('/messages')
    await router.isReady()
    const wrapper = mount(MessageList, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(`/processes/instances/${LONG_INSTANCE}`)
  })

  it('criterion 9: Enter on the row navigates to the process instance', async () => {
    const router = makeRouter()
    await router.push('/messages')
    await router.isReady()
    const wrapper = mount(MessageList, { global: { plugins: [router] } })
    await flushPromises()

    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(`/processes/instances/${LONG_INSTANCE}`)
  })

  it('subscription without instance id is not navigable', async () => {
    const router = makeRouter()
    await router.push('/messages')
    await router.isReady()
    const wrapper = mount(MessageList, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('tbody tr')[1].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/messages')
  })

  it('criterion 17: the instance id is a CopyableId shown in FULL (no ellipsis)', async () => {
    const wrapper = mount(MessageList, { global: { plugins: [makeRouter()] } })
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    const instSpan = firstRow.findAll('span.group').find((s) => s.text().includes('inst-439'))
    expect(instSpan).toBeDefined()
    expect(instSpan!.text()).toContain(LONG_INSTANCE)
    expect(instSpan!.text()).not.toContain('...')
  })

  it('criterion 6 (WO-ACL-14): the status badge carries its icon inside', async () => {
    const wrapper = mount(MessageList, { global: { plugins: [makeRouter()] } })
    await flushPromises()

    // row 0: consumed=false → WAITING → clock icon inside the pill
    const waiting = wrapper.findAll('tbody tr')[0].find('span.rounded-full svg.lucide-clock')
    expect(waiting.exists()).toBe(true)
    // row 1: consumed=true → CONSUMED → check-circle icon inside the pill
    const consumed = wrapper.findAll('tbody tr')[1].find('span.rounded-full svg.lucide-circle-check-big')
    expect(consumed.exists()).toBe(true)
  })
})