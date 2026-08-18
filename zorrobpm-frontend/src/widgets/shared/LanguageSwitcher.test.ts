// @vitest-environment jsdom
/**
 * WO-FE-22: Login locale switcher
 *
 * - LanguageSwitcher.vue exists and renders language buttons
 * - Login.vue renders LanguageSwitcher
 * - HeaderBar.vue still renders LanguageSwitcher (regression)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import LanguageSwitcher from './LanguageSwitcher.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/app/i18n', () => ({
  setLocale: vi.fn(),
}))

describe('WO-FE-22: LanguageSwitcher', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders language switcher button with Globe icon', () => {
    const wrapper = mount(LanguageSwitcher, {
      global: { plugins: [createPinia()] },
    })

    // Should have a button with the Globe icon
    const button = wrapper.find('button')
    expect(button.exists()).toBe(true)
    // Button text shows current language label
    expect(button.text()).toContain('English')
  })

  it('clicking the button opens language dropdown', async () => {
    const wrapper = mount(LanguageSwitcher, {
      global: { plugins: [createPinia()] },
    })

    const button = wrapper.find('button')
    await button.trigger('click')
    await wrapper.vm.$nextTick()

    // Dropdown should show 3 language options
    const options = wrapper.findAll('button')
    // 1 toggle + 3 language buttons = 4
    expect(options.length).toBe(4)
  })

  it('clicking a language option calls setLocale', async () => {
    const { setLocale } = await import('@/app/i18n')
    const wrapper = mount(LanguageSwitcher, {
      global: { plugins: [createPinia()] },
    })

    // Open dropdown
    await wrapper.find('button').trigger('click')
    await wrapper.vm.$nextTick()

    // Click Russian
    const options = wrapper.findAll('button')
    const ruOption = options.find((b) => b.text().includes('Русский'))
    expect(ruOption).toBeDefined()
    await ruOption!.trigger('click')

    expect(setLocale).toHaveBeenCalledWith('ru')
  })

  // --- WO-ACL-10 criterion 11: outside click / Escape close, listeners removed ---

  it('criterion 11: an outside click closes the open menu', async () => {
    const wrapper = mount(LanguageSwitcher, {
      global: { plugins: [createPinia()] },
    })
    await wrapper.find('button').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('button').length).toBe(4) // open

    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('button').length).toBe(1) // closed
  })

  it('criterion 11: Escape closes the open menu', async () => {
    const wrapper = mount(LanguageSwitcher, {
      global: { plugins: [createPinia()] },
    })
    await wrapper.find('button').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('button').length).toBe(4)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('button').length).toBe(1)
  })

  it('criterion 11: a click inside the component does NOT close the menu', async () => {
    const wrapper = mount(LanguageSwitcher, {
      global: { plugins: [createPinia()] },
    })
    await wrapper.find('button').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('button').length).toBe(4)

    // click on the component root itself (no toggle involved) — stays open
    wrapper.element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('button').length).toBe(4)
  })

  it('criterion 11: document listeners are removed on unmount', () => {
    const removeSpy = vi.spyOn(document, 'removeEventListener')
    const wrapper = mount(LanguageSwitcher, {
      global: { plugins: [createPinia()] },
    })
    wrapper.unmount()
    expect(removeSpy).toHaveBeenCalledWith('click', expect.any(Function))
    expect(removeSpy).toHaveBeenCalledWith('keydown', expect.any(Function))
    removeSpy.mockRestore()
  })
})
