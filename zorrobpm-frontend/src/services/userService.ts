import api from './api'
import type { User, CreateUserDTO, UpdateUserDTO } from '@/entities/user/User'
import type { PagedData, IdDTO } from '@/types/api'

function toQueryString(params: Record<string, unknown>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  return entries.length > 0 ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : ''
}

export async function getUsers(params: { pageIndex?: number; pageSize?: number; username?: string; active?: boolean } = {}): Promise<PagedData<User>> {
  const qs = toQueryString({ pageIndex: 0, pageSize: 50, ...params })
  const { data } = await api.get<PagedData<User>>(`/users${qs}`)
  return data
}

export async function getUser(id: string): Promise<User> {
  const { data } = await api.get<User>(`/users/${id}`)
  return data
}

export async function createUser(dto: CreateUserDTO): Promise<IdDTO> {
  const { data } = await api.post<IdDTO>('/users', dto)
  return data
}

export async function updateUser(id: string, dto: UpdateUserDTO): Promise<IdDTO> {
  const { data } = await api.put<IdDTO>(`/users/${id}`, dto)
  return data
}
