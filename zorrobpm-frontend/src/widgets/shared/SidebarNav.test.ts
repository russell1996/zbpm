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
  it('renders the Forms menu item (WO-FORM-6 — route existed, menu item was missing)', () => {
    const wrapper = mount(SidebarNav)
    // navItems includes { labelKey: 'forms', to: '/admin/forms' } → label 'forms' rendered
    expect(wrapper.text()).toContain('forms')
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
})
