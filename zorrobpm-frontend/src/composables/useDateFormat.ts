import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

// The app's locale codes (en/ru/kz) aren't all valid BCP-47 language tags - 'kz' is the
// ISO 3166-1 country code for Kazakhstan, not a language tag (the real one is 'kk'). Passing
// 'kz' straight to Intl.DateTimeFormat doesn't throw, it silently resolves to 'ru-KZ' instead
// of Kazakh, which stays invisible only as long as the format options below never include
// locale-specific text like month/weekday names.
const BCP47_LOCALE: Record<string, string> = { kz: 'kk-KZ' }

/**
 * Shared date formatting composable that respects the app's i18n locale.
 * Uses Intl.DateTimeFormat for consistent, locale-aware formatting.
 */
export function useDateFormat() {
  const { locale } = useI18n()
  const loc = computed(() => BCP47_LOCALE[locale.value] ?? locale.value ?? 'en')

  function formatDate(date: Date | string): string {
    const d = typeof date === 'string' ? new Date(date) : date
    return new Intl.DateTimeFormat(loc.value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).format(d)
  }

  function formatDateTime(date: Date | string): string {
    const d = typeof date === 'string' ? new Date(date) : date
    return new Intl.DateTimeFormat(loc.value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    }).format(d)
  }

  return { formatDate, formatDateTime }
}
