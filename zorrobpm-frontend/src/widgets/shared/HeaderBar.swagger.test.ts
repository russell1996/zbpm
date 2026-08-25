// @vitest-environment jsdom
/**
 * WO-FE-23: Swagger link in UI
 *
 * - HeaderBar renders a link to /swagger-ui/index.html
 * - Link opens in new tab with rel="noopener noreferrer"
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HeaderBar from './HeaderBar.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/app/i18n', () => ({
  setLocale: vi.fn(),
}))

vi.mock('@/components/ui/sidebar', () => ({
  SidebarTrigger: { template: '<div class="sidebar-trigger-stub" />' },
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: null, logout: vi.fn() }),
}))

vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({ darkMode: false, toggleDarkMode: vi.fn() }),
}))

describe('WO-FE-23: Swagger link in HeaderBar', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('CRIT-1: HeaderBar renders a link to /swagger-ui/index.html', () => {
    const wrapper = mount(HeaderBar, {
      global: { plugins: [createPinia()] },
      stubs: { teleport: true },
    })

    const link = wrapper.find('a[href="/swagger-ui/index.html"]')
    expect(link.exists()).toBe(true)
  })

  it('CRIT-2: link opens in new tab with rel="noopener noreferrer"', () => {
    const wrapper = mount(HeaderBar, {
      global: { plugins: [createPinia()] },
      stubs: { teleport: true },
    })

    const link = wrapper.find('a[href="/swagger-ui/index.html"]')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
  })

  it('CRIT-2: link has title attribute for accessibility', () => {
    const wrapper = mount(HeaderBar, {
      global: { plugins: [createPinia()] },
      stubs: { teleport: true },
    })

    const link = wrapper.find('a[href="/swagger-ui/index.html"]')
    expect(link.attributes('title')).toBe('apiDocs')
  })
})
