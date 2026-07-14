// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { useRouter, useRoute } from 'vue-router'
import ChangePassword from '@/pages/ChangePassword.vue'
import { useAuthStore } from '@/stores/auth'

vi.mock('vue-router', () => ({
  useRouter: vi.fn(),
  useRoute: vi.fn(),
}))

vi.mock('@/services/userService', () => ({
  updateUser: vi.fn().mockResolvedValue({ id: 'test-id' }),
}))

describe('ChangePassword.vue', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    vi.clearAllMocks()
    pinia = setActivePinia(createPinia())
    vi.mocked(useRouter).mockReturnValue({ push: vi.fn() } as unknown as ReturnType<typeof useRouter>)
    vi.mocked(useRoute).mockReturnValue({ name: 'change-password' } as unknown as ReturnType<typeof useRoute>)
  })

  function mountComponent() {
    return mount(ChangePassword, {
      global: { plugins: [pinia] },
    })
  }

  it('renders the form with password inputs and submit button', () => {
    const wrapper = mountComponent()
    expect(wrapper.find('h1').text()).toBe('Change Password')
    expect(wrapper.find('#newPassword').exists()).toBe(true)
    expect(wrapper.find('#confirmPassword').exists()).toBe(true)
    expect(wrapper.find('button[type="submit"]').exists()).toBe(true)
  })

  it('shows mismatch error when passwords differ', async () => {
    const wrapper = mountComponent()
    await wrapper.find('#newPassword').setValue('newpass123')
    await wrapper.find('#confirmPassword').setValue('differentpass')
    expect(wrapper.text()).toContain('Passwords do not match')
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
})
