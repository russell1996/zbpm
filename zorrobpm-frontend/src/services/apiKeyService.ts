import api from './api'

export interface ApiKeyGrant {
  processId: string
  processKey: string
  permissions: string | null
  full: boolean
}

export interface ApiKeyInfo {
  id: string
  ownerUserId: string
  prefix: string
  createdAt: string
  lastUsedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
  grants: ApiKeyGrant[]
}

export interface ApiKeyWithSecret extends ApiKeyInfo {
  key: string
}

export async function getMyApiKey(): Promise<ApiKeyInfo> {
  const { data } = await api.get<ApiKeyInfo>('/me/api-key')
  return data
}

export async function rotateMyApiKey(): Promise<ApiKeyWithSecret> {
  const { data } = await api.post<ApiKeyWithSecret>('/me/api-key/rotate')
  return data
}

export async function revokeMyApiKey(): Promise<void> {
  await api.post('/me/api-key/revoke')
}
