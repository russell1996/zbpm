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

  // NOTE: reka-ui's DismissableLayer dismisses on outside *pointer* events and on
  // item *selection* — both rely on real pointer/focus semantics that jsdom cannot
  // simulate with @vue/test-utils' synthetic `trigger('click')` (no preceding
  // pointerdown). Those close paths are native to reka-ui (and exercised in a real
  // browser). Here we assert the close paths that ARE drivable in jsdom:
  // trigger toggle and Escape.

  it('criterion 4: clicking the trigger again closes the dropdown', async () => {
    const wrapper = mount(HeaderBar, { attachTo: document.body } as any)
    const btn = wrapper.find('[aria-haspopup="menu"]')
    await btn.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('myProfile')

    // reka-ui toggles the menu on trigger activation
    await btn.trigger('click')
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

  it('menu shows My Profile and Logout items when open', async () => {
    const wrapper = mount(HeaderBar, { attachTo: document.body } as any)
    const btn = wrapper.find('[aria-haspopup="menu"]')
    await btn.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('myProfile')
    expect(wrapper.text()).toContain('logout')
  })
})
