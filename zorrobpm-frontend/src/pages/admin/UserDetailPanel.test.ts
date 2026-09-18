// @vitest-environment jsdom
// jsdom lacks PointerEvent capture APIs that reka-ui's SelectTrigger calls on pointerdown.
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
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
  // WO-UI-17 F24: required by the User interface (vue-tsc checks test props too).
  forcePasswordChange: false,
  createdAt: '',
  updatedAt: '',
}

// reka Select opens on a left-button pointerdown of the trigger; the menu content is portaled
// to <body>, so options must be queried from document, not from `wrapper`.
async function nextTickFlush() {
  await new Promise((r) => setTimeout(r, 0))
}
async function openSelect(wrapper: ReturnType<typeof mount>, triggerTestid: string) {
  const trigger = wrapper.find(`[data-testid="${triggerTestid}"]`)
  // reka's SelectTrigger opens on a plain left pointerdown (event.button === 0 && ctrlKey === false);
  // VTU's trigger() cannot set the read-only `button`, so dispatch a real MouseEvent.
  trigger.element.dispatchEvent(
    new MouseEvent('pointerdown', { button: 0, ctrlKey: false, bubbles: true, cancelable: true }),
  )
  await nextTickFlush()
}
async function chooseSelectItem(_wrapper: ReturnType<typeof mount>, itemTestid: string) {
  const item = document.querySelector(`[data-testid="${itemTestid}"]`)
  expect(item).not.toBeNull()
  ;(item as HTMLElement).click()
  await nextTickFlush()
}
function optionTexts(): string[] {
  return Array.from(document.querySelectorAll('[role="option"]')).map((o) => o.textContent ?? '')
}

describe('UserDetailPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(admin.getApiKey).mockRejectedValue({ response: { status: 404 } })
    vi.mocked(admin.listUserMemberships).mockResolvedValue([
      { userId: 'u1', username: 'user1', fullName: null, email: null, role: 'OWNER', processKey: 'proc-a', addedBy: null, addedAt: '' },
    ])
  })

  // reka teleports Select/Dialog content to <body>; leftovers from a prior test would pollute
  // document.querySelectorAll('[role="option"]') and cause a stale option to be clicked.
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders memberships list from backend', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: false } },
    })
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    // membership row renders the process key and the role sub-header
    expect(wrapper.text()).toContain('role')
  })

  it('has add membership form', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: false } },
    })
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    const buttons = wrapper.findAll('button')
    const addBtn = buttons.find((b) => b.text().includes('add'))
    expect(addBtn).toBeDefined()
    await addBtn!.trigger('click')
    await nextTickFlush()

    // the add-membership form exposes a process picker (placeholder key)
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('selectProcess')
    }, { timeout: 2000 })
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
      global: { stubs: { teleport: false } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('createNewKey')
    }, { timeout: 2000 })

    expect(wrapper.text()).toContain('revoked')
  })

  it('Role change on membership row calls changeMemberRole', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: false } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    // Open the membership role Select for proc-a and pick DESIGNER
    await openSelect(wrapper, 'member-role-proc-a')
    const item = optionTexts().find((o) => o === 'designerRole')
    expect(item).toBeDefined()
    const el = Array.from(document.querySelectorAll('[role="option"]')).find((o) => (o.textContent ?? '') === 'designerRole')
    // reka's SelectItem commits the selection on `pointerup`, not on `click`.
    ;(el as HTMLElement).dispatchEvent(new MouseEvent('pointerup', { bubbles: true, button: 0 }))
    await nextTickFlush()

    await vi.waitFor(() => {
      expect(admin.changeMemberRole).toHaveBeenCalledWith('proc-a', 'u1', 'DESIGNER')
    }, { timeout: 2000 })
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
      global: { stubs: { teleport: false } },
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
      global: { stubs: { teleport: false } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    // Open add membership form
    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('add'))
    await addBtn!.trigger('click')
    await nextTickFlush()

    // Open the process Select and inspect available options (proc-a already a member → absent)
    await openSelect(wrapper, 'add-member-process')
    const options = optionTexts()
    expect(options.some((o) => o.includes('proc-a'))).toBe(false)
    expect(options.some((o) => o.includes('proc-b'))).toBe(true)
  })

  it('409 toast on addMember conflict', async () => {
    toastError.mockClear()

    vi.mocked(admin.addMember).mockRejectedValue({ response: { status: 409 } })

    const wrapper = mount(UserDetailPanel, {
      props: { user: mockUser },
      global: { stubs: { teleport: false } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    // Open add membership form
    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('add'))
    await addBtn!.trigger('click')
    await nextTickFlush()

    // Select a process (proc-b)
    await openSelect(wrapper, 'add-member-process')
    const item = Array.from(document.querySelectorAll('[role="option"]')).find((o) => (o.textContent ?? '').includes('proc-b'))
    expect(item).toBeDefined()
    // reka's SelectItem commits the selection on `pointerup`, not on `click`.
    ;(item as HTMLElement).dispatchEvent(new MouseEvent('pointerup', { bubbles: true, button: 0 }))
    // reka commits the selection asynchronously — wait until the placeholder is replaced.
    await vi.waitFor(() => {
      expect(wrapper.find('[data-testid="add-member-process"]').text()).not.toContain('selectProcess')
    }, { timeout: 2000 })

    // Click Add
    const submitBtn = wrapper.findAll('button').find((b) => b.text() === 'add')
    await submitBtn!.trigger('click')
    await nextTickFlush()

    // Wait for async error handling
    await vi.waitFor(() => {
      expect(toastError).toHaveBeenCalled()
    }, { timeout: 2000 })

    expect(toastError).toHaveBeenCalledWith('alreadyMember')
  })

  it('editAccount switches the panel to inline edit (no modal); userType is immutable in the Drawer', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: { ...mockUser, userType: 'SYSTEM' }, editing: false },
      global: { stubs: { teleport: false } },
    })

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('proc-a')
    }, { timeout: 2000 })

    // View mode: an edit action is offered, but no inline save/cancel form yet
    expect(wrapper.find('[data-testid="edit-account-button"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="drawer-save-user"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="drawer-cancel-user"]').exists()).toBe(false)

    // Switch the SAME Drawer to inline edit mode
    await wrapper.setProps({ editing: true })
    await wrapper.vm.$nextTick()
    await nextTickFlush()

    // Inline edit form is active: the view-mode edit action is gone, save/cancel present...
    expect(wrapper.find('[data-testid="edit-account-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="drawer-save-user"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="drawer-cancel-user"]').exists()).toBe(true)
    // ...and userType is rendered as read-only text, never a <select> in the Drawer (immutable)
    expect(wrapper.find('[data-testid="userType"]').exists()).toBe(false)
    // No separate create/edit modal is spawned on top
    expect(document.querySelector('[data-testid="form-username"]')).toBeNull()
  })
})
