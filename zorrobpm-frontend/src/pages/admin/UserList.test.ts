// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import UserList from './UserList.vue'

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
    expect(wrapper.text()).toContain('2 users')
  })

  it('renders user details when expanded', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    // Click Details button for first user
    const detailsBtns = wrapper.findAll('button').filter(b => b.text().includes('Details'))
    expect(detailsBtns.length).toBeGreaterThan(0)
    await detailsBtns[0].trigger('click')
    await wrapper.vm.$nextTick()

    // UserDetailPanel should be visible
    expect(wrapper.text()).toContain('API Key')
  })
})
