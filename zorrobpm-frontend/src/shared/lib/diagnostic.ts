import type {
  ProcessInstance,
  ActivityInstance,
  ProcessVariable,
  UserTask,
  ServiceTask,
  Incident,
} from '@/types/api'

// ──────────────────────────────────────────────
// Public types exported for tests (P-28 gate)
// ──────────────────────────────────────────────

export interface DiagnosticInstance {
  id: string
  processKey: string | null
  processName: string | null
  version: number | null
  status: 'running' | 'completed'
  startedAt: string
  completedAt: string | null
  businessKeyIfAny: string | null
}

export interface DiagnosticActiveElement {
  token: string
  bpmnElementId: string
  type: string | null
  status: string | null
}

export interface DiagnosticToken {
  token: string
  firstSeenAt: string
  lastEventAt: string
  active: boolean
  path: string[]
}

export interface DiagnosticTimelineEntry {
  seq: number
  token: string
  bpmnElementId: string
  type: string | null
  status: string | null
  createdAt: string
  completedAt: string | null
  assignee: string | null
  formKey: string | null
  incidentMessage: string | null
}

export interface DiagnosticExportVariable {
  name: string
  type: string
  value: string
}

export interface DiagnosticExportIncident {
  id: string
  activityId: string
  bpmnElementId: string | null
  message: string
  createdAt: string
  resolvedAt: string | null
}

export interface DiagnosticExportUserTask {
  id: string
  code: string | null
  name: string | null
  status: string | null
  assignee: string | null
  completedAt: string | null
}

export interface DiagnosticExportServiceTask {
  id: string
  code: string | null
  name: string | null
  status: string | null
  completedAt: string | null
}

export interface DiagnosticExportDiagnostics {
  activeTokenCount: number
  activeElements: DiagnosticActiveElement[]
  openIncidents: number
  anomalies: string[]
}

export interface DiagnosticJson {
  schemaVersion: number
  generatedAt: string
  instance: DiagnosticInstance
  diagnostics: DiagnosticExportDiagnostics
  tokens: DiagnosticToken[]
  timeline: DiagnosticTimelineEntry[]
  variables: DiagnosticExportVariable[]
  incidents: DiagnosticExportIncident[]
  userTasks: DiagnosticExportUserTask[]
  serviceTasks: DiagnosticExportServiceTask[]
  bpmnXml: string
}

export interface DiagnosticInput {
  instance: ProcessInstance
  activities: ActivityInstance[]
  variables: ProcessVariable[]
  userTasks: UserTask[]
  serviceTasks: ServiceTask[]
  incidents: Incident[]
  bpmnXml: string
}

// ──────────────────────────────────────────────
// Pure function — fully testable, no side-effects
// ──────────────────────────────────────────────

