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

/** WO-SEC-58: self-service password change (PUT /me/password). */
export async function changeMyPassword(currentPassword: string, newPassword: string): Promise<IdDTO> {
  const { data } = await api.put<IdDTO>('/me/password', { currentPassword, newPassword })
  return data
}

/**
 * WO-ACL-18 criterion 12: request a password-reset link.
 * Enumeration-safe by design — the server returns 200 regardless of whether the email exists,
 * so this call never leaks account existence.
 */
export async function requestPasswordReset(email: string): Promise<void> {
  await api.post('/auth/forgot-password', { email })
}

/** WO-ACL-18: consume a one-time reset/invitation token and set a new password. */
export async function resetPassword(token: string, newPassword: string): Promise<void> {
  await api.post('/auth/reset-password', { token, password: newPassword })
}

/** WO-ACL-18: consume a one-time invitation token and set the account's first password. */
export async function acceptInvitation(token: string, newPassword: string): Promise<void> {
  await api.post('/auth/accept-invitation', { token, password: newPassword })
}

/** WO-ACL-18 criterion 10/11: super-admin triggered a password reset for a user. */
export async function adminResetPassword(userId: string): Promise<void> {
  await api.post(`/users/${userId}/reset-password`)
}