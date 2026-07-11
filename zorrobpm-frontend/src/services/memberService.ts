import api from './api'
import type { Member, AddMemberDTO, ChangeRoleDTO } from '@/types/api'

export async function listMembers(processKey: string): Promise<Member[]> {
  const { data } = await api.get<Member[]>(`/processes/${processKey}/members`)
  return data
}

export async function addMember(processKey: string, dto: AddMemberDTO): Promise<Member> {
  const { data } = await api.post<Member>(`/processes/${processKey}/members`, dto)
  return data
}

export async function changeRole(processKey: string, userId: string, dto: ChangeRoleDTO): Promise<Member> {
  const { data } = await api.patch<Member>(`/processes/${processKey}/members/${userId}`, dto)
  return data
}

export async function removeMember(processKey: string, userId: string): Promise<void> {
  await api.delete(`/processes/${processKey}/members/${userId}`)
}
