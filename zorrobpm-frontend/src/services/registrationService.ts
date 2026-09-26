import api from './api'

export interface RegisterPayload {
  username: string
  password: string
  fullName?: string
  email: string
}

export async function register(payload: RegisterPayload): Promise<void> {
  await api.post('/auth/register', payload)
}

export async function verifyEmail(token: string): Promise<void> {
  await api.post('/auth/verify-email', { token })
}

export interface PendingRegistration {
  id: string
  username: string
  fullName: string | null
  email: string
  createdAt: string
  registrationStatus: string
}

export async function getPendingRegistrations(): Promise<PendingRegistration[]> {
  const { data } = await api.get<PendingRegistration[]>('/admin/registrations')
  return data
}

export async function approveRegistration(id: string): Promise<void> {
  await api.post(`/admin/registrations/${id}/approve`)
}

export async function rejectRegistration(id: string, reason?: string): Promise<void> {
  await api.post(`/admin/registrations/${id}/reject`, { reason })
}
