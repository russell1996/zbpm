// @vitest-environment jsdom
/**
 * WO-ACL-11 criteria 6, 9 — ProcessDefinitionList:
 *  6 — the whole row opens the definition card on click;
 *  9 — focus + Enter opens it.
 * (The old "view" button is gone — covered by the whole-row click.)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ProcessDefinitionList from './ProcessDefinitionList.vue'

const mockAuth = vi.hoisted(() => ({ isSuperAdmin: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: mockAuth.isSuperAdmin, user: { username: 'alice' } }),
}))
vi.mock('@/services/processService', () => ({
  deployProcessDefinition: vi.fn().mockResolvedValue({ id: 'def-1' }),
  addProcessDefinitionVersion: vi.fn().mockResolvedValue({ id: 'def-2' }),
  getProcessDefinitions: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
}))
vi.mock('@/services/submissionService', () => ({
  submitProcessSubmission: vi.fn().mockResolvedValue({ id: 'sub-1' }),
  getMySubmissions: vi.fn().mockResolvedValue([
    { id: 'sub-1', processKey: 'vacation', name: 'Vacation', status: 'PENDING', submittedBy: 'alice', submittedAt: '2026-08-01T10:00:00Z', rejectReason: null, previousSubmissionId: null },
  ]),
}))
vi.mock('@/services/adminService', () => ({
  getMyMemberships: vi.fn().mockResolvedValue([]),
  listMembers: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))
vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    definitions: {
      data: [
        { id: 'def-1', name: 'Vacation', key: 'vacation', version: 1, createdAt: '2026-01-01' },
        { id: 'def-2', name: 'Onboarding', key: 'onboarding', version: 3, createdAt: '2026-01-02' },
      ],
      totalElements: 2,
    },
    loading: false,
    error: null,
    fetchDefinitions: vi.fn().mockResolvedValue(undefined),
  }),
}))
vi.mock('@/composables/usePagination', () => ({
  usePagination: () => ({ page: 0, pageSize: 20, nextPage: vi.fn(), prevPage: vi.fn(), hasNext: false, hasPrev: false, resetPage: vi.fn() }),
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
          { path: 'processes/definitions', name: 'process-definitions', component: { template: '<div />' } },
          { path: 'processes/definitions/:id', name: 'process-definition-detail', component: { template: '<div />' } },
        ],
      },
    ],
  })
}

describe('WO-ACL-11 criteria 6/9: ProcessDefinitionList rows', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('criterion 6: clicking the row opens the definition card', async () => {
    const router = makeRouter()
    await router.push('/processes/definitions')
    await router.isReady()
    const wrapper = mount(ProcessDefinitionList, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('tbody tr')[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/processes/definitions/def-1')
  })

  it('criterion 9: pressing Enter on the focused row opens the definition card', async () => {
    const router = makeRouter()
    await router.push('/processes/definitions')
    await router.isReady()
    const wrapper = mount(ProcessDefinitionList, { global: { plugins: [router] } })
    await flushPromises()

    const row = wrapper.findAll('tbody tr')[1]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/processes/definitions/def-2')
  })

  it('criterion 7: the "view" button is gone from the table', async () => {
    const wrapper = mount(ProcessDefinitionList)
    await flushPromises()
    expect(wrapper.text()).not.toContain('view')
  })

  it('criterion 32: "My Submissions" opens as a right-side DRAWER panel with the table inside', async () => {
    const wrapper = mount(ProcessDefinitionList, { attachTo: document.body })
    await flushPromises()
    // closed drawer: panel exists but is off-screen (translated away)
    expect(wrapper.get('[data-testid="drawer-panel"]').classes()).toContain('translate-x-full')
    const openBtn = wrapper.findAll('button').find((b) => b.text() === 'mySubmissions')!
    await openBtn.trigger('click')
    await flushPromises()
    const panel = wrapper.get('[data-testid="drawer-panel"]')
    // drawer: slides from the right, width capped, table lives inside it
    expect(panel.classes()).toContain('translate-x-0')
    // WO-ACL-14 criterion 20: wider panel for the 5-column submissions table
    expect(panel.classes()).toContain('sm:max-w-4xl')
    expect(wrapper.text()).toContain('vacation')
    // closing by Esc returns to the list state (panel leaves the screen)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.get('[data-testid="drawer-panel"]').classes()).toContain('translate-x-full')
    expect(document.body.style.overflow).toBe('')
    wrapper.unmount()
    document.body.innerHTML = ''
  })
})