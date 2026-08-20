// @vitest-environment jsdom
// WO-ACL-10 criterion 22: no menu item may wrap, verified with the REAL ru/kz
// locale strings. t()-stubs like 'tasks' can never wrap, so any layout would
// pass with them; real Kazakh labels (two words) must still stay on one line —
// enforced here by truncate + min-w-0 on the label spans.
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import SidebarNav from './SidebarNav.vue'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/' }),
  useRouter: () => ({ push: vi.fn() }),
}))

const mockAuth = vi.hoisted(() => ({ isSuperAdmin: true }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isSuperAdmin: mockAuth.isSuperAdmin }),
}))

function makeI18n(locale: string) {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en',
    messages: { ru, en, kz },
  })
}

describe('SidebarNav WO-ACL-10 criterion 22', () => {
  it('kz labels render from the real locale and every label span stays single-line (truncate + min-w-0)', () => {
    const i18n = makeI18n('kz')
    const wrapper = mount(SidebarNav, { props: { collapsed: false }, global: { plugins: [i18n] } })
    const spans = wrapper.findAll('nav button span')
    expect(spans.length).toBeGreaterThanOrEqual(10)
    for (const s of spans) {
      // real kz string, not the t() stub key
      const key = Object.keys(kz).find(k => (kz as unknown as Record<string, string>)[k] === s.text())
      expect(key).toBeTruthy()
      expect(s.classes()).toContain('truncate')
      expect(s.classes()).toContain('min-w-0')
    }
  })

  it('ru labels render from the real locale and every label span stays single-line (truncate + min-w-0)', () => {
    const i18n = makeI18n('ru')
    const wrapper = mount(SidebarNav, { props: { collapsed: false }, global: { plugins: [i18n] } })
    const spans = wrapper.findAll('nav button span')
    expect(spans.length).toBeGreaterThanOrEqual(10)
    for (const s of spans) {
      const key = Object.keys(ru).find(k => (ru as unknown as Record<string, string>)[k] === s.text())
      expect(key).toBeTruthy()
      expect(s.classes()).toContain('truncate')
      expect(s.classes()).toContain('min-w-0')
    }
  })
})