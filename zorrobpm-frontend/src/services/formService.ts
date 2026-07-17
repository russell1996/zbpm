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

export async function getForm(key: string): Promise<FormSummary> {
  const { data } = await api.get<FormSummary>(`/forms/${key}`)
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

export interface ElementBinding {
  id: string
  elementId: string
  artifactKey: string
  artifactVersion: number
  processDefinitionId: string
  processDefinitionVersion: number
}

export async function createElementBinding(processKey: string, elementId: string, artifactKey: string): Promise<ElementBinding> {
  const { data } = await api.post<ElementBinding>(`/process-definitions/${processKey}/element-bindings`, { elementId, artifactKey })
  return data
}

export interface SchemaMapElement {
  elementId: string
  name: string | null
  type: string
  artifactKey: string | null
  kind: ArtifactKind | null
  artifactVersion: number | null
  hasExternalReference: boolean
  shared: boolean
}

export interface SchemaMap {
  processDefinitionKey: string
  version: number
  elements: SchemaMapElement[]
}

export async function getSchemaMap(processKey: string): Promise<SchemaMap> {
  const { data } = await api.get<SchemaMap>(`/process-definitions/${processKey}/schema-map`)
  return data
}

export async function saveElementSchema(
  processKey: string,
  elementId: string,
  kind: ArtifactKind,
  schema: string,
): Promise<SchemaMapElement> {
  const { data } = await api.post<SchemaMapElement>(
    `/process-definitions/${processKey}/elements/${elementId}/schema`,
    { kind, schema },
  )
  return data
}

export interface SchemaField {
  key: string
  label?: string
  type: string
  required?: boolean
  enum?: string[]
  min?: number
  max?: number
  maxLength?: number
  pattern?: string
  itemsType?: string
  minItems?: number
  maxItems?: number
}

export async function generateSchema(fields: SchemaField[]): Promise<string> {
  const { data } = await api.post<{ schema: string }>('/variable-schemas/generate', { fields })
  return data.schema
}
