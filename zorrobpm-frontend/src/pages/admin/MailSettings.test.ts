// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MailSettings from './MailSettings.vue'

const mockGetMailHealth = vi.fn()
const mockGetMailSettings = vi.fn()
const mockSaveMailSettings = vi.fn()
const mockCheckMailSettings = vi.fn()
const mockTestMailSettingsToSelf = vi.fn()

vi.mock('@/services/adminService', () => ({
  getMailHealth: (...a: any[]) => mockGetMailHealth(...a),
  getMailSettings: (...a: any[]) => mockGetMailSettings(...a),
  saveMailSettings: (...a: any[]) => mockSaveMailSettings(...a),
  checkMailSettings: (...a: any[]) => mockCheckMailSettings(...a),
  testMailSettingsToSelf: (...a: any[]) => mockTestMailSettingsToSelf(...a),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: mockToastSuccess, error: mockToastError }),
}))

describe('MailSettings', () => {
  beforeEach(() => {
    mockGetMailHealth.mockResolvedValue({
      configured: true,
      reachable: null,
      lastSuccess: null,
      lastError: null,
      lastErrorMessage: null,
    })
    mockGetMailSettings.mockResolvedValue({
      host: 'smtp.example.com',
      port: 587,
      username: 'sender',
      password: null,
      from: 'noreply@example.com',
      allowedRecipients: '',
      passwordSet: false,
    })
    mockSaveMailSettings.mockResolvedValue({
      host: 'smtp.example.com',
      port: 587,
      username: 'sender',
      password: null,
      from: 'noreply@example.com',
      allowedRecipients: '',
      passwordSet: true,
    })
    mockCheckMailSettings.mockResolvedValue({ reachable: true, errorCode: null })
    mockTestMailSettingsToSelf.mockResolvedValue(undefined)
    mockSaveMailSettings.mockClear()
    mockCheckMailSettings.mockClear()
    mockTestMailSettingsToSelf.mockClear()
    mockToastSuccess.mockClear()
    mockToastError.mockClear()
  })

  it('shows saved config in view mode (form hidden)', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    expect(mockGetMailHealth).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="host"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('smtp.example.com')
    expect(wrapper.text()).toContain('mailConfigured')
  })

  it('edit mode reveals the form bound to saved values', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="host"]').exists()).toBe(true)
    expect((wrapper.find('[data-testid="host"]').element as HTMLInputElement).value).toBe('smtp.example.com')
  })

  it('save sends entered values and returns to view mode', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="host"]').setValue('new.host.example')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(mockSaveMailSettings).toHaveBeenCalledWith(
      expect.objectContaining({ host: 'new.host.example' }),
    )
    expect(wrapper.find('[data-testid="host"]').exists()).toBe(false)
  })

  it('cancel discards edits and returns to view mode', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="host"]').setValue('changed.example')
    await wrapper.find('[data-testid="cancel"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="host"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('smtp.example.com')
  })

  it('check probes current form values without saving and toasts success', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="check"]').trigger('click')
    await flushPromises()
    expect(mockCheckMailSettings).toHaveBeenCalledWith(
      expect.objectContaining({ host: 'smtp.example.com' }),
    )
    expect(mockSaveMailSettings).not.toHaveBeenCalled()
    expect(mockToastSuccess).toHaveBeenCalledWith('mailCheckOk')
  })

  it('check toasts failure when the server is unreachable', async () => {
    mockCheckMailSettings.mockResolvedValueOnce({ reachable: false, errorCode: 'UNREACHABLE' })
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="check"]').trigger('click')
    await flushPromises()
    expect(mockToastError).toHaveBeenCalledWith('mailCheckFailed')
  })

  it('test-send button (view mode) sends from the saved config with no body', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    expect(wrapper.find('[data-testid="testSend"]').exists()).toBe(true)
    await wrapper.find('[data-testid="testSend"]').trigger('click')
    await flushPromises()
    expect(mockTestMailSettingsToSelf).toHaveBeenCalledWith()
    expect(mockToastSuccess).toHaveBeenCalledWith('mailTestSentToSelf')
  })

  it('password visibility toggle switches input type', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    const input = wrapper.find('[data-testid="password"]')
    const toggle = wrapper.find('[data-testid="togglePassword"]')
    expect((input.element as HTMLInputElement).type).toBe('password')
    expect(wrapper.find('[data-testid="togglePassword"] svg').exists()).toBe(true)
    await toggle.trigger('click')
    await flushPromises()
    expect((wrapper.find('[data-testid="password"]').element as HTMLInputElement).type).toBe('text')
  })

  it('health loads in background and shows config + connection state', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    expect(mockGetMailHealth).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="healthLoading"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('mailConfigured')
  })

  it('test-send disabled button in edit mode shows hint', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    const btn = wrapper.find('[data-testid="testSendDisabledInEdit"]')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeDefined()
    expect(btn.attributes('title')).toBe('mailTestSendDisabledHint')
  })
})
