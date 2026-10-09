import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  Incident,
  PagedData,
  IncidentQuery,
  ProcessVariable,
  EventEnvelope,
} from '@/types/api'
import * as incidentService from '@/services/incidentService'
import { createPatchTracker } from '@/services/realtimePatch'
import { scheduleListRefresh } from '@/services/realtimeScheduler'

export const useIncidentStore = defineStore('incident', () => {
  const incidents = ref<PagedData<Incident> | null>(null)
  const currentIncident = ref<Incident | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  // WO-UI-18 часть B (критерий 6): тот же паттерн, что в task.ts — отклик
  // не от последнего запроса игнорируется, иначе IncidentList с быстрым
  // переключением фильтра показывает устаревшие данные.
  let incidentsRequest = 0

  // WO-REL-60: тот же lastQuery-паттерн, что в task.ts — live-refetch
  // повторяет текущий фильтр страницы (resolved:false), иначе resolved-
  // инцидент возвращается в список после incident.resolved.
  let lastIncidentsQuery: IncidentQuery = {}

  // WO-UI-26 Доп.4: трекер порядка/дедупа событий.
  const patchTracker = createPatchTracker()

  /** WO-UI-26 Доп.2: тихий фоновый refresh (без loading, merge по ключу). */
  function mergeIncidents(patch: PagedData<Incident>): void {
    const cur = incidents.value
    if (!cur) {
      incidents.value = patch
      return
    }
    const byId = new Map(cur.data.map((i) => [i.id, i]))
    let changed = false
    for (const row of patch.data) {
      if (byId.get(row.id) !== row) {
        byId.set(row.id, row)
        changed = true
      }
    }
    if (changed || cur.totalElements !== patch.totalElements) {
      incidents.value = { ...patch, data: [...byId.values()] }
    }
  }

  /** WO-UI-26 Доп.2/Доп.4: тихий запасной refetch (без loading, merge по ключу). */
  async function refreshIncidentsQuiet(): Promise<void> {
    const myRequest = ++incidentsRequest
    try {
      const result = await incidentService.getIncidents(lastIncidentsQuery)
      if (myRequest !== incidentsRequest) return
      mergeIncidents(result)
    } catch (e) {
      if (myRequest !== incidentsRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load incidents'
    }
  }

  /**
   * WO-UI-26 Доп.4: адресный патч одного инцидента (одна строка/бейдж),
   * 0 запросов списка. False — нужен запасной путь.
   */
  function patchIncidentRow(incident: Incident): boolean {
    const cur = incidents.value
    if (!cur) return false
    const q = lastIncidentsQuery
    if (q.resolved === false && incident.completedAt) return false
    if (q.resolved === true && !incident.completedAt) return false
    if (q.processInstanceId && incident.processInstanceId !== q.processInstanceId) return false
    const idx = cur.data.findIndex((i) => i.id === incident.id)
    if (idx === -1) {
      if ((q.pageIndex ?? 0) !== 0) {
        incidents.value = { ...cur, totalElements: cur.totalElements + 1 }
        return true
      }
      incidents.value = { ...cur, data: [incident, ...cur.data], totalElements: cur.totalElements + 1 }
      return true
    }
    if (cur.data[idx] === incident) return true
    const data = [...cur.data]
    data[idx] = incident
    incidents.value = { ...cur, data }
    return true
  }

  async function fetchIncidents(query: IncidentQuery = {}) {
    lastIncidentsQuery = query
    const myRequest = ++incidentsRequest
    loading.value = true
    error.value = null
    try {
      const result = await incidentService.getIncidents(query)
      if (myRequest !== incidentsRequest) return
      incidents.value = result
    } catch (e) {
      if (myRequest !== incidentsRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load incidents'
    } finally {
      if (myRequest === incidentsRequest) loading.value = false
    }
  }

  async function fetchIncident(id: string) {
    loading.value = true
    error.value = null
    try {
      currentIncident.value = await incidentService.getIncident(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load incident'
    } finally {
      loading.value = false
    }
  }

  async function resolveIncident(id: string, variables: ProcessVariable[] = []) {
    loading.value = true
    error.value = null
    try {
      await incidentService.resolveIncident(id, { variables })
      currentIncident.value = null
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to resolve incident'
    } finally {
      loading.value = false
    }
  }

  function clearCurrent() {
    currentIncident.value = null
  }

  function handleEvent(envelope: EventEnvelope) {
    // WO-UI-26 Доп.4: событие → адресный патч; полный refetch — запасной.
    const patchable = envelope.type === 'incident.raised' || envelope.type === 'incident.resolved'
    if (patchable && !patchTracker.shouldPatch(envelope)) {
      const seq = typeof envelope.sequence === 'number' ? envelope.sequence : 0
      if (seq > 0 && seq > patchTracker.lastSequence() + 1 && patchTracker.lastSequence() > 0) {
        scheduleListRefresh('incidents', () => refreshIncidentsQuiet())
      }
      return
    }
    switch (envelope.type) {
      case 'incident.raised':
      case 'incident.resolved': {
        // incidentId есть в data — один GET сущности.
        const incidentId = envelope.data?.['incidentId']
        if (typeof incidentId === 'string' && incidentId) {
          void incidentService
            .getIncident(incidentId)
            .then((incident) => {
              patchTracker.markApplied(envelope)
              if (!patchIncidentRow(incident)) {
                scheduleListRefresh('incidents', () => refreshIncidentsQuiet())
              }
            })
            .catch(() => {
              scheduleListRefresh('incidents', () => refreshIncidentsQuiet())
            })
        } else {
          patchTracker.markApplied(envelope)
          scheduleListRefresh('incidents', () => refreshIncidentsQuiet())
        }
        break
      }
    }
  }

  return {
    incidents,
    currentIncident,
    loading,
    error,
    fetchIncidents,
    fetchIncident,
    resolveIncident,
    clearCurrent,
    handleEvent,
    // WO-UI-26 Доп.2/Доп.4: тихие точечные обновления (тесты + планировщик).
    refreshIncidentsQuiet,
    patchIncidentRow,
    lastSequenceForTest: () => patchTracker.lastSequence(),
  }
})