export function buildDiagnosticJson(input: DiagnosticInput): DiagnosticJson {
  const {
    instance,
    activities,
    variables,
    userTasks,
    serviceTasks,
    incidents,
    bpmnXml,
  } = input

  const generatedAt = new Date().toISOString()
  const isCompleted = instance.completedAt !== null

  // ── Active tokens ──
  const activeActivities = activities.filter(
    (a) => a.status === 'CREATED' || a.status === 'IN_PROGRESS',
  )
  const activeTokenCount = activeActivities.length

  // ── Active elements ──
  const activeElements: DiagnosticActiveElement[] = activeActivities.map((a) => ({
    token: `${a.bpmnElementId}::${a.id}`,
    bpmnElementId: a.bpmnElementId,
    type: a.type,
    status: a.status,
  }))

  // ── Open incidents ──
  const openIncidentsList = incidents.filter((inc) => !inc.completedAt)
  const openIncidentCount = openIncidentsList.length

  // ── Anomalies ──
  const anomalies: string[] = []
  if (isCompleted && activeTokenCount > 0) {
    anomalies.push(
      `Instance status=COMPLETED, but active elements: ${activeTokenCount}`,
    )
  }
  if (openIncidentCount > 0) {
    anomalies.push(`Open incidents: ${openIncidentCount}`)
  }
  if (isCompleted && activeTokenCount > 0) {
    anomalies.push(
      `Active tokens: ${activeTokenCount} for completed instance`,
    )
  }

  // ── Tokens (derived from activities) ──
  const tokens: DiagnosticToken[] = activities.map((a) => ({
    token: `${a.bpmnElementId}::${a.id}`,
    firstSeenAt: a.createdAt,
    lastEventAt: a.completedAt || a.createdAt,
    active: a.status === 'CREATED' || a.status === 'IN_PROGRESS',
    path: [a.bpmnElementId],
  }))

  // ── Timeline: merge activities + tasks + incidents → sort → dedup ──
  interface RawTimelineEntry {
    sortKey: string // ISO date
    entry: DiagnosticTimelineEntry
  }

  const raw: RawTimelineEntry[] = []
  const seen = new Set<string>()

  function addIfNew(
    sortKey: string,
    dedupKey: string,
    entry: Omit<DiagnosticTimelineEntry, 'seq'>,
  ) {
    if (seen.has(dedupKey)) return
    seen.add(dedupKey)
    raw.push({ sortKey, entry: { seq: 0, ...entry } })
  }

  for (const a of activities) {
    addIfNew(a.createdAt, `act:${a.id}`, {
      token: `${a.bpmnElementId}::${a.id}`,
      bpmnElementId: a.bpmnElementId,
      type: a.type,
      status: a.status,
      createdAt: a.createdAt,
      completedAt: a.completedAt,
      assignee: null,
      formKey: null,
      incidentMessage: null,
    })
  }
  for (const ut of userTasks) {
    addIfNew(ut.createdAt, `ut:${ut.id}`, {
      token: `${ut.code || 'ut'}::${ut.id}`,
      bpmnElementId: ut.code || '',
      type: 'userTask',
      status: ut.status,
      createdAt: ut.createdAt,
      completedAt: ut.completedAt,
      assignee: null,
      formKey: ut.formKey,
      incidentMessage: null,
    })
  }
  for (const st of serviceTasks) {
    addIfNew(st.createdAt, `st:${st.id}`, {
      token: `${st.code || 'st'}::${st.id}`,
      bpmnElementId: st.code || '',
      type: 'serviceTask',
      status: st.status,
      createdAt: st.createdAt,
      completedAt: st.completedAt,
      assignee: null,
      formKey: null,
      incidentMessage: null,
    })
  }
  for (const inc of incidents) {
    addIfNew(inc.createdAt, `inc:${inc.id}`, {
      token: `inc::${inc.id}`,
      bpmnElementId: '',
      type: 'incident',
      status: inc.completedAt ? 'resolved' : 'open',
      createdAt: inc.createdAt,
      completedAt: inc.completedAt,
      assignee: null,
      formKey: null,
      incidentMessage: inc.message,
    })
  }

  // Sort by sortKey (createdAt), then preserve insertion order for ties
  raw.sort((a, b) => a.sortKey.localeCompare(b.sortKey))

  const timeline: DiagnosticTimelineEntry[] = raw.map((r, i) => ({
    ...r.entry,
    seq: i,
  }))

  // ── Enrich incidents with bpmnElementId via activities ──
  const actById = new Map(activities.map((a) => [a.id, a.bpmnElementId]))

  // ── Build result ──
  return {
    schemaVersion: 1,
    generatedAt,
    instance: {
      id: instance.id,
      processKey: instance.processKey,
      processName: instance.processName,
      version: instance.processVersion,
      status: isCompleted ? 'completed' : 'running',
      startedAt: instance.startedAt,
      completedAt: instance.completedAt,
      businessKeyIfAny: null, // not available in current store
    },
    diagnostics: {
      activeTokenCount,
      activeElements,
      openIncidents: openIncidentCount,
      anomalies,
    },
    tokens,
    timeline,
    variables: variables.map((v) => ({
      name: v.name,
      type: v.type,
      value: v.value,
    })),
    incidents: incidents.map((inc) => ({
      id: inc.id,
      activityId: inc.activityId,
      bpmnElementId: actById.get(inc.activityId) ?? null,
      message: inc.message,
      createdAt: inc.createdAt,
      resolvedAt: inc.completedAt,
    })),
    userTasks: userTasks.map((t) => ({
      id: t.id,
      code: t.code,
      name: t.name,
      status: t.status,
      assignee: null, // not in current store
      completedAt: t.completedAt,
    })),
    serviceTasks: serviceTasks.map((t) => ({
      id: t.id,
      code: t.code,
      name: t.name,
      status: t.status,
      completedAt: t.completedAt,
    })),
    bpmnXml,
  }
}
