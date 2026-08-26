// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MailSettings from './MailSettings.vue'

const mockGetMailHealth = vi.fn()
const mockGetMailSettings = vi.fn()
const mockSaveMailSettings = vi.fn()
const mockTestMailSettingsToSelf = vi.fn()

vi.mock('@/services/adminService', () => ({
  getMailHealth: (...a: any[]) => mockGetMailHealth(...a),
  getMailSettings: (...a: any[]) => mockGetMailSettings(...a),
  saveMailSettings: (...a: any[]) => mockSaveMailSettings(...a),
  testMailSettingsToSelf: (...a: any[]) => mockTestMailSettingsToSelf(...a),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
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
    mockTestMailSettingsToSelf.mockResolvedValue('Test email sent successfully to admin@corp.kz')
  })

  it('loads health and settings on mount and binds host', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    expect(mockGetMailHealth).toHaveBeenCalled()
    expect(mockGetMailSettings).toHaveBeenCalled()
    expect((wrapper.find('[data-testid="host"]').element as HTMLInputElement).value).toBe('smtp.example.com')
  })

  it('save sends entered values to service', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="host"]').setValue('new.host.example')
    await wrapper.find('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(mockSaveMailSettings).toHaveBeenCalledWith(
      expect.objectContaining({ host: 'new.host.example' }),
    )
  })

  it('test sends to self and shows returned result', async () => {
    const wrapper = mount(MailSettings)
    await flushPromises()
    await wrapper.find('[data-testid="test"]').trigger('click')
    await flushPromises()
    expect(mockTestMailSettingsToSelf).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="testResult"]').text()).toContain('admin@corp.kz')
  })
})
