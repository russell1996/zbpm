// @vitest-environment jsdom
/**
 * WO-OBS-4: observability shortcut icons in HeaderBar (shared ZBPM login).
 *
 * - Grafana (/grafana/) and Prometheus (/prometheus/) icons render next to the
 *   API-docs icon, same <a target="_blank"> primitive, SUPER_ADMIN-only
 *   (cosmetic gate; real enforcement is nginx auth_request + Grafana whitelist
 *   + the hard nginx role check on /prometheus/).
 * - Non-super-admin sees neither; the ungated API-docs link is unaffected.
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

const mockSuper = vi.hoisted(() => ({ value: false }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: null, isSuperAdmin: mockSuper.value, logout: vi.fn() }),
}))

vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({ darkMode: false, toggleDarkMode: vi.fn() }),
}))

function mountBar() {
  return mount(HeaderBar, {
    global: { plugins: [createPinia()] },
    stubs: { teleport: true },
  })
}

describe('WO-OBS-4: observability icons in HeaderBar', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockSuper.value = false
  })

  it('super-admin sees the Grafana icon (new tab, no opener)', () => {
    mockSuper.value = true
    const link = mountBar().find('a[href="/grafana/"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    expect(link.attributes('title')).toBe('monitoring')
  })

  it('super-admin sees the Prometheus icon (new tab, no opener)', () => {
    mockSuper.value = true
    const link = mountBar().find('a[href="/prometheus/"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    expect(link.attributes('title')).toBe('metrics')
  })

  it('non-super-admin sees neither icon, API-docs link unaffected', () => {
    const wrapper = mountBar()
    expect(wrapper.find('a[href="/grafana/"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/prometheus/"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/swagger-ui/index.html"]').exists()).toBe(true)
  })
})
