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

export async function startProcessInstance(dto: StartProcessInstanceDTO): Promise<IdDTO> {
  const { data } = await api.post<IdDTO>('/process-instances', dto)
  return data
}
