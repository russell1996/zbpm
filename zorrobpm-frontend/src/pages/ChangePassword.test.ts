// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { useRouter, useRoute } from 'vue-router'
import ChangePassword from '@/pages/ChangePassword.vue'
import { useAuthStore } from '@/stores/auth'
import * as userService from '@/services/userService'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('vue-router', () => ({
  useRouter: vi.fn(),
  useRoute: vi.fn(),
}))

vi.mock('@/services/userService', () => ({
  // WO-SEC-58 HOLD-fix: the locked-out screen uses the self-service endpoint.
  // The old contract (updateUser → PUT /users/{id}) is SUPER_ADMIN-only and
  // returned 403 for exactly the users this screen serves (P-65).
  changeMyPassword: vi.fn().mockResolvedValue({ id: 'test-id' }),
}))

describe('ChangePassword.vue', () => {
  let pinia: ReturnType<typeof createPinia>
  let pushMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.clearAllMocks()
    pinia = setActivePinia(createPinia())
    pushMock = vi.fn()
    vi.mocked(useRouter).mockReturnValue({ push: pushMock } as unknown as ReturnType<typeof useRouter>)
    vi.mocked(useRoute).mockReturnValue({ name: 'change-password' } as unknown as ReturnType<typeof useRoute>)
  })

  function mountComponent() {
    return mount(ChangePassword, {
      global: { plugins: [pinia] },
    })
  }

  function setupForcedUser() {
    const auth = useAuthStore()
    auth.user = {
      id: 'user-123',
      username: 'forcedAdmin',
      fullName: 'Forced Admin',
      email: null,
      role: 'SUPER_ADMIN',
      active: true,
      forcePasswordChange: true,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
  }

  it('renders current + new + confirm inputs and submit button', () => {
    const wrapper = mountComponent()
    expect(wrapper.find('h1').text()).toBe('changePassword')
    expect(wrapper.find('#currentPassword').exists()).toBe(true)
    expect(wrapper.find('#newPassword').exists()).toBe(true)
    expect(wrapper.find('#confirmPassword').exists()).toBe(true)
    expect(wrapper.find('button[type="submit"]').exists()).toBe(true)
  })

  it('shows mismatch error when passwords differ', async () => {
    const wrapper = mountComponent()
    await wrapper.find('#newPassword').setValue('newpass123456')
    await wrapper.find('#confirmPassword').setValue('differentpass12')
    expect(wrapper.text()).toContain('passwordsDoNotMatch')
  })

  it('submit button disabled when passwords mismatch', async () => {
    const wrapper = mountComponent()
    await wrapper.find('#newPassword').setValue('newpass123456')
    await wrapper.find('#confirmPassword').setValue('differentpass12')
    const btn = wrapper.find('button[type="submit"]')
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('submit button disabled when password is empty', async () => {
    const wrapper = mountComponent()
    const btn = wrapper.find('button[type="submit"]')
    expect(btn.attributes('disabled')).toBeDefined()
  })

  // --- Criterion #10 (UI path): successful submit → changeMyPassword(current,new)
  // → refreshUser → navigate to dashboard.
  //
  // Proof-of-failure for the WO-SEC-58 HOLD fix: revert ChangePassword.vue to the
  // old updateUser(...) call and this test goes RED ("changeMyPassword was never
  // called") — the same mutation that broke real users in production.

  it('criterion10: submit calls changeMyPassword(current, new), refreshes user, navigates to dashboard', async () => {
    setupForcedUser()
    const auth = useAuthStore()

    // Mock refreshUser to simulate backend resetting forcePasswordChange
    auth.refreshUser = vi.fn(async () => {
      auth.user = { ...auth.user!, forcePasswordChange: false }
    })

    const wrapper = mountComponent()
    await wrapper.find('#currentPassword').setValue('TempPass!2026')
    await wrapper.find('#newPassword').setValue('NewSecure123!')
    await wrapper.find('#confirmPassword').setValue('NewSecure123!')

    await wrapper.find('form').trigger('submit')

    // 1. self-service endpoint called with BOTH fields — identity comes from JWT
    expect(userService.changeMyPassword).toHaveBeenCalledWith('TempPass!2026', 'NewSecure123!')

    // 2. refreshUser was called (which set forcePasswordChange to false)
    expect(auth.refreshUser).toHaveBeenCalled()

    // 3. router.push to dashboard after successful change
    expect(pushMock).toHaveBeenCalledWith({ name: 'dashboard' })
  })

  it('criterion10: server error surfaces inside the form (no navigation)', async () => {
    setupForcedUser()
    vi.mocked(userService.changeMyPassword).mockRejectedValueOnce({
      response: { data: { message: 'Current password is incorrect' } },
    })
    const auth = useAuthStore()
    auth.refreshUser = vi.fn(async () => {})

    const wrapper = mountComponent()
    await wrapper.find('#currentPassword').setValue('WrongCurrent!1')
    await wrapper.find('#newPassword').setValue('NewSecure123!')
    await wrapper.find('#confirmPassword').setValue('NewSecure123!')

    await wrapper.find('form').trigger('submit')

    expect(wrapper.text()).toContain('Current password is incorrect')
    expect(pushMock).not.toHaveBeenCalled()
  })
})
