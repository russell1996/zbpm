// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import UserDetailPanel from './UserDetailPanel.vue'

vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
  getApiKey: vi.fn().mockRejectedValue({ response: { status: 404 } }),
  createApiKey: vi.fn(),
  rotateApiKey: vi.fn(),
  revokeApiKey: vi.fn(),
  setGrants: vi.fn().mockResolvedValue([]),
  listProcesses: vi.fn().mockResolvedValue([]),
}))

import { createApiKey, rotateApiKey } from '@/services/adminService'

/**
 * WO-MT-9b criterion #2: admin-created key must NOT persist in state after modal close.
 */
describe('UserDetailPanel — admin key state isolation', () => {
  const mockUser = { id: 'u1', username: 'testuser', fullName: 'Test', email: null, role: 'USER' as const, active: true, createdAt: '', updatedAt: '' }

  beforeEach(() => {
    vi.mocked(createApiKey).mockResolvedValue({
      id: 'k1', ownerUserId: 'u1', prefix: 'zbpm_sk_abc', key: 'zbpm_sk_admin_secret_123',
      createdAt: '', lastUsedAt: null, expiresAt: null, revokedAt: null, grants: [],
    })
    vi.mocked(rotateApiKey).mockResolvedValue({
      id: 'k1', ownerUserId: 'u1', prefix: 'zbpm_sk_xyz', key: 'zbpm_sk_rotated_secret_456',
      createdAt: '', lastUsedAt: null, expiresAt: null, revokedAt: null, grants: [],
    })
  })

  it('clears displayedKey when admin closes key modal (key never stays in state)', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })

    await wrapper.vm.$nextTick()

    const vm = wrapper.vm as any

    // Simulate admin creating a key
    vm.showCreateKey = true
    await vm.createApiKey()
    await wrapper.vm.$nextTick()

    // Key IS shown
    expect(vm.displayedKey).toBe('zbpm_sk_admin_secret_123')
    expect(vm.showKeyModal).toBe(true)

    // Close modal
    vm.closeKeyModal()
    await wrapper.vm.$nextTick()

    // Key MUST be cleared
    expect(vm.displayedKey).toBe('')
    expect(vm.showKeyModal).toBe(false)
  })

  it('clears displayedKey after rotate too', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })

    await wrapper.vm.$nextTick()

    // Simulate admin rotating key
    await (wrapper.vm as any).rotateKey()
    await wrapper.vm.$nextTick()

    expect((wrapper.vm as any).displayedKey).toBe('zbpm_sk_rotated_secret_456')
    expect((wrapper.vm as any).showKeyModal).toBe(true)

    ;(wrapper.vm as any).closeKeyModal()
    await wrapper.vm.$nextTick()

    expect((wrapper.vm as any).displayedKey).toBe('')
  })
})
