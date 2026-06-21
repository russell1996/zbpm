import api from './api'
import type {
  Incident,
  PagedData,
  IncidentQuery,
  ResolveIncidentDTO,
  IdDTO,
} from '@/types/api'

function toQueryString(params: Record<string, unknown>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  return entries.length > 0 ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : ''
}

function desc<T>(result: PagedData<T>): PagedData<T> {
  return { ...result, data: [...result.data].reverse() }
}

export async function getIncidents(query: IncidentQuery = {}): Promise<PagedData<Incident>> {
  const qs = toQueryString({ pageIndex: 0, pageSize: 10, ...query })
  const { data } = await api.get<PagedData<Incident>>(`/incidents${qs}`)
  return desc(data)
}

export async function resolveIncident(id: string, dto: ResolveIncidentDTO): Promise<IdDTO> {
  const { data } = await api.post<IdDTO>(`/incidents/${id}/resolve`, dto)
  return data
}
