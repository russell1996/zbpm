// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import UserList from './UserList.vue'
import { createUser } from '@/services/userService'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/services/userService', () => ({
  getUsers: vi.fn().mockResolvedValue({
    data: [
      { id: 'u1', username: 'alice', fullName: 'Alice', email: 'alice@test.com', role: 'ADMIN', active: true, userType: 'HUMAN', createdAt: '', updatedAt: '' },
      { id: 'u2', username: 'bob', fullName: 'Bob', email: null, role: 'USER', active: false, userType: 'SYSTEM', createdAt: '', updatedAt: '' },
    ],
    totalElements: 2,
  }),
  createUser: vi.fn(),
  updateUser: vi.fn(),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }),
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

  it('WO-ACL-11 criterion 8: no per-row edit button; the Drawer holds the edit action (no separate modal)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    // Row has no standalone 'edit' button — actions live inside the Drawer
    const rowEdit = wrapper.findAll('tbody tr').flatMap((tr) => tr.findAll('button')).find((b) => b.text() === 'edit')
    expect(rowEdit).toBeUndefined()

    // Clicking the row opens the Drawer (panel)
    await wrapper.findAll('tbody tr')[0].trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('apiKey')

    // The Drawer footer offers an inline 'edit' action (no separate modal opens)
    const drawerEdit = wrapper.findAll('button').find((b) => b.text() === 'edit')
    expect(drawerEdit).toBeDefined()
    await drawerEdit!.trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="drawer-save-user"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="drawer-cancel-user"]').exists()).toBe(true)
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

  it('WO-INT-4 criterion 2: a SYSTEM account carries the system chip in the user list, a human does not', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    // exactly one chip, attached to the system row (bob), not to the human row (alice)
    const chips = wrapper.findAll('tbody tr').map((tr) => tr.text()).filter((t) => t.includes('systemAccount'))
    expect(chips).toHaveLength(1)
    expect(chips[0]).toContain('bob')
    expect(chips[0]).not.toContain('alice')
  })

  it('WO-INT-4 criterion 2: the type filter narrows one list — SYSTEM shows only systems, HUMAN only humans', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    const filter = wrapper.find('[data-testid="user-type-filter"]')
    await filter.setValue('SYSTEM')
    await wrapper.vm.$nextTick()
    const rows = wrapper.findAll('tbody tr').map((tr) => tr.text())
    expect(rows.some((t) => t.includes('bob'))).toBe(true)
    expect(rows.some((t) => t.includes('alice'))).toBe(false)

    await filter.setValue('HUMAN')
    await wrapper.vm.$nextTick()
    const humanRows = wrapper.findAll('tbody tr').map((tr) => tr.text())
    expect(humanRows.some((t) => t.includes('alice'))).toBe(true)
    expect(humanRows.some((t) => t.includes('bob'))).toBe(false)

    // back to ALL — both are visible again (one list, not two screens)
    await filter.setValue('ALL')
    await wrapper.vm.$nextTick()
    const allRows = wrapper.findAll('tbody tr').map((tr) => tr.text())
    expect(allRows.some((t) => t.includes('alice'))).toBe(true)
    expect(allRows.some((t) => t.includes('bob'))).toBe(true)
  })
})

describe('WO-UI-10 Phase 2: SYSTEM account creation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('offers HUMAN/SYSTEM and sends userType=SYSTEM on create; creationMode hidden, email optional', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('addUser'), { timeout: 2000 })
    await wrapper.findAll('button').find((b) => b.text() === 'addUser')!.trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="userType"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="creationMode"]').exists()).toBe(true)
    expect((wrapper.find('[data-testid="userType"]').element as HTMLSelectElement).value).toBe('HUMAN')

    await wrapper.find('[data-testid="userType"]').setValue('SYSTEM')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="creationMode"]').exists()).toBe(false)

    await wrapper.find('[data-testid="form-username"]').setValue('svc1')
    await wrapper.find('[data-testid="submit-user"]').trigger('click')
    await wrapper.vm.$nextTick()
    await vi.waitFor(() => expect(createUser).toHaveBeenCalled(), { timeout: 2000 })
    const payload = (createUser as unknown as { mock: { calls: any[] } }).mock.calls[0][0] as Record<string, unknown>
    expect(payload.userType).toBe('SYSTEM')
    expect(payload.email).toBeNull()
    expect(payload.creationMode).toBeUndefined()
  })

  it('HUMAN create still requires email (no regression of WO-ACL-19)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('addUser'), { timeout: 2000 })
    await wrapper.findAll('button').find((b) => b.text() === 'addUser')!.trigger('click')
    await wrapper.vm.$nextTick()
    expect((wrapper.find('[data-testid="userType"]').element as HTMLSelectElement).value).toBe('HUMAN')
    await wrapper.find('[data-testid="form-username"]').setValue('human1')
    await wrapper.find('[data-testid="submit-user"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(createUser).not.toHaveBeenCalled()
  })

  it('editing a SYSTEM user shows userType immutable (read-only inside the Drawer edit mode)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('bob'), { timeout: 2000 })
    const bobRow = wrapper.findAll('tbody tr').find((r) => r.text().includes('bob'))!
    await bobRow.trigger('click')
    await wrapper.vm.$nextTick()
    const drawerEdit = wrapper.findAll('button').find((b) => b.text() === 'edit')!
    await drawerEdit.trigger('click')
    await wrapper.vm.$nextTick()
    // userType is shown read-only as SYSTEM; no editable userType select
    expect(wrapper.text()).toContain('userTypeSystem')
    expect(wrapper.find('[data-testid="userType"]').exists()).toBe(false)
  })
})
