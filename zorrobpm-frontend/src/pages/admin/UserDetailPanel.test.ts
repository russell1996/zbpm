// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import UserDetailPanel from './UserDetailPanel.vue'

vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn(),
  listUserMemberships: vi.fn().mockResolvedValue([
    { userId: 'u1', username: 'user1', role: 'OWNER', processKey: 'proc-a', addedBy: null, addedAt: '' },
    { userId: 'u1', username: 'user1', role: 'DESIGNER', processKey: 'proc-b', addedBy: null, addedAt: '' },
  ]),
  addMember: vi.fn(),
  removeMember: vi.fn(),
  getApiKey: vi.fn().mockRejectedValue({ response: { status: 404 } }),
  createApiKey: vi.fn(),
  rotateApiKey: vi.fn(),
  revokeApiKey: vi.fn(),
  setGrants: vi.fn(),
  listProcesses: vi.fn().mockResolvedValue([
    { id: 'p1', key: 'proc-a', name: 'Process A' },
    { id: 'p2', key: 'proc-b', name: 'Process B' },
  ]),
}))

const mockUser = {
  id: 'u1',
  username: 'testuser',
  fullName: 'Test',
  email: null,
  role: 'USER' as const,
  active: true,
  createdAt: '',
  updatedAt: '',
}

describe('UserDetailPanel', () => {
  it('renders memberships list from backend', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })
    // Wait for async load to complete
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    expect(wrapper.text()).toContain('proc-b')
    expect(wrapper.text()).toContain('OWNER')
    expect(wrapper.text()).toContain('DESIGNER')
  })

  it('has add membership form', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })
    await wrapper.vm.$nextTick()

    // Form should be present (hidden initially, toggle via button)
    expect(wrapper.text()).toContain('Process Memberships')

    // Add button exists
    const buttons = wrapper.findAll('button')
    const addBtn = buttons.find(b => b.text().includes('Add'))
    expect(addBtn).toBeDefined()
  })
})
