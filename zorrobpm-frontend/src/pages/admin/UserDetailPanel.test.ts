// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import UserDetailPanel from './UserDetailPanel.vue'
import * as admin from '@/services/adminService'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

const toastError = vi.fn()
const toastSuccess = vi.fn()
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: toastSuccess, error: toastError, info: vi.fn() }),
}))

vi.mock('@/services/adminService', () => ({
  listMembers: vi.fn(),
  listUserMemberships: vi.fn().mockResolvedValue([
    { userId: 'u1', username: 'user1', fullName: null, email: null, role: 'OWNER', processKey: 'proc-a', addedBy: null, addedAt: '' },
  ]),
  addMember: vi.fn(),
  removeMember: vi.fn(),
  getApiKey: vi.fn().mockRejectedValue({ response: { status: 404 } }),
  createApiKey: vi.fn(),
  rotateApiKey: vi.fn(),
  revokeApiKey: vi.fn(),
  setGrants: vi.fn(),
  changeMemberRole: vi.fn().mockResolvedValue({}),
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
  beforeEach(() => {
    vi.clearAllMocks()
    // Default: no active key
    vi.mocked(admin.getApiKey).mockRejectedValue({ response: { status: 404 } })
    // Default memberships: proc-a only
    vi.mocked(admin.listUserMemberships).mockResolvedValue([
      { userId: 'u1', username: 'user1', fullName: null, email: null, role: 'OWNER', processKey: 'proc-a', addedBy: null, addedAt: '' },
    ])
  })

  it('renders memberships list from backend', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    expect(wrapper.text()).toContain('ownerRole')
  })

  it('has add membership form', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('processMemberships')

    const buttons = wrapper.findAll('button')
    const addBtn = buttons.find(b => b.text().includes('add'))
    expect(addBtn).toBeDefined()
  })

  it('Create button visible when key is revoked', async () => {
    vi.mocked(admin.getApiKey).mockResolvedValue({
      revokedAt: '2024-01-01',
      grants: [],
      id: 'k1',
      ownerUserId: 'u1',
      prefix: 'zbpm_sk_test...',
      createdAt: '',
      lastUsedAt: null,
      expiresAt: null,
    } as any)

    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('createNewKey')
    }, { timeout: 2000 })

    expect(wrapper.text()).toContain('revoked')
  })

  it('Role change on membership row calls changeMemberRole', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    // Find the role select for proc-a row
    const selects = wrapper.findAll('select')
    // The first select is the role dropdown in the memberships table
    const roleSelect = selects.find(s => s.element.value === 'OWNER')
    expect(roleSelect).toBeDefined()

    await roleSelect!.setValue('DESIGNER')
    await wrapper.vm.$nextTick()

    expect(admin.changeMemberRole).toHaveBeenCalledWith('proc-a', 'u1', 'DESIGNER')
  })

  it('Grant checkboxes visible when active key', async () => {
    vi.mocked(admin.getApiKey).mockResolvedValue({
      revokedAt: null,
      grants: [{ processId: 'p1', processKey: 'proc-a', permissions: 'START', full: false }],
      id: 'k1',
      ownerUserId: 'u1',
      prefix: 'zbpm_sk_test...',
      createdAt: '',
      lastUsedAt: null,
      expiresAt: null,
    } as any)

    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    // Checkbox for "Full" should be visible
    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    expect(checkboxes.length).toBeGreaterThan(0)
    // "Full" label should be present (i18n mock returns key)
    expect(wrapper.text()).toContain('full')
  })

  it('Add membership selects only non-member processes', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    // Open add membership form
    const addBtn = wrapper.findAll('button').find(b => b.text().includes('add'))
    await addBtn!.trigger('click')
    await wrapper.vm.$nextTick()

    // Find the process select in the add form
    const selects = wrapper.findAll('select')
    const processSelect = selects.find(s => s.find('option[value="proc-b"]'))
    expect(processSelect).toBeDefined()

    // Should NOT have proc-a option (already a member)
    const options = processSelect!.findAll('option')
    const optionValues = options.map(o => o.element.value)
    expect(optionValues).not.toContain('proc-a')
    expect(optionValues).toContain('proc-b')
  })

  it('409 toast on addMember conflict', async () => {
    toastError.mockClear()

    vi.mocked(admin.addMember).mockRejectedValue({ response: { status: 409 } })

    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: true } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    // Open add membership form
    const addBtn = wrapper.findAll('button').find(b => b.text().includes('add'))
    await addBtn!.trigger('click')
    await wrapper.vm.$nextTick()

    // Select a process
    const selects = wrapper.findAll('select')
    const processSelect = selects.find(s => s.find('option[value="proc-b"]'))
    await processSelect!.setValue('proc-b')

    // Click Add
    const submitBtn = wrapper.findAll('button').find(b => b.text() === 'add')
    await submitBtn!.trigger('click')
    await wrapper.vm.$nextTick()

    // Wait for async error handling
    await vi.waitFor(() => {
      expect(toastError).toHaveBeenCalled()
    }, { timeout: 2000 })

    expect(toastError).toHaveBeenCalledWith('alreadyMember')
  })
})
