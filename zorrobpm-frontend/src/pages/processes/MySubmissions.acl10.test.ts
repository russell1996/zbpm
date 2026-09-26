// @vitest-environment jsdom
/**
 * WO-ACL-10 criterion 20: the PENDING badge must show a REAL localized string,
 * not the raw 'statusPending' key. The key existed in no locale at all, so the
 * parity check could not catch it — the badge rendered the literal key on stage.
 *
 * This test mounts the real MySubmissions with a REAL i18n instance (ru/kz/en)
 * and asserts the rendered text is the translated string.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import MySubmissions from './MySubmissions.vue'
import ru from '@/locales/ru.json'
import en from '@/locales/en.json'
import kz from '@/locales/kz.json'

const mockGetMy = vi.hoisted(() => vi.fn())
vi.mock('@/services/submissionService', () => ({
  getMySubmissions: mockGetMy,
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/composables/useDateFormat', () => ({
  useDateFormat: () => ({ formatDateTime: (v: string) => v }),
}))

function makeI18n(locale: string) {
  return createI18n({ legacy: false, locale, fallbackLocale: 'en', messages: { ru, en, kz } })
}

const PENDING_SUBMISSION = {
  id: 'sub-pending',
  processKey: 'p-pending',
  name: 'Pending Process',
  status: 'PENDING',
  submittedBy: 'alice',
  submittedAt: '2026-08-02T10:00:00Z',
  rejectReason: null,
  previousSubmissionId: null,
}

describe('WO-ACL-10 criterion 20: statusPending resolves to a real translation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetMy.mockResolvedValue([{ ...PENDING_SUBMISSION }])
  })

  it('ru: the PENDING badge shows «На рассмотрении», not the raw key', async () => {
    const wrapper = mount(MySubmissions, { global: { plugins: [makeI18n('ru')] } })
    await flushPromises()
    expect(wrapper.text()).toContain('На рассмотрении')
    expect(wrapper.text()).not.toContain('statusPending')
  })

  it('kz: the PENDING badge shows «Қаралуда», not the raw key', async () => {
    const wrapper = mount(MySubmissions, { global: { plugins: [makeI18n('kz')] } })
    await flushPromises()
    expect(wrapper.text()).toContain('Қаралуда')
    expect(wrapper.text()).not.toContain('statusPending')
  })

  it('en: the PENDING badge shows "Pending"', async () => {
    const wrapper = mount(MySubmissions, { global: { plugins: [makeI18n('en')] } })
    await flushPromises()
    expect(wrapper.text()).toContain('Pending')
    expect(wrapper.text()).not.toContain('statusPending')
  })
})