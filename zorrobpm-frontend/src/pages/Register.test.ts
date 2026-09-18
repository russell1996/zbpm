// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import Register from './Register.vue'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'

const mockRegister = vi.hoisted(() => vi.fn())
vi.mock('@/services/registrationService', () => ({ register: mockRegister }))
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))

function mountRegister() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/register', component: Register },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, ru, kz } })
  router.push('/register')
  return { router, i18n }
}

describe('Register.vue WO-REG-6', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockRegister.mockResolvedValue(undefined)
  })

  it('criterion1: successful registration shows confirmation, does not redirect', async () => {
    const { router, i18n } = mountRegister()
    await router.isReady()
    const wrapper = mount(Register, { global: { plugins: [router, i18n] } })

    await wrapper.find('[data-testid="register-username"]').setValue('newuser')
    await wrapper.find('[data-testid="register-email"]').setValue('newuser@example.com')
    await wrapper.find('[data-testid="register-password"]').setValue('a-genuinely-strong-Passphrase-42')
    const inputs = wrapper.findAll('input[type="password"]')
    // confirm is second password input
    await inputs[1].setValue('a-genuinely-strong-Passphrase-42')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(mockRegister).toHaveBeenCalledWith(
      expect.objectContaining({ username: 'newuser', email: 'newuser@example.com' }),
    )
    expect(wrapper.find('[data-testid="register-success"]').exists()).toBe(true)
    expect(wrapper.find('form').exists()).toBe(false)
    // No redirect to dashboard — still on /register
    expect(router.currentRoute.value.path).toBe('/register')
    wrapper.unmount()
  })

  it('criterion2: email already exists → message under email field, not generic alert', async () => {
    mockRegister.mockRejectedValue({
      response: { data: { message: 'Email already exists', code: 'EMAIL_ALREADY_EXISTS' } },
    })
    const { router, i18n } = mountRegister()
    await router.isReady()
    const wrapper = mount(Register, { global: { plugins: [router, i18n] } })

    await wrapper.find('[data-testid="register-username"]').setValue('dupeuser')
    await wrapper.find('[data-testid="register-email"]').setValue('dupe@example.com')
    await wrapper.find('[data-testid="register-password"]').setValue('a-genuinely-strong-Passphrase-42')
    const inputs = wrapper.findAll('input[type="password"]')
    await inputs[1].setValue('a-genuinely-strong-Passphrase-42')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('[data-testid="register-email-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="register-error"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
