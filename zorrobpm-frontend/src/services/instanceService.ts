import api from './api'
import type {
  ProcessInstance,
  ActivityInstance,
  PagedData,
  ProcessInstanceQuery,
  StartProcessInstanceDTO,
  IdDTO,
} from '@/types/api'

function toQueryString(params: Record<string, unknown>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  return entries.length > 0 ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : ''
}

// Backend returns newest-first (sorted by startedAt desc), so no client-side reordering is needed.
export async function getProcessInstances(query: ProcessInstanceQuery = {}): Promise<PagedData<ProcessInstance>> {
  const qs = toQueryString({ pageIndex: 0, pageSize: 10, ...query })
  const { data } = await api.get<PagedData<ProcessInstance>>(`/process-instances${qs}`)
  return data
}

export async function getProcessInstance(id: string): Promise<ProcessInstance> {
  const { data } = await api.get<ProcessInstance>(`/process-instances/${id}`)
  return data
}

export async function getProcessInstanceActivities(id: string): Promise<ActivityInstance[]> {
  const { data } = await api.get<ActivityInstance[]>(`/process-instances/${id}/activities`)
  return data
}

// WO-UI-18 часть C: пагинированный путь (WO-PERF-7, GET .../activities/paged).
// Встроенный SPA использует только его; голый List выше оставлен внешним
// клиентам (публичный контракт, G-C — убирать нельзя).
export async function getProcessInstanceActivitiesPaged(
  id: string, pageIndex = 0, pageSize = 100,
): Promise<PagedData<ActivityInstance>> {
  const qs = toQueryString({ pageIndex, pageSize })
  const { data } = await api.get<PagedData<ActivityInstance>>(`/process-instances/${id}/activities/paged${qs}`)
  return data
}

export async function startProcessInstance(dto: StartProcessInstanceDTO): Promise<IdDTO> {
  const { data } = await api.post<IdDTO>('/process-instances', dto)
  return data
}

// WO-UI-21 Раунд 2: ручная отмена instance. Реальный эндпоинт бэкенда —
// POST /process-instances/{id}/cancel (RuntimeContract.java:68,
// RuntimeResource.cancelProcessInstance → 202 Accepted; 409 если уже
// завершён/отменён, 403 без права DELETE_PROCESS). Параметра reason у
// эндпоинта нет — confirm-диалог без поля причины, причина — follow-up
// с изменением контракта (СТОП-список, см. отчёт).
export async function cancelProcessInstance(id: string): Promise<IdDTO> {
  const { data } = await api.post<IdDTO>(`/process-instances/${id}/cancel`)
  return data
}
