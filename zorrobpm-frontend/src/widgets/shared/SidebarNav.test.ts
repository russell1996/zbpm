// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
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

describe('SidebarNav', () => {
  it('Forms menu item removed — form/schema editing now lives inside Process Definitions detail', () => {
    const wrapper = mount(SidebarNav)
    // forms was superseded by the per-process SchemaEditorPanel (same pattern as processSchemas below)
    const buttons = wrapper.findAll('button')
    const formsBtn = buttons.find(b => b.text().includes('forms'))
    expect(formsBtn).toBeFalsy()
  })

  it('still renders existing items (users) — no regression', () => {
    const wrapper = mount(SidebarNav)
    expect(wrapper.text()).toContain('users')
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

  it('WO-ACL-6: My Submissions item present (criterion 6)', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('mySubmissions'))).toBe(true)
  })

  it('WO-ACL-6: Submission Queue item present (criterion 6)', () => {
    const wrapper = mount(SidebarNav)
    const buttons = wrapper.findAll('button')
    expect(buttons.some(b => b.text().includes('submissionQueue'))).toBe(true)
  })
})
