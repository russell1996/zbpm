import api from './api'

// --- Member management ---
export interface Member {
  userId: string
  username: string | null
  role: string
  addedBy: string | null
  addedAt: string
}

export async function listMembers(processKey: string): Promise<Member[]> {
  const { data } = await api.get<Member[]>(`/processes/${processKey}/members`)
  return data
}

export async function addMember(processKey: string, userId: string, role: string): Promise<Member> {
  const { data } = await api.post<Member>(`/processes/${processKey}/members`, { userId, role })
  return data
}

export async function changeMemberRole(processKey: string, userId: string, role: string): Promise<Member> {
  const { data } = await api.patch<Member>(`/processes/${processKey}/members/${userId}`, { role })
  return data
}

export async function removeMember(processKey: string, userId: string): Promise<void> {
  await api.delete(`/processes/${processKey}/members/${userId}`)
}

// --- API Key management ---
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

export async function getApiKey(userId: string): Promise<ApiKeyInfo> {
  const { data } = await api.get<ApiKeyInfo>(`/admin/users/${userId}/api-key`)
  return data
}

export async function createApiKey(userId: string): Promise<ApiKeyWithSecret> {
  const { data } = await api.post<ApiKeyWithSecret>(`/admin/users/${userId}/api-key`)
  return data
}

export async function setGrants(userId: string, grants: { processKey: string; permissions?: string; full?: boolean }[]): Promise<ApiKeyGrant[]> {
  const { data } = await api.put<ApiKeyGrant[]>(`/admin/users/${userId}/api-key/grants`, { grants })
  return data
}

export async function rotateApiKey(userId: string): Promise<ApiKeyWithSecret> {
  const { data } = await api.post<ApiKeyWithSecret>(`/admin/users/${userId}/api-key/rotate`)
  return data
}

export async function revokeApiKey(userId: string): Promise<void> {
  await api.post(`/admin/users/${userId}/api-key/revoke`)
}

// --- Process list (for grant configuration) ---
export interface ProcessInfo {
  id: string
  key: string
  name: string
}

export async function listProcesses(): Promise<ProcessInfo[]> {
  const { data } = await api.get<{ data: ProcessInfo[] }>('/process-definitions?pageSize=100&latestVersionOnly=true')
  return data.data
}
