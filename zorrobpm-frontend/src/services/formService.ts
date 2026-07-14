import api from './api'

export interface TaskFormResponse {
  type: 'embedded' | 'external' | 'none'
  schema?: Record<string, unknown>
  data?: Record<string, string>
  url?: string
}

export async function getTaskForm(taskId: string): Promise<TaskFormResponse> {
  const { data } = await api.get<TaskFormResponse>(`/user-tasks/${taskId}/form`)
  return data
}

export async function getStartForm(processKey: string): Promise<TaskFormResponse> {
  const { data } = await api.get<TaskFormResponse>(`/process-definitions/${processKey}/start-form`)
  return data
}
