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
  updateUser: vi.fn().mockResolvedValue({ id: 'test-id' }),
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

  it('renders the form with password inputs and submit button', () => {
    const wrapper = mountComponent()
    expect(wrapper.find('h1').text()).toBe('changePassword')
    expect(wrapper.find('#newPassword').exists()).toBe(true)
    expect(wrapper.find('#confirmPassword').exists()).toBe(true)
    expect(wrapper.find('button[type="submit"]').exists()).toBe(true)
  })

  it('shows mismatch error when passwords differ', async () => {
    const wrapper = mountComponent()
    await wrapper.find('#newPassword').setValue('newpass123')
    await wrapper.find('#confirmPassword').setValue('differentpass')
    expect(wrapper.text()).toContain('passwordsDoNotMatch')
  })

  it('submit button disabled when passwords mismatch', async () => {
    const wrapper = mountComponent()
    await wrapper.find('#newPassword').setValue('newpass123')
    await wrapper.find('#confirmPassword').setValue('differentpass')
    const btn = wrapper.find('button[type="submit"]')
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('submit button disabled when password is empty', async () => {
    const wrapper = mountComponent()
    const btn = wrapper.find('button[type="submit"]')
    expect(btn.attributes('disabled')).toBeDefined()
  })

  // --- Criterion #2: successful submit → updateUser + refreshUser + navigate ---

  it('criterion2: successful submit calls updateUser, refreshUser, then navigates to dashboard', async () => {
    setupForcedUser()
    const auth = useAuthStore()

    // Mock refreshUser to simulate backend resetting forcePasswordChange
    const origRefreshUser = auth.refreshUser.bind(auth)
    auth.refreshUser = vi.fn(async () => {
      auth.user = { ...auth.user!, forcePasswordChange: false }
    })

    const wrapper = mountComponent()
    await wrapper.find('#newPassword').setValue('NewSecure123!')
    await wrapper.find('#confirmPassword').setValue('NewSecure123!')

    await wrapper.find('form').trigger('submit')

    // 1. updateUser called with auth.user.id and the new password
    expect(userService.updateUser).toHaveBeenCalledWith('user-123', {
      fullName: 'Forced Admin',
      email: null,
      role: 'SUPER_ADMIN',
      active: true,
      password: 'NewSecure123!',
    })

    // 2. refreshUser was called (which set forcePasswordChange to false)
    expect(auth.refreshUser).toHaveBeenCalled()

    // 3. router.push to dashboard after successful change
    expect(pushMock).toHaveBeenCalledWith({ name: 'dashboard' })
  })
})
