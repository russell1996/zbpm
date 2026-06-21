import api from './api'
import type {
  ProcessVariable,
  PagedData,
  VariableQuery,
} from '@/types/api'

function toQueryString(params: Record<string, unknown>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  return entries.length > 0 ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : ''
}

export async function getVariables(query: VariableQuery = {}): Promise<PagedData<ProcessVariable>> {
  const qs = toQueryString({ pageIndex: 0, pageSize: 50, ...query })
  const { data } = await api.get<PagedData<ProcessVariable>>(`/variables${qs}`)
  return data
}
