// @vitest-environment jsdom
// jsdom lacks PointerEvent capture APIs that reka-ui's SelectTrigger calls on pointerdown.
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
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

// reka Select opens on a left-button pointerdown of the trigger; the menu content is portaled
// to <body>, so items must be queried from document, not from `wrapper`.
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
  // reka's SelectItem commits the selection on `pointerup`, not on `click`.
  ;(item as HTMLElement).dispatchEvent(new MouseEvent('pointerup', { bubbles: true, button: 0 }))
  await nextTickFlush()
}

describe('UserList render', () => {
  // reka teleports Select content to <body>; clear leftovers so option queries stay scoped.
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders users without error (regression for undefined user.id bug)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.text()).toContain('usersCount')
  })

  it('WO-ACL-11 criterion 6: clicking the ROW expands the user detail panel (the Details button is gone)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    expect(wrapper.text()).not.toContain('details')
    await wrapper.findAll('tbody tr')[0].trigger('click')
    await nextTickFlush()

    // Clicking the row sets the selected user, which opens the detail Drawer.
    // (The Drawer is portaled + Presence-gated and does not render in jsdom; its
    //  content is covered by UserDetailPanel.test.ts, mounted directly.)
    // WO-UI-17 F24: the SFC's public instance type does not expose setup
    // bindings (same reason `wrapper.vm as any` is used in
    // ProcessInstanceDetail.test.ts) — the selection itself is real state,
    // asserted through the untyped handle.
    expect((wrapper.vm as any).selectedUser).toBeTruthy()
  })

  it('WO-ACL-11 criterion 8: "Редактировать учётную запись" switches the Drawer to inline edit (no modal)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    // Row has no standalone 'edit' button — the edit action lives inside the Drawer
    const rowEdit = wrapper.findAll('tbody tr').flatMap((tr) => tr.findAll('button')).find((b) => b.text() === 'editAccount')
    expect(rowEdit).toBeUndefined()

    // Clicking the row opens the Drawer
    await wrapper.findAll('tbody tr')[0].trigger('click')
    await nextTickFlush()
    expect((wrapper.vm as any).selectedUser).toBeTruthy()

    // No separate create/edit modal opens on top of the list when the Drawer is invoked
    // (the edit action lives inside the Drawer itself — verified in UserDetailPanel.test.ts).
    expect(document.querySelector('[data-testid="form-username"]')).toBeNull()
  })

  it('WO-ACL-11 criterion 9: Enter on the focused row expands the user detail panel', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    const row = wrapper.findAll('tbody tr')[0]
    expect(row.attributes('tabindex')).toBe('0')
    await row.trigger('keydown', { key: 'Enter' })
    await nextTickFlush()
    expect((wrapper.vm as any).selectedUser).toBeTruthy()
  })

  it('WO-INT-4 criterion 2: a SYSTEM account carries the system chip in the user list, a human does not', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    // exactly one chip, attached to the system row (bob), not to the human row (alice)
    const chips = wrapper.findAll('tbody tr').map((tr) => tr.text()).filter((t) => t.includes('systemAccount'))
    expect(chips).toHaveLength(1)
    expect(chips[0]).toContain('bob')
    expect(chips[0]).not.toContain('alice')
  })

  it('WO-INT-4 criterion 2: the type filter narrows one list — SYSTEM shows only systems, HUMAN only humans', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => { expect(wrapper.text()).toContain('alice') }, { timeout: 2000 })

    await openSelect(wrapper, 'user-type-filter')
    await chooseSelectItem(wrapper, 'user-type-filter-SYSTEM')
    let rows = wrapper.findAll('tbody tr').map((tr) => tr.text())
    expect(rows.some((t) => t.includes('bob'))).toBe(true)
    expect(rows.some((t) => t.includes('alice'))).toBe(false)

    await openSelect(wrapper, 'user-type-filter')
    await chooseSelectItem(wrapper, 'user-type-filter-HUMAN')
    rows = wrapper.findAll('tbody tr').map((tr) => tr.text())
    expect(rows.some((t) => t.includes('alice'))).toBe(true)
    expect(rows.some((t) => t.includes('bob'))).toBe(false)

    // back to ALL — both are visible again (one list, not two screens)
    await openSelect(wrapper, 'user-type-filter')
    await chooseSelectItem(wrapper, 'user-type-filter-ALL')
    rows = wrapper.findAll('tbody tr').map((tr) => tr.text())
    expect(rows.some((t) => t.includes('alice'))).toBe(true)
    expect(rows.some((t) => t.includes('bob'))).toBe(true)
  })
})

describe('WO-UI-10 Phase 2: SYSTEM account creation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // reka teleports Select content to <body>; clear leftovers so option queries stay scoped.
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('offers HUMAN/SYSTEM and sends userType=SYSTEM on create; creationMode hidden, email optional', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('addUser'), { timeout: 2000 })
    await wrapper.findAll('button').find((b) => b.text() === 'addUser')!.trigger('click')
    await nextTickFlush()

    expect(wrapper.find('[data-testid="userType"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="creationMode"]').exists()).toBe(true)

    // both options are offered once the menu is open
    await openSelect(wrapper, 'userType')
    expect(document.querySelector('[data-testid="userType-item-HUMAN"]')).not.toBeNull()
    expect(document.querySelector('[data-testid="userType-item-SYSTEM"]')).not.toBeNull()
    await chooseSelectItem(wrapper, 'userType-item-SYSTEM')
    // creationMode is hidden for SYSTEM accounts
    expect(wrapper.find('[data-testid="creationMode"]').exists()).toBe(false)

    await wrapper.find('[data-testid="form-username"]').setValue('svc1')
    await wrapper.find('[data-testid="submit-user"]').trigger('click')
    await nextTickFlush()
    await vi.waitFor(() => expect(createUser).toHaveBeenCalled(), { timeout: 2000 })
    const payload = (createUser as unknown as { mock: { calls: any[] } }).mock.calls[0][0] as Record<string, unknown>
    expect(payload.userType).toBe('SYSTEM')
    expect(payload.email).toBeNull()
    expect(payload.creationMode).toBeUndefined()
  })

  it('HUMAN create still requires email (no regression of WO-ACL-19)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('addUser'), { timeout: 2000 })
    await wrapper.findAll('button').find((b) => b.text() === 'addUser')!.trigger('click')
    await nextTickFlush()
    expect(wrapper.find('[data-testid="userType"]').exists()).toBe(true)
    await wrapper.find('[data-testid="form-username"]').setValue('human1')
    await wrapper.find('[data-testid="submit-user"]').trigger('click')
    await nextTickFlush()
    expect(createUser).not.toHaveBeenCalled()
  })

  it('editing a SYSTEM user shows userType immutable (inline edit, no modal)', async () => {
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('bob'), { timeout: 2000 })
    const bobRow = wrapper.findAll('tbody tr').find((r) => r.text().includes('bob'))!
    await bobRow.trigger('click')
    await nextTickFlush()
    expect((wrapper.vm as any).selectedUser).toBeTruthy()
    // Opening a SYSTEM user does not spawn a second create/edit modal; the Drawer's
    // inline edit (and userType immutability) is covered by UserDetailPanel.test.ts.
    expect(document.querySelector('[data-testid="form-username"]')).toBeNull()
  })
})
