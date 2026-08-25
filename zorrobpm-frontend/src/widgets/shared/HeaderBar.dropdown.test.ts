// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HeaderBar from './HeaderBar.vue'

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: { id: 'u-1', username: 'test', fullName: 'Test User' }, logout: vi.fn() }),
}))
vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({ darkMode: false, toggleDarkMode: vi.fn() }),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('@/app/i18n', () => ({ setLocale: vi.fn() }))
vi.mock('@/components/ui/sidebar', () => ({
  SidebarTrigger: { template: '<div class="sidebar-trigger-stub" />' },
}))

describe('HeaderBar dropdown — WO-UI-5 criteria 4/5', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('criterion 4: clicking outside closes the dropdown', async () => {
    const wrapper = mount(HeaderBar, { attachTo: document.body } as any)
    const btn = wrapper.find('[aria-haspopup="menu"]')
    await btn.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('myProfile')

    // click outside (document body, not inside rootEl)
    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('myProfile')
  })

  it('criterion 5: Escape closes the dropdown', async () => {
    const wrapper = mount(HeaderBar, { attachTo: document.body } as any)
    const btn = wrapper.find('[aria-haspopup="menu"]')
    await btn.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('myProfile')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('myProfile')
  })

  it('clicking inside dropdown does not close it via outside handler', async () => {
    const wrapper = mount(HeaderBar, { attachTo: document.body } as any)
    const btn = wrapper.find('[aria-haspopup="menu"]')
    await btn.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('myProfile')
    // clicking the link itself should close via its own @click="userMenuOpen = false"
    const link = wrapper.find('a')
    expect(link.exists()).toBe(true)
    await link.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('myProfile')
  })
})
