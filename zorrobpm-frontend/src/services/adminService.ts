import api from './api'

// --- Member management ---
// WO-ACL-6/7: fullName and email come from the backend MemberDTO (added in ACL-7).
export interface Member {
  userId: string
  username: string | null
  fullName: string | null
  email: string | null
  role: string
  addedBy: string | null
  addedAt: string
  processKey?: string
}

export async function listMembers(processKey: string): Promise<Member[]> {
  const { data } = await api.get<Member[]>(`/processes/${processKey}/members`)
  return data
}

// --- WO-ACL-11 criteria 17-19: add members straight from the process card ---
// WO-ACL-7 endpoint: GET /processes/{key}/members/candidates?q= — OWNER-scoped
// (MANAGE_MEMBERS), active users only, members excluded, result capped at 20,
// requires q >= 3 chars. The /users directory stays closed from this screen.
export interface MemberCandidate {
  userId: string
  username: string
}

export async function searchMemberCandidates(processKey: string, q: string): Promise<MemberCandidate[]> {
  const { data } = await api.get<MemberCandidate[]>(`/processes/${processKey}/members/candidates`, { params: { q } })
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

export async function listUserMemberships(userId: string): Promise<Member[]> {
  const { data } = await api.get<Member[]>(`/admin/users/${userId}/memberships`)
  return data
}

/** WO-ACL-7 endpoint surfaced for WO-ACL-8 criterion 8: the caller's OWN memberships
 *  (userId comes from the server-side principal — no cross-user access). */
export async function getMyMemberships(): Promise<Member[]> {
  const { data } = await api.get<Member[]>('/me/memberships')
  return data
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
