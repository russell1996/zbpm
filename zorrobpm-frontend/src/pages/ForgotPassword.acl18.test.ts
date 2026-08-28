// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ForgotPassword from '@/pages/ForgotPassword.vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))
vi.mock('@/services/userService', () => ({
  requestPasswordReset: vi.fn().mockResolvedValue(undefined),
}))

describe('ForgotPassword WO-ACL-18 / WO-ACL-19', () => {
  beforeEach(() => vi.clearAllMocks())

  it('criterion12: success message is shown after a successful submit', async () => {
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

  it('criterion12: enumeration-safe — success is shown for a non-existent account (server returns 200)', async () => {
    const { requestPasswordReset } = await import('@/services/userService')
    // The backend returns 200 for unknown emails (enumeration-safe), so the UI never sees a rejection.
    vi.mocked(requestPasswordReset).mockResolvedValueOnce(undefined)
    const wrapper = mount(ForgotPassword, {
      global: { stubs: { RouterLink: { template: '<a><slot/></a>' } } },
    })
    await wrapper.find('input[type="email"]').setValue('unknown@example.com')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-testid="forgot-password-success"]').exists()).toBe(true)
  })

  it('WO-ACL-19 criterion2: a real transport/5xx error is shown as an error, not masked as success', async () => {
    const { requestPasswordReset } = await import('@/services/userService')
    vi.mocked(requestPasswordReset).mockRejectedValueOnce(new Error('network'))
    const wrapper = mount(ForgotPassword, {
      global: { stubs: { RouterLink: { template: '<a><slot/></a>' } } },
    })
    await wrapper.find('input[type="email"]').setValue('someone@example.com')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    // The failure must be visible to the user/support (not silently turned into success).
    expect(wrapper.find('[data-testid="forgot-password-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="forgot-password-success"]').exists()).toBe(false)
  })
})
