import api from './api'
import type {
  ProcessDefinition,
  PagedData,
  ProcessDefinitionsQuery,
  BpmnProcessStructure,
} from '@/types/api'

function toQueryString(params: Record<string, unknown>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  return entries.length > 0 ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : ''
}

export async function getProcessDefinitions(query: ProcessDefinitionsQuery = {}): Promise<PagedData<ProcessDefinition>> {
  const qs = toQueryString({ pageIndex: 0, pageSize: 20, order: 'desc', ...query })
  const { data } = await api.get<PagedData<ProcessDefinition>>(`/process-definitions${qs}`)
  return data
}

export async function getProcessDefinition(id: string): Promise<ProcessDefinition> {
  const { data } = await api.get<ProcessDefinition>(`/process-definitions/${id}`)
  return data
}

/** All versions of a process key (newest first), for the definition's version history. */
export async function getProcessDefinitionVersions(key: string): Promise<ProcessDefinition[]> {
  const qs = toQueryString({ pageIndex: 0, pageSize: 100, processDefinitionKey: key })
  const { data } = await api.get<PagedData<ProcessDefinition>>(`/process-definitions${qs}`)
  return [...data.data].sort((a, b) => b.version - a.version)
}

export async function getProcessDefinitionXml(id: string): Promise<string> {
  const { data } = await api.get<string>(`/process-definitions/${id}/xml`)
  return data
}

export async function getProcessDefinitionStructure(id: string): Promise<BpmnProcessStructure> {
  const { data } = await api.get<BpmnProcessStructure>(`/process-definitions/${id}/structure`)
  return data
}

export async function deployProcessDefinition(bpmn: string): Promise<ProcessDefinition> {
  const { data } = await api.post<ProcessDefinition>('/process-definitions', { bpmn })
  return data
}

/** WO-ACL-4 endpoint surfaced for WO-ACL-8 criterion 5: new version of an EXISTING
 *  process — the server authorizes it by the target definition id (DEPLOY action). */
export async function addProcessDefinitionVersion(id: string, bpmn: string): Promise<ProcessDefinition> {
  const { data } = await api.post<ProcessDefinition>(`/process-definitions/${id}/versions`, { bpmn })
  return data
}

export async function archiveProcess(key: string): Promise<void> {
  await api.post(`/processes/${key}/archive`)
}

export async function unarchiveProcess(key: string): Promise<void> {
  await api.post(`/processes/${key}/unarchive`)
}
