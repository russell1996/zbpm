// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import UserList from './UserList.vue'

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
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
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

  it('WO-ACL-11 criterion 8: clicking Edit does NOT expand the panel', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: true } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    const editBtn = wrapper.findAll('button').find((b) => b.text() === 'edit')!
    await editBtn.trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('apiKey')
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
