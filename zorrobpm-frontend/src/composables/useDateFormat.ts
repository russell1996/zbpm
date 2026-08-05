import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * Shared date formatting composable that respects the app's i18n locale.
 * Uses Intl.DateTimeFormat for consistent, locale-aware formatting.
 */
export function useDateFormat() {
  const { locale } = useI18n()
  const loc = computed(() => locale.value || 'en')

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

  function formatRelative(date: Date | string): string {
    const d = typeof date === 'string' ? new Date(date) : date
    const now = new Date()
    const diffMs = now.getTime() - d.getTime()
    const diffSec = Math.floor(diffMs / 1000)
    const diffMin = Math.floor(diffSec / 60)
    const diffHour = Math.floor(diffMin / 60)
    const diffDay = Math.floor(diffHour / 24)

    if (diffMin < 1) return 'just now'
    if (diffMin < 60) return `${diffMin}m ago`
    if (diffHour < 24) return `${diffHour}h ago`
    if (diffDay < 7) return `${diffDay}d ago`
    return formatDate(d)
  }

  return { formatDate, formatDateTime, formatRelative }
}
