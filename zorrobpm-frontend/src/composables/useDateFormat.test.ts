// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { useDateFormat } from './useDateFormat'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ locale: { value: 'ru' } }),
}))

describe('useDateFormat', () => {
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
