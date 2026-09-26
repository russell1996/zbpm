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
  archived?: boolean
}

export interface ProcessInstance {
  id: string
  parentActivityId: string | null
  processDefinitionId: string
  processName: string | null
  processKey: string | null
  processVersion: number | null
  startedAt: string
  completedAt: string | null
  // WO-UI-21 Раунд 2: бэкенд реально шлёт это поле
  // (zorrobpm-contract .../model/ProcessInstance.java:12, `boolean cancelled`),
  // фронт до этого раунда его игнорировал.
  cancelled: boolean
}

export type ActivityLifecycleStatus = 'CREATED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'ERROR'

export interface UserTask {
  id: string
  code: string | null
  name: string | null
  processInstanceId: string
  processDefinitionId: string
  formKey: string | null
  status: ActivityLifecycleStatus | null
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
  status: ActivityLifecycleStatus | null
  createdAt: string
  completedAt: string | null
}

export interface Incident {
  id: string
  activityId: string
  message: string
  createdAt: string
  completedAt: string | null
  processName: string | null
  processInstanceId: string | null
  bpmnElementId: string | null
  elementName: string | null
}

export interface ActivityInstance {
  id: string
  processInstanceId: string
  bpmnElementId: string
  type: string | null
  status: string | null
  createdAt: string
  completedAt: string | null
}

export interface TimerJob {
  id: string
  activityId: string | null
  processInstanceId: string | null
  dueAt: string
  fired: boolean
  boundaryElementId: string | null
  eventSubprocessId: string | null
  createdAt: string
}

export interface MessageSubscription {
  id: string
  processInstanceId: string | null
  activityId: string | null
  messageName: string
  consumed: boolean
  boundaryElementId: string | null
  eventSubprocessId: string | null
  correlationKey: string | null
  createdAt: string
}

export type ProcessVariableType = 'STRING' | 'UUID' | 'LONG' | 'DOUBLE' | 'BOOLEAN' | 'JSON'

export interface ProcessVariable {
  name: string
  value: string
  type: ProcessVariableType
  activityId?: string | null
}

export interface BpmnNode {
  id: string
  name: string | null
  type: string
  eventDefinition: string | null
  documentation: string | null
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
  documentation: string | null
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
  order?: 'asc' | 'desc'
  includeArchived?: boolean
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
  resolved?: boolean
}

export interface VariableQuery {
  pageIndex?: number
  pageSize?: number
  processInstanceId?: string
  name?: string
  type?: ProcessVariableType
  value?: string
  activityId?: string
}

export interface TimerJobQuery {
  pageIndex?: number
  pageSize?: number
  processInstanceId?: string
  fired?: boolean
}

export interface MessageSubscriptionQuery {
  pageIndex?: number
  pageSize?: number
  processInstanceId?: string
  consumed?: boolean
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
  variables: ProcessVariable[]
}

/** SSE domain event envelope (ADR-7, WO-EVT-5) */
export interface EventEnvelope {
  sequence: number
  id: string
  type: string
  version: number
  occurredAt: string
  processDefinitionId?: string
  processDefinitionKey?: string
  processInstanceId?: string
  elementId?: string
  ownerScope?: string
  data: Record<string, unknown>
}
