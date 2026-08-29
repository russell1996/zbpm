// @vitest-environment jsdom
// jsdom lacks PointerEvent capture APIs that reka-ui's SelectTrigger calls on pointerdown.
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import UserDetailPanel from '@/pages/admin/UserDetailPanel.vue'
import * as userService from '@/services/userService'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))
vi.mock('@/services/adminService', () => ({
  listUserMemberships: vi.fn().mockResolvedValue([]),
  listProcesses: vi.fn().mockResolvedValue([]),
  getApiKey: vi.fn().mockResolvedValue(null),
  setGrants: vi.fn(),
  addMember: vi.fn(),
  changeMemberRole: vi.fn(),
  removeMember: vi.fn(),
  createApiKey: vi.fn(),
  rotateApiKey: vi.fn(),
  revokeApiKey: vi.fn(),
}))
vi.mock('@/services/userService', () => ({
  adminResetPassword: vi.fn().mockResolvedValue(undefined),
}))

function user(userType: 'HUMAN' | 'SYSTEM') {
  return {
    id: 'u1',
    username: 'alice',
    fullName: 'A',
    email: 'a@x.com',
    role: 'USER' as const,
    active: true,
    forcePasswordChange: false,
    userType,
    createdAt: '',
    updatedAt: '',
  }
}

describe('UserDetailPanel WO-ACL-18', () => {
  beforeEach(() => vi.clearAllMocks())

  // reka teleports Select content to <body>; clear leftovers so option queries stay scoped.
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('criterion10: shows a reset-password button for a HUMAN user and calls adminResetPassword', async () => {
    const wrapper = mount(UserDetailPanel, { props: { user: user('HUMAN') } })
    await flushPromises()
    const btn = wrapper.find('[data-testid="reset-password-button"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    await flushPromises()
    expect(userService.adminResetPassword).toHaveBeenCalledWith('u1')
  })

  it('criterion14: no reset-password button for a SYSTEM account', async () => {
    const wrapper = mount(UserDetailPanel, { props: { user: user('SYSTEM') } })
    await flushPromises()
    expect(wrapper.find('[data-testid="reset-password-button"]').exists()).toBe(false)
  })
})
