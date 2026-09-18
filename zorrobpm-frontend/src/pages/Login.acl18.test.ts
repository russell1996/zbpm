// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Login from '@/pages/Login.vue'

const routerPush = vi.fn()
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k }),
  createI18n: () => ({ global: { t: (k: string) => k }, mode: 'legacy' }),
}))
vi.mock('@/features/auth/useAuth', () => ({
  useAuth: () => ({ login: vi.fn(), isLoading: false, error: null }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: routerPush, replace: vi.fn() }),
}))

describe('Login WO-ACL-18', () => {
  it('criterion12: offers a link to the forgot-password screen', async () => {
    routerPush.mockClear()
    const wrapper = mount(Login, {
      global: { stubs: { LanguageSwitcher: true } },
    })
    const link = wrapper.find('[data-testid="forgot-password-link"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toContain('forgotPasswordTitle')
    await link.trigger('click')
    expect(routerPush).toHaveBeenCalledWith('/forgot-password')
  })
})
