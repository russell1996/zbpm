// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MailSettings from './MailSettings.vue'

const mockGetMailHealth = vi.fn()
const mockGetMailSettings = vi.fn()
const mockSaveMailSettings = vi.fn()
const mockCheckMailSettings = vi.fn()
const mockTestMailSettingsToSelf = vi.fn()
const mockToastError = vi.fn()
const mockToastSuccess = vi.fn()

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

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: mockToastSuccess, error: mockToastError }),
}))

describe('WO-UI-9 point 1 — MailSettings shows .message not raw JSON', () => {
  beforeEach(() => {
    mockGetMailHealth.mockResolvedValue({
      configured: false,
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
    mockToastError.mockClear()
    mockToastSuccess.mockClear()
  })

  it('onSave: toast shows .message, not JSON stringified object', async () => {
    const backendMessage = 'У вашей учётки нет email, некуда слать тестовое письмо'
    mockSaveMailSettings.mockRejectedValueOnce({
      response: { data: { code: 'VALIDATION_ERROR', message: backendMessage } },
    })
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(mockToastError).toHaveBeenCalledTimes(1)
    const arg = mockToastError.mock.calls[0][0] as string
    expect(arg).toBe(backendMessage)
    expect(arg).not.toContain('VALIDATION_ERROR')
    expect(arg).not.toContain('{')
    // ensure raw object was NOT passed
    expect(typeof arg).toBe('string')
  })

  it('WO-INT-8 criterion 4: onCheck error toast is ALWAYS the localized key, never the raw backend message', async () => {
    // A real production message (English, e.g. a 429 rate-limit reason) must never leak through —
    // regression test for the exact leak an independent review caught: e.response.data.message was
    // shown verbatim, in English, in all three locales.
    const backendMessage = 'Too many mail check/test requests, try again later'
    mockCheckMailSettings.mockRejectedValueOnce({
      response: { data: { code: 'TOO_MANY_REQUESTS', message: backendMessage } },
    })
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="check"]').trigger('click')
    await flushPromises()
    expect(mockToastError).toHaveBeenCalledWith('mailCheckFailed')
    const arg = mockToastError.mock.calls[0][0] as string
    expect(arg).not.toContain(backendMessage)
    expect(arg).not.toContain('TOO_MANY_REQUESTS')
    expect(arg).not.toContain('{')
  })

  it('onSave/onTest fall back to default when .message missing', async () => {
    mockSaveMailSettings.mockRejectedValueOnce({ response: { data: { code: 'ERR' } } })
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(mockToastError).toHaveBeenCalledWith('Failed to save')
  })
})

describe('WO-UI-9 point 3 — MailSettings has max-w-2xl', () => {
  beforeEach(() => {
    mockGetMailHealth.mockResolvedValue({
      configured: false,
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
  })

  it('root wrapper has max-w-2xl (like MyApiKey.vue)', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    // the root div in <template> is the first div with space-y-6
    const root = wrapper.find('div.space-y-6')
    expect(root.exists()).toBe(true)
    expect(root.classes()).toContain('max-w-2xl')
  })
})
