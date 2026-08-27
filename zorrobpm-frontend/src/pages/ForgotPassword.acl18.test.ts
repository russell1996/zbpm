// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ForgotPassword from '@/pages/ForgotPassword.vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))
vi.mock('@/services/userService', () => ({
  requestPasswordReset: vi.fn().mockResolvedValue(undefined),
}))

describe('ForgotPassword WO-ACL-18', () => {
  beforeEach(() => vi.clearAllMocks())

  it('criterion12: success message is shown after submitting an email', async () => {
    const { requestPasswordReset } = await import('@/services/userService')
    const wrapper = mount(ForgotPassword, {
      global: { stubs: { RouterLink: { template: '<a><slot/></a>' } } },
    })
    await wrapper.find('input[type="email"]').setValue('someone@example.com')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(requestPasswordReset).toHaveBeenCalledWith('someone@example.com')
    expect(wrapper.find('[data-testid="forgot-password-success"]').exists()).toBe(true)
  })

  it('criterion12: success message is shown even if the request fails (enumeration-safe)', async () => {
    const { requestPasswordReset } = await import('@/services/userService')
    vi.mocked(requestPasswordReset).mockRejectedValueOnce(new Error('network'))
    const wrapper = mount(ForgotPassword, {
      global: { stubs: { RouterLink: { template: '<a><slot/></a>' } } },
    })
    await wrapper.find('input[type="email"]').setValue('unknown@example.com')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    // The UI must never reveal whether the account exists.
    expect(wrapper.find('[data-testid="forgot-password-success"]').exists()).toBe(true)
  })
})
