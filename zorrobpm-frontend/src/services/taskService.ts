import api from './api'
import type {
  UserTask,
  ServiceTask,
  PagedData,
  UserTaskQuery,
  ServiceTaskQuery,
  CompleteTaskDTO,
  IdDTO,
} from '@/types/api'

function toQueryString(params: Record<string, unknown>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  return entries.length > 0 ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : ''
}

// Backend returns newest-first (sorted by createdAt desc); no client-side reordering needed.
export async function getUserTasks(query: UserTaskQuery = {}): Promise<PagedData<UserTask>> {
  const qs = toQueryString({ pageIndex: 0, pageSize: 10, ...query })
  const { data } = await api.get<PagedData<UserTask>>(`/user-tasks${qs}`)
  return data
}

export async function getUserTask(id: string): Promise<UserTask> {
  const { data } = await api.get<UserTask>(`/user-tasks/${id}`)
  return data
}

export async function completeUserTask(id: string, dto: CompleteTaskDTO): Promise<IdDTO> {
  const { data } = await api.post<IdDTO>(`/user-tasks/${id}/complete`, dto)
  return data
}

export async function getServiceTasks(query: ServiceTaskQuery = {}): Promise<PagedData<ServiceTask>> {
  const qs = toQueryString({ pageIndex: 0, pageSize: 10, ...query })
  const { data } = await api.get<PagedData<ServiceTask>>(`/service-tasks${qs}`)
  return data
}

export async function getServiceTask(id: string): Promise<ServiceTask> {
  const { data } = await api.get<ServiceTask>(`/service-tasks/${id}`)
  return data
}

export async function completeServiceTask(id: string, dto: CompleteTaskDTO): Promise<IdDTO> {
  const { data } = await api.post<IdDTO>(`/service-tasks/${id}/complete`, dto)
  return data
}
