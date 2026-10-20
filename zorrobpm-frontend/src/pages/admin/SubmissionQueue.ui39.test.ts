// @vitest-environment jsdom
/**
 * WO-UI-39: фильтр статуса очереди заявок живёт в query-параметрах маршрута.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import SubmissionQueue from './SubmissionQueue.vue'

const getPendingSubmissions = vi.hoisted(() => vi.fn())
vi.mock('@/services/submissionService', () => ({
  getPendingSubmissions,
  approveSubmission: vi.fn().mockResolvedValue({}),
  rejectSubmission: vi.fn().mockResolvedValue({}),
  getSubmissionBpmn: vi.fn(),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v, formatDate: (v: string) => v }),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }),
}))
vi.mock('@/widgets/bpmn/BpmnViewer.vue', () => ({
  default: { template: '<div class="bpmn-stub" />' },
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [
      {
        path: '/',
        component: { template: '<router-view />' },
        children: [{ path: 'admin/submissions', name: 'admin-submissions', component: SubmissionQueue }],
      },
    ],
  })
}

describe('WO-UI-39: SubmissionQueue — фильтр статуса в query-параметрах', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
    getPendingSubmissions.mockResolvedValue({ data: [], totalElements: 0 })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  async function mountQueue(url: string) {
    const router = makeRouter()
    await router.push(url)
    await router.isReady()
    const wrapper = mount(
      defineComponent({ setup: () => () => h(RouterView) }),
      { global: { plugins: [router] } },
    )
    await flushPromises()
    await vi.waitFor(() => expect(getPendingSubmissions.mock.calls.length).toBeGreaterThan(0))
    return { router, wrapper }
  }

  it('CRIT-1: статус из URL применяется (прямая ссылка)', async () => {
    const { router, wrapper } = await mountQueue('/admin/submissions?submissions.status=APPROVED')
    expect(getPendingSubmissions.mock.calls[getPendingSubmissions.mock.calls.length - 1][0]).toBe('APPROVED')
    expect(router.currentRoute.value.query['submissions.status']).toBe('APPROVED')
    wrapper.unmount()
  })

  it('CRIT-2: дефолт (PENDING) не попадает в URL (чистый URL)', async () => {
    const { router, wrapper } = await mountQueue('/admin/submissions')
    expect(getPendingSubmissions.mock.calls[getPendingSubmissions.mock.calls.length - 1][0]).toBe('PENDING')
    expect(router.currentRoute.value.query['submissions.status']).toBeUndefined()
    wrapper.unmount()
  })

  it('CRIT-3: битый параметр из URL → дефолт', async () => {
    const { router, wrapper } = await mountQueue('/admin/submissions?submissions.status=WHATEVER')
    expect(getPendingSubmissions.mock.calls[getPendingSubmissions.mock.calls.length - 1][0]).toBe('PENDING')
    await vi.waitFor(() => expect(router.currentRoute.value.query['submissions.status']).toBeUndefined())
    wrapper.unmount()
  })

  it('CRIT-4: переключение вкладки статуса пишется в URL', async () => {
    const { router, wrapper } = await mountQueue('/admin/submissions')
    const tabs = wrapper.findAll('[role="tab"]')
    const rejected = tabs.find((t) => t.text() === 'submissionStatusRejected')
    expect(rejected).toBeDefined()
    await rejected!.trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.query['submissions.status']).toBe('REJECTED'))
    expect(getPendingSubmissions.mock.calls[getPendingSubmissions.mock.calls.length - 1][0]).toBe('REJECTED')
    wrapper.unmount()
  })
})
