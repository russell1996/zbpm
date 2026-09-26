// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProcessDefinitionList from './ProcessDefinitionList.vue'

// DeploySection is rendered inside the list dialog — its own deps must be mocked
// here too (vitest module mocks are per test file).
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
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }),
}))
vi.mock('@/stores/process', () => ({
  useProcessStore: () => ({
    definitions: { data: [], totalElements: 0 },
    loading: false,
    error: null,
    fetchDefinitions: vi.fn().mockResolvedValue(undefined),
  }),
}))
vi.mock('@/composables/usePagination', () => ({
  usePagination: () => ({ page: 0, pageSize: 20, nextPage: vi.fn(), prevPage: vi.fn(), hasNext: false, hasPrev: false, resetPage: vi.fn() }),
}))
vi.mock('@/shared/lib/export', () => ({ exportToCsv: vi.fn() }))

describe('ProcessDefinitionList (WO-ACL-6 criterion 4 / WO-ACL-10 criterion 3)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('WO-ACL-10 criterion 3: upload is a header button; no inline upload section on the page', () => {
    const wrapper = mount(ProcessDefinitionList)
    const buttons = wrapper.findAll('button')
    expect(buttons.some((b) => b.text().includes('uploadProcess'))).toBe(true)
    // the inline section is gone — the page text has no drop zone or BPMN hint
    expect(wrapper.text()).not.toContain('dropBpmn')
  })

  it('WO-ACL-10 criterion 3: clicking the header button opens the upload dialog', async () => {
    const wrapper = mount(ProcessDefinitionList)
    const openBtn = wrapper.findAll('button').find((b) => b.text().includes('uploadProcess'))!
    await openBtn.trigger('click')
    await flushPromises()
    // the dialog contains the upload section content
    expect(wrapper.text()).toContain('dropBpmn')
  })

  it('the empty list still renders the table below the header (upload no longer sits above it)', () => {
    const wrapper = mount(ProcessDefinitionList)
    const h1 = wrapper.find('h1')
    expect(h1.exists()).toBe(true)
    // table placeholder text is present
    expect(wrapper.text()).toContain('noDefinitions')
  })
})