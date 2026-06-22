import api from './api'
import type { User, LoginDTO, AuthResponse } from '@/entities/user/User'

export async function login(dto: LoginDTO): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>('/auth/login', dto)
  return data
}

export async function getMe(): Promise<User> {
  const { data } = await api.get<User>('/auth/me')
  return data
}
