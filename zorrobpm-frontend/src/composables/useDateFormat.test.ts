// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useDateFormat } from './useDateFormat'

const mockLocale = { value: 'ru' }
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ locale: mockLocale }),
}))

afterEach(() => {
  vi.restoreAllMocks()
  mockLocale.value = 'ru'
})

describe('useDateFormat', () => {
  it('maps the app locale code "kz" to the real BCP-47 Kazakh tag "kk-KZ", not raw "kz"', () => {
    // 'kz' is the ISO 3166-1 country code for Kazakhstan, not a language tag (the real one is
    // 'kk') - Intl.DateTimeFormat('kz', ...) doesn't throw, it silently resolves to 'ru-KZ'.
    // Spy on the constructor (still delegating to the real one) to assert useDateFormat passes
    // the corrected 'kk-KZ' tag rather than the raw app locale code. Vitest requires the mock
    // implementation to use `function`, not an arrow function, to stay usable with `new`.
    const RealDateTimeFormat = Intl.DateTimeFormat
    const ctorSpy = vi
      .spyOn(Intl, 'DateTimeFormat')
      .mockImplementation(function (...args: ConstructorParameters<typeof Intl.DateTimeFormat>) {
        return new RealDateTimeFormat(...args)
      })
    mockLocale.value = 'kz'
    const { formatDate } = useDateFormat()
    formatDate(new Date('2026-07-30T12:00:00Z'))
    expect(ctorSpy).toHaveBeenCalledWith('kk-KZ', expect.anything())
  })

  it('formatDate returns dd.MM.yyyy for ru locale', () => {
    const { formatDate } = useDateFormat()
    const result = formatDate(new Date('2026-07-30T12:00:00Z'))
    // Intl.DateTimeFormat with locale 'ru' formats as dd.MM.yyyy
    expect(result).toMatch(/30\.07\.2026/)
  })

  it('formatDate handles string input', () => {
    const { formatDate } = useDateFormat()
    const result = formatDate('2026-01-15T00:00:00Z')
    expect(result).toMatch(/15\.01\.2026/)
  })

  it('formatDateTime includes time', () => {
    const { formatDateTime } = useDateFormat()
    const result = formatDateTime(new Date('2026-07-30T14:30:00Z'))
    // Should contain both date and time parts
    expect(result).toMatch(/30/)
    expect(result).toMatch(/07/)
    expect(result).toMatch(/2026/)
  })
})
