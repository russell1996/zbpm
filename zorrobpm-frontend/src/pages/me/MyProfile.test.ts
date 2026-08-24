// @vitest-environment jsdom
/**
 * WO-SEC-58 criteria 7-8: the My Profile page renders for any user and its
 * password form surfaces SERVER errors inside the form (not swallowed), while
 * success shows a confirmation.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MyProfile from './MyProfile.vue'
import MyApiKey from './MyApiKey.vue'

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

const mockToastSuccess = vi.hoisted(() => vi.fn())
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: mockToastSuccess, error: vi.fn(), info: vi.fn(), warning: vi.fn(), loading: vi.fn(), dismiss: vi.fn() }),
}))

import type { VueWrapper } from '@vue/test-utils'
type Wrapper = VueWrapper<InstanceType<typeof MyProfile>>
async function setValue(w: Wrapper, sel: string, v: string) { await w.get(sel).setValue(v) }
async function submitForm(w: Wrapper) { await w.find('form').trigger('submit') }

describe('WO-SEC-58 criteria 7-8: My Profile', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('criterion 7: renders profile fields for any authenticated user', async () => {
    const wrapper = mount(MyProfile)
    const text = wrapper.text()
    expect(text).toContain('ivan')
    expect(text).toContain('Ivan I.')
    expect(text).toContain('i@t.com')
    expect(text).toContain('USER')
  })

  // WO-SEC-58 HOLD-fix (criterion 9): the API-key section must be REAL — mounted
  // component, not a leftover import. Removing <MyApiKey /> from MyProfile.vue
  // makes this test RED, so the section cannot silently disappear.
  // Top design: now in apikey view (left nav), need to switch to apikey
  it('criterion 9: the API-key section is mounted inside the profile', async () => {
    const wrapper = mount(MyProfile)
    const apiKeyNav = wrapper.findAll('button').find((b) => b.text().includes('apiKey'))
    expect(apiKeyNav).toBeDefined()
    await apiKeyNav!.trigger('click')
    await flushPromises()
    const apiKeySection = wrapper.findComponent(MyApiKey)
    expect(apiKeySection.exists())
      .toBe(true)
  })

  it('criterion 8: server error is shown INSIDE the form', async () => {
    mockChange.mockRejectedValue({ response: { data: { message: 'Current password is incorrect' } } })
    const wrapper = mount(MyProfile)
    // Top design: password form is now in a dialog, open via changePassword
    const btn = wrapper.findAll('button').find((b) => b.text().includes('changePassword'))
    expect(btn).toBeDefined()
    await btn!.trigger('click')
    await flushPromises()
    const f = { current: (v:string)=>setValue(wrapper,"#currentPassword",v), next:(v:string)=>setValue(wrapper,"#newPassword",v), confirm:(v:string)=>setValue(wrapper,"#confirmNew",v), submit:()=>submitForm(wrapper) }
    await f.current('wrong')
    await f.next('Whatever!2345678')
    await f.confirm('Whatever!2345678')
    await submitForm(wrapper)
    await flushPromises()
    const err = wrapper.get('[data-testid="password-error"]')
    expect(err.text()).toContain('Current password is incorrect')
  })

  it('criterion 8: success shows toast, closes dialog and clears form (top design)', async () => {
    mockChange.mockResolvedValue({ id: 'u1' })
    const wrapper = mount(MyProfile)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('changePassword'))
    expect(btn).toBeDefined()
    await btn!.trigger('click')
    await flushPromises()
    const f = { current: (v:string)=>setValue(wrapper,"#currentPassword",v), next:(v:string)=>setValue(wrapper,"#newPassword",v), confirm:(v:string)=>setValue(wrapper,"#confirmNew",v), submit:()=>submitForm(wrapper) }
    await f.current('OldPassw0rd!')
    await f.next('NewPassw0rd!2345')
    await f.confirm('NewPassw0rd!2345')
    await submitForm(wrapper)
    await flushPromises()
    expect(mockChange).toHaveBeenCalledWith('OldPassw0rd!', 'NewPassw0rd!2345')
    expect(mockToastSuccess).toHaveBeenCalledWith('Пароль успешно изменён')
    // dialog should be closed, form cleared for next open
    expect(wrapper.find('#currentPassword').exists()).toBe(false)
    // reopen — clean form
    const btn2 = wrapper.findAll('button').find((b) => b.text().includes('changePassword'))
    await btn2!.trigger('click')
    await flushPromises()
    expect((wrapper.get('#currentPassword').element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('[data-testid="password-error"]').exists()).toBe(false)
  })
})
