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

/** Badge label + tailwind classes for an activity lifecycle status (CREATED/IN_PROGRESS/COMPLETED/CANCELLED/ERROR). */
export function taskStatusBadge(status: string | null | undefined, completedAt: string | null): { label: string; cls: string } {
  const s = status || (completedAt ? 'COMPLETED' : 'CREATED')
  switch (s) {
    case 'COMPLETED': return { label: 'Completed', cls: 'bg-green-100 text-green-800' }
    case 'CANCELLED': return { label: 'Cancelled', cls: 'bg-gray-100 text-gray-700' }
    case 'ERROR': return { label: 'Incident', cls: 'bg-red-100 text-red-800' }
    case 'IN_PROGRESS': return { label: 'In progress', cls: 'bg-yellow-100 text-yellow-800' }
    default: return { label: 'Active', cls: 'bg-yellow-100 text-yellow-800' }
  }
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
