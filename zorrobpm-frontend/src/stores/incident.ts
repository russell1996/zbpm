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

export const useIncidentStore = defineStore('incident', () => {
  const incidents = ref<PagedData<Incident> | null>(null)
  const currentIncident = ref<Incident | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  // WO-UI-18 часть B (критерий 6): тот же паттерн, что в task.ts — отклик
  // не от последнего запроса игнорируется, иначе IncidentList с быстрым
  // переключением фильтра показывает устаревшие данные.
  let incidentsRequest = 0

  async function fetchIncidents(query: IncidentQuery = {}) {
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
    switch (envelope.type) {
      case 'incident.raised':
      case 'incident.resolved':
        fetchIncidents()
        break
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
  }
})
