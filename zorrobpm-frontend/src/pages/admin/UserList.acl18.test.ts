// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import UserList from '@/pages/admin/UserList.vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))
vi.mock('@/services/userService', () => ({
  getUsers: vi.fn().mockResolvedValue({ data: [], totalElements: 0 }),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  adminResetPassword: vi.fn(),
}))

function pendingUser(pending: boolean) {
  return {
    id: 'u1',
    username: 'alice',
    fullName: null,
    email: 'a@x.com',
    role: 'USER' as const,
    active: true,
    forcePasswordChange: false,
    pendingInvitation: pending,
    createdAt: '',
    updatedAt: '',
  }
}

describe('UserList WO-ACL-18', () => {
  beforeEach(() => vi.clearAllMocks())

  it('criterion5: shows an "invited" badge when the account has a pending invitation', async () => {
    const { getUsers } = await import('@/services/userService')
    vi.mocked(getUsers).mockResolvedValue({ data: [pendingUser(true)], totalElements: 1, pageIndex: 0, pageSize: 100 })
    const wrapper = mount(UserList)
    await flushPromises()
    const badge = wrapper.find('[data-testid="pending-invitation-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('invited')
  })

  it('criterion5: no badge once the invitation has been accepted', async () => {
    const { getUsers } = await import('@/services/userService')
    vi.mocked(getUsers).mockResolvedValue({ data: [pendingUser(false)], totalElements: 1, pageIndex: 0, pageSize: 100 })
    const wrapper = mount(UserList)
    await flushPromises()
    expect(wrapper.find('[data-testid="pending-invitation-badge"]').exists()).toBe(false)
  })
})
