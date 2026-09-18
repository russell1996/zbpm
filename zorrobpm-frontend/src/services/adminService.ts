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
  // WO-INT-4 criterion 6: SYSTEM accounts are marked in the members list
  isSystem?: boolean
}

export async function listMembers(processKey: string): Promise<Member[]> {
  const { data } = await api.get<Member[]>(`/processes/${processKey}/members`)
  return data
}

// --- WO-ACL-11 criteria 17-19: add members straight from the process card ---
// WO-ACL-7 endpoint: GET /processes/{key}/members/candidates?q= — OWNER-scoped
// (MANAGE_MEMBERS), active users only, members excluded, result capped at 20,
// requires q >= 3 chars. The /users directory stays closed from this screen.
// WO-ACL-15: fullName/email come as EMPTY STRINGS (never null) when the account
// has none — the dialog renders absence, not the literal "null".
export interface MemberCandidate {
  userId: string
  username: string
  fullName: string
  email: string
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

// --- WO-INT-6: in-app mail settings management (super-admin only) ---
export interface MailHealth {
  configured: boolean
  reachable: boolean | null
  lastSuccess: string | null
  lastError: string | null
  lastErrorMessage: string | null
}

export interface MailSettings {
  host: string | null
  port: number | null
  username: string | null
  password: string | null
  from: string | null
  allowedRecipients: string | null
  passwordSet: boolean
}

export interface MailSettingsUpdate {
  host?: string | null
  port?: number | null
  username?: string | null
  password?: string | null
  from?: string | null
  allowedRecipients?: string | null
}

// WO-INT-8: result of the pre-save connectivity check. No ready-made message from the backend —
// the frontend localizes based on `reachable`/`errorCode` (G18: en/ru/kz).
export interface MailCheckResult {
  reachable: boolean
  errorCode: string | null
}

export async function getMailHealth(): Promise<MailHealth> {
  const { data } = await api.get<MailHealth>('/admin/mail/health')
  return data
}

export async function getMailSettings(): Promise<MailSettings> {
  const { data } = await api.get<MailSettings>('/admin/mail/settings')
  return data
}

export async function saveMailSettings(dto: MailSettingsUpdate): Promise<MailSettings> {
  const { data } = await api.put<MailSettings>('/admin/mail/settings', dto)
  return data
}

// WO-INT-8: pre-save check — current (possibly unsaved) form values, sends no email.
export async function checkMailSettings(dto: MailSettingsUpdate): Promise<MailCheckResult> {
  const { data } = await api.post<MailCheckResult>('/admin/mail/check', dto)
  return data
}

// WO-INT-8: post-save real send — no body: uses the saved config, no password leaves the browser.
export async function testMailSettingsToSelf(): Promise<void> {
  await api.post('/admin/mail/test-self')
}
