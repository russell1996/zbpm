// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import UserList from './UserList.vue'

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

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }),
}))

describe('WO-UI-9 point 2 — UserList role SUPER_ADMIN', () => {
  it('select contains SUPER_ADMIN option (and correct translations)', async () => {
    mockGetUsers.mockResolvedValue({
      data: [],
      totalElements: 0,
    })
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => expect(wrapper.find('button').exists()).toBe(true), { timeout: 2000 })
    // open create form to see the select
    await wrapper.find('button').trigger('click') // first button is addUser
    await wrapper.vm.$nextTick()
    const selects = wrapper.findAll('select')
    const roleSelect = selects.find((s) => s.find('option[value="SUPER_ADMIN"]').exists())
    expect(roleSelect).toBeDefined()
    const options = roleSelect!.findAll('option')
    const values = options.map((o) => (o.element as HTMLOptionElement).value)
    expect(values).toContain('USER')
    expect(values).toContain('ADMIN')
    expect(values).toContain('SUPER_ADMIN')
    expect(values).toHaveLength(3)
  })

  it('editing a SUPER_ADMIN keeps SUPER_ADMIN selected — no silent downgrade to USER', async () => {
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
    mockGetUsers.mockResolvedValue({
      data: [superAdminUser],
      totalElements: 1,
    })
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('admin'), { timeout: 2000 })

    // click Edit
    const editBtn = wrapper.findAll('button').find((b) => b.text() === 'edit')!
    await editBtn.trigger('click')
    await wrapper.vm.$nextTick()

    // the select should now be visible and have SUPER_ADMIN selected
    const selects = wrapper.findAll('select')
    const select = selects.find((s) => s.find('option[value="SUPER_ADMIN"]').exists())
    expect(select).toBeDefined()
    expect(select!.exists()).toBe(true)
    const selectEl = select!.element as HTMLSelectElement
    expect(selectEl.value).toBe('SUPER_ADMIN')

    // the displayed text should be superAdminRole, not USER
    const selectedOption = select!.find('option:checked')
    expect(selectedOption.exists()).toBe(true)
    // ensure the option with SUPER_ADMIN is the selected one
    expect((selectedOption.element as HTMLOptionElement).value).toBe('SUPER_ADMIN')

    // simulate save without changing role — should send SUPER_ADMIN, not USER
    mockUpdateUser.mockResolvedValue({})
    const saveBtn = wrapper.findAll('button').find((b) => b.text() === 'save')!
    await saveBtn.trigger('click')
    await flushPromises()
    expect(mockUpdateUser).toHaveBeenCalledWith(
      'u1',
      expect.objectContaining({ role: 'SUPER_ADMIN' }),
    )
  })

  it('router guard for /admin/users is SUPER_ADMIN-only', async () => {
    // import router to verify meta — this is the authz check from WO variant (a)
    const { default: router } = await import('@/app/router')
    const route = router.getRoutes().find((r: any) => r.name === 'admin-users')
    expect(route).toBeDefined()
    expect(route!.meta.requiresSuperAdmin).toBe(true)
  })
})
