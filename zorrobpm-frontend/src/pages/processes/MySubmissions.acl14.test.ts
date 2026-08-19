// @vitest-environment jsdom
/**
 * WO-ACL-14 criteria 21-22 — My Submissions table inside the drawer:
 *  21 — the 5-column table scrolls horizontally INSIDE the panel
 *       (overflow-x-auto wrapper) instead of inflating the drawer;
 *  22 — a long reject reason wraps inside its cell (break-words) and is
 *       shown in full — the cell must not truncate or push the column wide.
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

const LONG_REASON = 'документы не приложены: счёт-фактура № 12345678901234567890 от 2026-08-01 отсутствует в системе и не может быть восстановлен автоматически, пожалуйста приложите оригинал'

const REJECTED_SUBMISSION = {
  id: 'sub-rejected',
  processKey: 'p-reject',
  name: 'Rejected Process',
  status: 'REJECTED',
  submittedBy: 'alice',
  submittedAt: '2026-08-02T10:00:00Z',
  rejectReason: LONG_REASON,
  previousSubmissionId: null,
}

describe('WO-ACL-14 criteria 21-22: My Submissions table fits the drawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetMy.mockResolvedValue([{ ...REJECTED_SUBMISSION }])
  })

  it('criterion 21: the table is wrapped in an overflow-x-auto container (scrolls inside the panel)', async () => {
    const wrapper = mount(MySubmissions, { global: { plugins: [makeI18n('ru')] } })
    await flushPromises()
    const scroller = wrapper.find('div.overflow-x-auto')
    expect(scroller.exists()).toBe(true)
    expect(scroller.find('table').exists()).toBe(true)
  })

  it('criterion 22: a long reject reason is shown IN FULL and wraps (break-words) inside its cell', async () => {
    const wrapper = mount(MySubmissions, { global: { plugins: [makeI18n('ru')] } })
    await flushPromises()
    const reason = wrapper.find('td .text-red-600 span')
    expect(reason.exists()).toBe(true)
    expect(reason.classes()).toContain('break-words')
    expect(reason.text()).toBe(LONG_REASON)
  })

  it('criterion 22: submissions without a reject reason show the "no reason" placeholder', async () => {
    mockGetMy.mockResolvedValue([{ ...REJECTED_SUBMISSION, rejectReason: null }])
    const wrapper = mount(MySubmissions, { global: { plugins: [makeI18n('ru')] } })
    await flushPromises()
    expect(wrapper.text()).toContain('Причина не указана')
  })
})