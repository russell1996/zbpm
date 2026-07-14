import type { ProcessVariable } from '@/types/api'

/** Convert flat form data (name→value) to ProcessVariable array for POST /process-instances or /complete. */
export function dataToVariables(data: Record<string, string>): ProcessVariable[] {
  return Object.entries(data).map(([name, value]) => ({
    name,
    type: (typeof value === 'number' ? 'LONG' : typeof value === 'boolean' ? 'BOOLEAN' : 'STRING') as ProcessVariable['type'],
    value: String(value),
  }))
}
