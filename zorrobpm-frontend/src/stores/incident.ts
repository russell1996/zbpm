import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  Incident,
  PagedData,
  IncidentQuery,
  ProcessVariable,
} from '@/types/api'
import * as incidentService from '@/services/incidentService'

export const useIncidentStore = defineStore('incident', () => {
  const incidents = ref<PagedData<Incident> | null>(null)
  const currentIncident = ref<Incident | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchIncidents(query: IncidentQuery = {}) {
    loading.value = true
    error.value = null
    try {
      incidents.value = await incidentService.getIncidents(query)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load incidents'
    } finally {
      loading.value = false
    }
  }

  async function fetchIncident(id: string) {
    loading.value = true
    error.value = null
    try {
      // Backend doesn't have GET /incidents/{id}, search through list
      // TODO: Backend needs GET /incidents/{id}
      const all = await incidentService.getIncidents({ pageIndex: 0, pageSize: 100 })
      currentIncident.value = all.data.find((i) => i.id === id) || null
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
      await incidentService.resolveIncident(id, { id, variables })
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

  return {
    incidents,
    currentIncident,
    loading,
    error,
    fetchIncidents,
    fetchIncident,
    resolveIncident,
    clearCurrent,
  }
})
