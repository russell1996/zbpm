// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ProcessDefinitionList from './ProcessDefinitionList.vue'

// DeploySection is rendered inside the list — its own deps must be mocked here too
// (vitest module mocks are per test file).
const mockAuth = vi.hoisted(() => ({ isSuperAdmin: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: mockAuth.isSuperAdmin }),
}))
vi.mock('@/services/processService', () => ({
  deployProcessDefinition: vi.fn().mockResolvedValue({ id: 'def-1' }),
}))
vi.mock('@/services/submissionService', () => ({
  submitProcessSubmission: vi.fn().mockResolvedValue({ id: 'sub-1' }),
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

describe('ProcessDefinitionList (WO-ACL-6 criterion 4)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('upload lives INSIDE the definitions list (uploadProcess section is rendered)', () => {
    const wrapper = mount(ProcessDefinitionList)
    expect(wrapper.text()).toContain('uploadProcess')
  })

  it('the empty list still renders the upload section above the table', () => {
    const wrapper = mount(ProcessDefinitionList)
    const h1 = wrapper.find('h1')
    const sectionIndex = wrapper.text().indexOf('uploadProcess')
    const noDefinitionsIndex = wrapper.text().indexOf('noDefinitions')
    expect(h1.exists()).toBe(true)
    // upload section comes before the (empty) table
    expect(sectionIndex).toBeGreaterThan(0)
    expect(noDefinitionsIndex).toBeGreaterThan(sectionIndex)
  })
})