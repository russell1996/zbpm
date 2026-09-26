// @vitest-environment jsdom
// jsdom lacks PointerEvent capture APIs that reka-ui's SelectTrigger calls on pointerdown.
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}

import { describe, it, expect, vi, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import UserList from './UserList.vue'
import UserDetailPanel from './UserDetailPanel.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

const mockGetUsers = vi.fn()
const mockCreateUser = vi.fn()
const mockUpdateUser = vi.fn()

vi.mock('@/services/userService', () => ({
  getUsers: (...a: any[]) => mockGetUsers(...a),
  createUser: (...a: any[]) => mockCreateUser(...a),
  updateUser: (...a: any[]) => mockUpdateUser(...a),
}))

vi.mock('@/services/adminService', () => ({
  getApiKey: vi.fn().mockRejectedValue({ response: { status: 404 } }),
  listUserMemberships: vi.fn().mockResolvedValue([]),
  addMember: vi.fn().mockResolvedValue({}),
  changeMemberRole: vi.fn().mockResolvedValue({}),
  removeMember: vi.fn().mockResolvedValue({}),
  createApiKey: vi.fn().mockResolvedValue({ key: 'k' }),
  revokeApiKey: vi.fn().mockResolvedValue({}),
  rotateApiKey: vi.fn().mockResolvedValue({ key: 'k' }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }),
}))

// reka teleports Select content to <body>; clear leftovers so option queries stay scoped.
afterEach(() => {
  document.body.innerHTML = ''
})

function openSelect(wrapper: ReturnType<typeof mount>, testid: string) {
  const trigger = wrapper.find(`[data-testid="${testid}"]`)
  expect(trigger.exists()).toBe(true)
  // reka SelectTrigger opens on pointerdown with button === 0.
  trigger.element.dispatchEvent(new MouseEvent('pointerdown', { button: 0, ctrlKey: false, bubbles: true, cancelable: true }))
  return trigger
}

function optionInDocument(testid: string): Element | null {
  return document.querySelector(`[data-testid="${testid}"]`)
}

const superAdminUser = {
  id: 'u1',
  username: 'admin',
  fullName: 'Admin',
  email: 'admin@corp.kz',
  role: 'SUPER_ADMIN',
  active: true,
  userType: 'HUMAN',
  createdAt: '',
  updatedAt: '',
}

describe('WO-UI-9 point 2 — role SUPER_ADMIN (reka Select, post WO-UI-10)', () => {
  it('create form role Select contains SUPER_ADMIN option (and correct translations)', async () => {
    mockGetUsers.mockResolvedValue({ data: [], totalElements: 0 })
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('addUser'), { timeout: 2000 })

    // open create form (button has text "addUser", no testid)
    const addBtn = wrapper.findAll('button').find((b) => b.text() === 'addUser')
    expect(addBtn).toBeTruthy()
    await addBtn!.trigger('click')
    await nextTick()

    openSelect(wrapper, 'create-role-trigger')
    await vi.waitFor(() => expect(optionInDocument('create-role-SUPER_ADMIN')).not.toBeNull(), { timeout: 2000 })

    expect(optionInDocument('create-role-SUPER_ADMIN')).not.toBeNull()
    const optionTexts = [...document.querySelectorAll('[role="option"]')].map((o) => o.textContent?.trim())
    expect(optionTexts).toEqual(expect.arrayContaining(['userRole', 'adminRole', 'superAdminRole']))
  })

  it('editing a SUPER_ADMIN keeps SUPER_ADMIN selected — no silent downgrade to USER (Drawer edit form)', async () => {
    const wrapper = mount(UserDetailPanel, {
      props: { user: superAdminUser as any, editing: true, editForm: { fullName: 'Admin', email: 'admin@corp.kz', role: 'SUPER_ADMIN', active: true, password: '' } },
      global: { stubs: { teleport: false } },
    })
    await flushPromises()
    await nextTick()

    // The Drawer inline-edit role Select must show SUPER_ADMIN (not be silently downgraded to USER).
    const trigger = wrapper.find('[data-testid="edit-role-trigger"]')
    expect(trigger.exists()).toBe(true)
    expect(trigger.text()).toContain('superAdminRole')

    openSelect(wrapper, 'edit-role-trigger')
    await vi.waitFor(() => expect(optionInDocument('edit-role-SUPER_ADMIN')).not.toBeNull(), { timeout: 2000 })
    expect(optionInDocument('edit-role-SUPER_ADMIN')).not.toBeNull()
  })

  it('router guard for /admin/users is SUPER_ADMIN-only', async () => {
    // import router to verify meta — this is the authz check from WO variant (a)
    const { default: router } = await import('@/app/router')
    const route = router.getRoutes().find((r: any) => r.name === 'admin-users')
    expect(route).toBeDefined()
    expect(route!.meta.requiresSuperAdmin).toBe(true)
  })
})
