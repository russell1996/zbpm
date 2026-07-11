import api from './api'
import type { ServiceAccount, ServiceAccountWithKey, CreateServiceAccountDTO } from '@/types/api'

export async function listServiceAccounts(processKey: string): Promise<ServiceAccount[]> {
  const { data } = await api.get<ServiceAccount[]>(`/processes/${processKey}/service-accounts`)
  return data
}

/**
 * Create a service account. Returns the plaintext key ONCE.
 * The caller MUST display it to the user and then DISCARD it — never store in state/storage.
 */
export async function createServiceAccount(processKey: string, dto: CreateServiceAccountDTO): Promise<ServiceAccountWithKey> {
  const { data } = await api.post<ServiceAccountWithKey>(`/processes/${processKey}/service-accounts`, dto)
  return data
}

/**
 * Rotate an API key. Returns the new plaintext key ONCE.
 * Previous key becomes invalid immediately.
 */
export async function rotateServiceAccountKey(processKey: string, saId: string): Promise<ServiceAccountWithKey> {
  const { data } = await api.post<ServiceAccountWithKey>(`/processes/${processKey}/service-accounts/${saId}/rotate`)
  return data
}

export async function revokeServiceAccount(processKey: string, saId: string): Promise<void> {
  await api.post(`/processes/${processKey}/service-accounts/${saId}/revoke`)
}
