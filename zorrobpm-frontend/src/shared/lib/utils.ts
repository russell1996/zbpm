import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** A task is actionable (completable) only while its activity is active. */
export function isTaskActive(status: string | null | undefined, completedAt: string | null): boolean {
  if (status) return status === 'CREATED' || status === 'IN_PROGRESS'
  return !completedAt // fallback for older payloads without a status
}

/**
 * Extract a human-readable error message (WO-ACL-6 criterion 7): backend
 * validation rejections arrive as {code, message} (GlobalExceptionHandler),
 * e.g. "Process with key 'x' already exists — update the model from inside
 * the process". Show THAT text, never the generic axios "Request failed with
 * status code 400".
 */
export function errorMessage(e: unknown, fallback: string): string {
  if (e && typeof e === 'object' && 'response' in e) {
    const data = (e as { response?: { data?: { message?: unknown } } }).response?.data
    if (data && typeof data.message === 'string' && data.message.trim()) {
      return data.message
    }
  }
  return e instanceof Error && e.message ? e.message : fallback
}

/**
 * WO-ACL-15 criterion 12: backend errors since ACL-12 carry a stable `code` and
 * `params` (e.g. PROCESS_KEY_MISMATCH with {xmlKey, targetKey}). Translate the
 * KNOWN codes through the locale keys `errors.<CODE>` with the params injected;
 * an unknown code falls back to the server `message` (English), then to the
 * generic fallback. `t` is the vue-i18n translate fn — a missing key makes
 * vue-i18n return the key itself, which is how we detect "unknown".
 */
export function translatedError(
  e: unknown,
  t: (key: string, params?: Record<string, unknown>) => string,
  fallback: string,
): string {
  if (e && typeof e === 'object' && 'response' in e) {
    const data = (e as {
      response?: { data?: { code?: unknown; params?: Record<string, unknown>; message?: unknown } }
    }).response?.data
    if (data && typeof data.code === 'string' && data.code.trim()) {
      const key = `errors.${data.code}`
      const translated = t(key, data.params ?? {})
      if (translated !== key) return translated
      if (typeof data.message === 'string' && data.message.trim()) return data.message
    }
  }
  return e instanceof Error && e.message ? e.message : fallback
}
