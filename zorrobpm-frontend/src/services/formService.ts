import api from './api'

export interface TaskFormResponse {
  type: 'embedded' | 'external' | 'none'
  schema?: Record<string, unknown>
  data?: Record<string, string>
  url?: string
}

export interface FormSummary {
  key: string
  version: number
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

export async function deployForm(key: string, schema: string): Promise<void> {
  await api.post('/forms', { key, schema })
}
