export interface PagedData<T> {
  pageIndex: number
  pageSize: number
  totalElements: number
  data: T[]
}

export interface IdDTO {
  id: string
}

export interface ProcessDefinition {
  id: string
  key: string
  version: number
  name: string
  sha256: string
  createdAt: string
  startFormKey: string | null
}

export interface ProcessInstance {
  id: string
  parentActivityId: string | null
  processDefinitionId: string
  startedAt: string
  completedAt: string | null
}

export interface UserTask {
  id: string
  code: string | null
  name: string | null
  processInstanceId: string
  processDefinitionId: string
  formKey: string | null
  createdAt: string
  completedAt: string | null
}

export interface ServiceTask {
  id: string
  code: string | null
  name: string | null
  processInstanceId: string
  processDefinitionId: string
  job: string
  createdAt: string
  completedAt: string | null
}

export interface Incident {
  id: string
  activityId: string
  message: string
  createdAt: string
  completedAt: string | null
}

export interface TimerJob {
  id: string
  activityId: string | null
  processInstanceId: string
  dueAt: string
  fired: boolean
  boundaryElementId: string | null
  eventSubprocessId: string | null
  createdAt: string
}

export type ProcessVariableType = 'STRING' | 'UUID' | 'LONG' | 'BOOLEAN'

export interface ProcessVariable {
  name: string
  value: string
  type: ProcessVariableType
}

export interface BpmnNode {
  id: string
  name: string | null
  type: string
  eventDefinition: string | null
  incoming: string[]
  outgoing: string[]
  properties: Record<string, unknown>
  boundaryEvents: BpmnNode[]
  children: { nodes: BpmnNode[]; flows: BpmnFlow[] } | null
}

export interface BpmnFlow {
  id: string
  name: string | null
  sourceRef: string
  targetRef: string
  conditionExpression: string | null
}

export interface BpmnProcessStructure {
  id: string
  key: string
  version: number
  name: string
  nodes: BpmnNode[]
  flows: BpmnFlow[]
}

// Query params
export interface ProcessDefinitionsQuery {
  pageIndex?: number
  pageSize?: number
  name?: string
  processDefinitionKey?: string
  processDefinitionVersion?: number
  latestVersionOnly?: boolean
}

export interface ProcessInstanceQuery {
  pageIndex?: number
  pageSize?: number
  processDefinitionId?: string
  processDefinitionKey?: string
  processDefinitionVersion?: number
  parentProcessInstanceId?: string
}

export interface UserTaskQuery {
  pageIndex?: number
  pageSize?: number
  processInstanceId?: string
  assignee?: string
  candidateGroup?: string
  candidateUser?: string
  completed?: boolean
  assigned?: boolean
}

export interface ServiceTaskQuery {
  pageIndex?: number
  pageSize?: number
  processInstanceId?: string
  jobType?: string
  completed?: boolean
}

export interface IncidentQuery {
  pageIndex?: number
  pageSize?: number
  processInstanceId?: string
  processDefinitionId?: string
  processDefinitionKey?: string
  processDefinitionVersion?: number
  bpmnElementId?: string
}

export interface VariableQuery {
  pageIndex?: number
  pageSize?: number
  processInstanceId?: string
  name?: string
  type?: ProcessVariableType
  value?: string
}

// Action DTOs
export interface StartProcessInstanceDTO {
  processDefinitionId?: string
  processDefinitionKey?: string
  processDefinitionVersion?: number
  variables: ProcessVariable[]
}

export interface CompleteTaskDTO {
  variables: ProcessVariable[]
}

export interface ResolveIncidentDTO {
  id: string
  variables: ProcessVariable[]
}

// Dashboard UI Resource API
export interface DashboardData {
  activeProcessInstances: number
  openUserTasks: number
  openServiceTasks: number
  openIncidents: number
  completedToday: number
  totalProcessDefinitions: number
  recentDefinitions: DashboardProcessDefinition[]
  recentInstances: DashboardProcessInstance[]
  recentIncidents: DashboardIncident[]
}

export interface DashboardProcessDefinition {
  id: string
  key: string
  name: string
  version: number
  createdAt: string
}

export interface DashboardProcessInstance {
  id: string
  processDefinitionId: string
  processDefinitionName: string | null
  startedAt: string
  completedAt: string | null
}

export interface DashboardIncident {
  id: string
  message: string
  createdAt: string
  completedAt: string | null
}
