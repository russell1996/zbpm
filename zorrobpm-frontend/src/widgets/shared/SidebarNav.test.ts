// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SidebarNav from './SidebarNav.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

// t returns the key, so nav labels render as their labelKey
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

// WO-ACL-8 criteria 1-2: admin-only items are visible to super-admins only.
const mockAuth = vi.hoisted(() => ({ isSuperAdmin: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: mockAuth.isSuperAdmin }),
}))

describe('SidebarNav', () => {
  beforeEach(() => {
    mockAuth.isSuperAdmin = false
  })

  it('Forms menu item removed — form/schema editing now lives inside Process Definitions detail', () => {
    const wrapper = mount(SidebarNav)
    // forms was superseded by the per-process SchemaEditorPanel (same pattern as processSchemas below)
    const buttons = wrapper.findAll('button')
    const formsBtn = buttons.find(b => b.text().includes('forms'))
    expect(formsBtn).toBeFalsy()
  })

  it('WO-ACL-8 criterion 1: a non-super-admin does NOT see Users', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('users'))).toBe(false)
  })

  it('WO-ACL-8 criterion 1: a non-super-admin does NOT see Submission Queue', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('submissionQueue'))).toBe(false)
  })

  it('WO-ACL-8 criterion 2: a super-admin DOES see both admin items', () => {
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('users'))).toBe(true)
    expect(buttons.some(b => b.text().includes('submissionQueue'))).toBe(true)
  })

  it('Process Schemas menu item removed — now inside definitions detail (WO-VM-12)', () => {
    const wrapper = mount(SidebarNav)
    // processSchemas was moved into ProcessDefinitionDetail, so it should NOT be in the sidebar
    const buttons = wrapper.findAll('button')
    const processSchemasBtn = buttons.find(b => b.text().includes('processSchemas'))
    expect(processSchemasBtn).toBeFalsy()
  })

  it('WO-ACL-6 criterion 4: standalone "Deploy Process" menu item removed — upload lives inside definitions', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    const deployBtn = buttons.find(b => b.text().includes('deploy'))
    expect(deployBtn).toBeFalsy()
  })

  it('WO-ACL-8 criterion 10: My Submissions removed from sidebar (now a dialog on definitions page)', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('mySubmissions'))).toBe(false)
  })

  it('WO-ACL-6: Submission Queue item present for super-admin (criterion 6)', () => {
    mockAuth.isSuperAdmin = true
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('submissionQueue'))).toBe(true)
  })
})
