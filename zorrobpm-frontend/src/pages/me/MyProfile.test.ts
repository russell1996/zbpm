// @vitest-environment jsdom
/**
 * WO-SEC-58 criteria 7-8: the My Profile page renders for any user and its
 * password form surfaces SERVER errors inside the form (not swallowed), while
 * success shows a confirmation.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MyProfile from './MyProfile.vue'

const mockChange = vi.hoisted(() => vi.fn())
vi.mock('@/services/userService', () => ({
  changeMyPassword: mockChange,
}))
const authMock = vi.hoisted(() => ({
  user: { id: 'u1', username: 'ivan', fullName: 'Ivan I.', email: 'i@t.com', role: 'USER' },
  refreshUser: vi.fn(async () => {}),
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authMock }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }) }))
vi.mock('@/services/apiKeyService', () => ({
  getMyApiKey: vi.fn().mockRejectedValue({ response: { status: 404 } }),
  rotateMyApiKey: vi.fn(),
  revokeMyApiKey: vi.fn(),
}))
vi.mock('@/composables/useDateFormat', () => ({ useDateFormat: () => ({ formatDate: (v: string) => v }) }))

import type { VueWrapper } from '@vue/test-utils'
type Wrapper = VueWrapper<InstanceType<typeof MyProfile>>
async function setValue(w: Wrapper, sel: string, v: string) { await w.get(sel).setValue(v) }
async function submitForm(w: Wrapper) { await w.find('form').trigger('submit') }

describe('WO-SEC-58 criteria 7-8: My Profile', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('criterion 7: renders who-I-am fields for any authenticated user', () => {
    const wrapper = mount(MyProfile)
    const text = wrapper.text()
    expect(text).toContain('ivan')
    expect(text).toContain('Ivan I.')
    expect(text).toContain('i@t.com')
    expect(text).toContain('USER')
  })

  it('criterion 8: server error is shown INSIDE the form', async () => {
    mockChange.mockRejectedValue({ response: { data: { message: 'Current password is incorrect' } } })
    const wrapper = mount(MyProfile)
    const f = { current: (v:string)=>setValue(wrapper,"#currentPassword",v), next:(v:string)=>setValue(wrapper,"#newPassword",v), confirm:(v:string)=>setValue(wrapper,"#confirmNew",v), submit:()=>submitForm(wrapper) }
    await f.current('wrong')
    await f.next('Whatever!2345678')
    await f.confirm('Whatever!2345678')
    await submitForm(wrapper)
    await flushPromises()
    const err = wrapper.get('[data-testid="password-error"]')
    expect(err.text()).toContain('Current password is incorrect')
  })

  it('criterion 8: success shows confirmation and clears the form', async () => {
    mockChange.mockResolvedValue({ id: 'u1' })
    const wrapper = mount(MyProfile)
    const f = { current: (v:string)=>setValue(wrapper,"#currentPassword",v), next:(v:string)=>setValue(wrapper,"#newPassword",v), confirm:(v:string)=>setValue(wrapper,"#confirmNew",v), submit:()=>submitForm(wrapper) }
    await f.current('OldPassw0rd!')
    await f.next('NewPassw0rd!2345')
    await f.confirm('NewPassw0rd!2345')
    await submitForm(wrapper)
    await flushPromises()
    expect(mockChange).toHaveBeenCalledWith('OldPassw0rd!', 'NewPassw0rd!2345')
    expect(wrapper.get('[data-testid="password-success"]').text()).toContain('passwordChangedOk')
    expect((wrapper.get('#currentPassword').element as HTMLInputElement).value).toBe('')
  })
})
