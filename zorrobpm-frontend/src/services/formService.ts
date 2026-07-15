import api from './api'

export type ArtifactKind = 'FORM_JS' | 'VARIABLE_SCHEMA'

export interface TaskFormResponse {
  type: 'embedded' | 'external' | 'none'
  kind?: ArtifactKind
  schema?: Record<string, unknown>
  data?: Record<string, string>
  url?: string
}

export interface FormSummary {
  key: string
  version: number
  kind: ArtifactKind
  schema: string
}

export async function listForms(): Promise<FormSummary[]> {
  const { data } = await api.get<FormSummary[]>('/forms')
  return data
}

export async function getTaskForm(taskId: string): Promise<TaskFormResponse> {
  const { data } = await api.get<TaskFormResponse>(`/user-tasks/${taskId}/form`)
  return data
}

export async function getStartForm(processKey: string): Promise<TaskFormResponse> {
  const { data } = await api.get<TaskFormResponse>(`/process-definitions/${processKey}/start-form`)
  return data
}

export async function deployForm(key: string, schema: string, kind: ArtifactKind): Promise<void> {
  await api.post('/forms', { key, schema, kind })
}
