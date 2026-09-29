// @vitest-environment jsdom
if (!HTMLElement.prototype.hasPointerCapture) {
  HTMLElement.prototype.hasPointerCapture = () => false
  HTMLElement.prototype.setPointerCapture = () => {}
  HTMLElement.prototype.releasePointerCapture = () => {}
}
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import UserList from './UserList.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

const getUsersMock = vi.fn()
vi.mock('@/services/userService', () => ({
  getUsers: (...args: unknown[]) => getUsersMock(...args),
  createUser: vi.fn(),
  updateUser: vi.fn(),
}))
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }),
}))

async function nextTickFlush() {
  await new Promise((r) => setTimeout(r, 0))
}
async function openSelect(wrapper: ReturnType<typeof mount>, triggerTestid: string) {
  const trigger = wrapper.find(`[data-testid="${triggerTestid}"]`)
  trigger.element.dispatchEvent(
    new MouseEvent('pointerdown', { button: 0, ctrlKey: false, bubbles: true, cancelable: true }),
  )
  await nextTickFlush()
}
async function chooseSelectItem(_wrapper: ReturnType<typeof mount>, itemTestid: string) {
  const item = document.querySelector(`[data-testid="${itemTestid}"]`)
  expect(item).not.toBeNull()
  ;(item as HTMLElement).dispatchEvent(new MouseEvent('pointerup', { bubbles: true, button: 0 }))
  await nextTickFlush()
}

describe('WO-UI-16: active filter', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('default load uses active:true; switching shows all/inactive', async () => {
    const activeUsers = [{ id: 'u1', username: 'alice', fullName: 'Alice', email: null, role: 'USER', active: true, userType: 'HUMAN', createdAt: '', updatedAt: '' }]
    const inactiveUsers = [{ id: 'u2', username: 'bob', fullName: 'Bob', email: null, role: 'USER', active: false, userType: 'HUMAN', createdAt: '', updatedAt: '' }]
    getUsersMock.mockImplementation(async (params: { active?: boolean }) => {
      if (params.active === true) return { data: activeUsers, totalElements: 1 }
      if (params.active === false) return { data: inactiveUsers, totalElements: 1 }
      return { data: [...activeUsers, ...inactiveUsers], totalElements: 2 }
    })

    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => expect(getUsersMock).toHaveBeenCalled(), { timeout: 2000 })
    expect(getUsersMock).toHaveBeenCalledWith(expect.objectContaining({ active: true }))
    await vi.waitFor(() => expect(wrapper.text()).toContain('alice'), { timeout: 2000 })
    expect(wrapper.text()).not.toContain('bob')

    // switch to ALL — should reload with no active filter
    getUsersMock.mockClear()
    await openSelect(wrapper, 'user-active-filter')
    await chooseSelectItem(wrapper, 'user-active-filter-ALL')
    await nextTickFlush()
    await vi.waitFor(() => expect(getUsersMock).toHaveBeenCalled(), { timeout: 2000 })
    expect(getUsersMock).toHaveBeenLastCalledWith(expect.objectContaining({ active: undefined }))
    await vi.waitFor(() => expect(wrapper.text()).toContain('bob'), { timeout: 2000 })
    expect(wrapper.text()).toContain('alice')

    // switch to INACTIVE — only bob
    getUsersMock.mockClear()
    await openSelect(wrapper, 'user-active-filter')
    await chooseSelectItem(wrapper, 'user-active-filter-INACTIVE')
    await nextTickFlush()
    await vi.waitFor(() => expect(getUsersMock).toHaveBeenCalled(), { timeout: 2000 })
    expect(getUsersMock).toHaveBeenLastCalledWith(expect.objectContaining({ active: false }))
    await vi.waitFor(() => expect(wrapper.text()).toContain('bob'), { timeout: 2000 })
  })

  it('click on any visible user opens detail panel (active filter does not break selection)', async () => {
    const users = [
      { id: 'u1', username: 'alice', fullName: 'Alice', email: null, role: 'USER', active: true, userType: 'HUMAN', createdAt: '', updatedAt: '' },
      { id: 'u2', username: 'bob', fullName: 'Bob', email: null, role: 'USER', active: false, userType: 'HUMAN', createdAt: '', updatedAt: '' },
    ]
    getUsersMock.mockResolvedValue({ data: users, totalElements: 2 })
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    // need to show all to see bob
    await vi.waitFor(() => expect(wrapper.text()).toContain('alice'), { timeout: 2000 })
    getUsersMock.mockClear()
    // switch to ALL to make bob visible (type filter stays ALL)
    await openSelect(wrapper, 'user-active-filter')
    await chooseSelectItem(wrapper, 'user-active-filter-ALL')
    await nextTickFlush()
    await vi.waitFor(() => expect(wrapper.text()).toContain('bob'), { timeout: 2000 })

    const bobRow = wrapper.findAll('tbody tr').find((r) => r.text().includes('bob'))!
    await bobRow.trigger('click')
    await nextTickFlush()
    expect((wrapper.vm as unknown as { selectedUser: { username: string } }).selectedUser).toBeTruthy()
    expect((wrapper.vm as unknown as { selectedUser: { username: string } }).selectedUser.username).toBe('bob')
  })

  it('typeFilter still works together with active filter (client-side)', async () => {
    const users = [
      { id: 'u1', username: 'alice', fullName: 'Alice', email: null, role: 'USER', active: true, userType: 'HUMAN', createdAt: '', updatedAt: '' },
      { id: 'u2', username: 'bob', fullName: 'Bob', email: null, role: 'USER', active: true, userType: 'SYSTEM', createdAt: '', updatedAt: '' },
    ]
    getUsersMock.mockResolvedValue({ data: users, totalElements: 2 })
    const wrapper = mount(UserList, { global: { stubs: { teleport: false } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('alice'), { timeout: 2000 })
    expect(wrapper.text()).toContain('bob')

    await openSelect(wrapper, 'user-type-filter')
    await chooseSelectItem(wrapper, 'user-type-filter-HUMAN')
    let rows = wrapper.findAll('tbody tr').map((tr) => tr.text())
    expect(rows.some((t) => t.includes('alice'))).toBe(true)
    expect(rows.some((t) => t.includes('bob'))).toBe(false)

    await openSelect(wrapper, 'user-type-filter')
    await chooseSelectItem(wrapper, 'user-type-filter-SYSTEM')
    rows = wrapper.findAll('tbody tr').map((tr) => tr.text())
    expect(rows.some((t) => t.includes('bob'))).toBe(true)
    expect(rows.some((t) => t.includes('alice'))).toBe(false)
  })
})
