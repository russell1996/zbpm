// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import VerifyEmail from './VerifyEmail.vue'
import en from '@/locales/en.json'
import ru from '@/locales/ru.json'
import kz from '@/locales/kz.json'

const mockVerifyEmail = vi.hoisted(() => vi.fn())
vi.mock('@/services/registrationService', () => ({ verifyEmail: mockVerifyEmail }))

function mountVerify(token: string | null) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/verify-email', component: VerifyEmail },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, ru, kz } })
  const query = token ? `?token=${token}` : ''
  router.push(`/verify-email${query}`)
  return { router, i18n }
}

describe('VerifyEmail.vue WO-REG-6', () => {
  it('criterion3: valid token shows success', async () => {
    mockVerifyEmail.mockResolvedValue(undefined)
    const { router, i18n } = mountVerify('valid-tok-123')
    await router.isReady()
    const wrapper = mount(VerifyEmail, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(mockVerifyEmail).toHaveBeenCalledWith('valid-tok-123')
    expect(wrapper.find('[data-testid="verify-email-success"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('criterion3: invalid token shows error', async () => {
    mockVerifyEmail.mockRejectedValue({ response: { data: { message: 'Invalid or expired token' } } })
    const { router, i18n } = mountVerify('bad-tok')
    await router.isReady()
    const wrapper = mount(VerifyEmail, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="verify-email-error"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
