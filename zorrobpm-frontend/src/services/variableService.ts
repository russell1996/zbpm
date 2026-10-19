import api from './api'
import type {
  ProcessVariable,
  PagedData,
  VariableQuery,
  VariableScopeGroup,
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

/**
 * WO-UI-37 (часть B): переменные инстанса по скоупам БЕЗ слияния —
 * ответ GET /process-instances/{id}/variables-by-scope (WO-C8-51 часть A):
 * массив групп, первая — root (scope == null), дальше по группе на каждый
 * непустой scopeId. Завершённые скоупы/инстансы своих переменных теряют
 * (движок сводит outputCollection в root), поэтому ответ живёт и умирает
 * вместе с ними — клиент просто рендерит то, что пришло.
 */
export async function getVariablesByScope(processInstanceId: string): Promise<VariableScopeGroup[]> {
  const { data } = await api.get<VariableScopeGroup[]>(
    `/process-instances/${processInstanceId}/variables-by-scope`,
  )
  return data
}
