// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import UserList from './UserList.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/services/userService', () => ({
  getUsers: vi.fn().mockResolvedValue({
    data: [
      { id: 'u1', username: 'alice', fullName: 'Alice', email: 'alice@test.com', role: 'ADMIN', active: true, createdAt: '', updatedAt: '' },
      { id: 'u2', username: 'bob', fullName: 'Bob', email: null, role: 'USER', active: false, createdAt: '', updatedAt: '' },
    ],
    totalElements: 2,
  }),
  createUser: vi.fn(),
  updateUser: vi.fn(),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}))

describe('UserList render', () => {
  it('renders users without error (regression for undefined user.id bug)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.text()).toContain('usersCount')
  })

  it('WO-ACL-11 criterion 6: clicking the ROW expands the user detail panel (the Details button is gone)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    // the old "details" button must be gone
    expect(wrapper.text()).not.toContain('details')
    await wrapper.findAll('tbody tr')[0].trigger('click')
    await wrapper.vm.$nextTick()

    // UserDetailPanel should be visible (i18n mock returns key)
    expect(wrapper.text()).toContain('apiKey')
  })

  it('WO-ACL-11 criterion 8: clicking Edit does NOT expand the panel', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    const editBtn = wrapper.findAll('button').find((b) => b.text() === 'edit')!
    await editBtn.trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('apiKey')
  })

  it('WO-ACL-11 criterion 9: Enter on the focused row expands the user detail panel', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('apiKey')
  })
})
