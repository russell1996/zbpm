import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  ProcessDefinition,
  ProcessInstance,
  ActivityInstance,
  ProcessVariable,
  BpmnProcessStructure,
  PagedData,
  ProcessDefinitionsQuery,
  ProcessInstanceQuery,
  VariableQuery,
  EventEnvelope,
} from '@/types/api'
import * as processService from '@/services/processService'
import * as instanceService from '@/services/instanceService'
import * as variableService from '@/services/variableService'

export const useProcessStore = defineStore('process', () => {
  const definitions = ref<PagedData<ProcessDefinition> | null>(null)
  const instances = ref<PagedData<ProcessInstance> | null>(null)
  const currentDefinition = ref<ProcessDefinition | null>(null)
  const currentInstance = ref<ProcessInstance | null>(null)
  const currentStructure = ref<BpmnProcessStructure | null>(null)
  const currentVariables = ref<ProcessVariable[]>([])
  const currentActivities = ref<ActivityInstance[]>([])
  const currentVersions = ref<ProcessDefinition[]>([])
  const currentSubprocesses = ref<ProcessInstance[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchDefinitions(query: ProcessDefinitionsQuery = {}) {
    loading.value = true
    error.value = null
    try {
      definitions.value = await processService.getProcessDefinitions(query)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load definitions'
    } finally {
      loading.value = false
    }
  }

  async function fetchDefinition(id: string) {
    loading.value = true
    error.value = null
    try {
      currentDefinition.value = await processService.getProcessDefinition(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load definition'
    } finally {
      loading.value = false
    }
  }

  async function fetchStructure(id: string) {
    try {
      currentStructure.value = await processService.getProcessDefinitionStructure(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load structure'
    }
  }

  async function fetchVersions(key: string) {
    try {
      currentVersions.value = await processService.getProcessDefinitionVersions(key)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load versions'
    }
  }

  async function fetchInstances(query: ProcessInstanceQuery = {}) {
    loading.value = true
    error.value = null
    try {
      instances.value = await instanceService.getProcessInstances(query)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load instances'
    } finally {
      loading.value = false
    }
  }

  async function fetchInstance(id: string) {
    loading.value = true
    error.value = null
    try {
      currentInstance.value = await instanceService.getProcessInstance(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load instance'
    } finally {
      loading.value = false
    }
  }

  async function fetchActivities(id: string) {
    try {
      currentActivities.value = await instanceService.getProcessInstanceActivities(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load activities'
    }
  }

  async function fetchSubprocesses(id: string) {
    try {
      const result = await instanceService.getProcessInstances({ parentProcessInstanceId: id, pageIndex: 0, pageSize: 100 })
      currentSubprocesses.value = result.data
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load subprocesses'
    }
  }

  async function fetchVariables(query: VariableQuery) {
    try {
      const result = await variableService.getVariables(query)
      currentVariables.value = result.data
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load variables'
    }
  }

  async function startInstance(dto: { processDefinitionId?: string; processDefinitionKey?: string; variables: ProcessVariable[] }) {
    loading.value = true
    error.value = null
    try {
      const result = await instanceService.startProcessInstance(dto)
      return result.id
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to start instance'
      return null
    } finally {
      loading.value = false
    }
  }

  function clearCurrent() {
    currentDefinition.value = null
    currentInstance.value = null
    currentStructure.value = null
    currentVariables.value = []
    currentActivities.value = []
    currentVersions.value = []
    currentSubprocesses.value = []
  }

  function handleEvent(envelope: EventEnvelope) {
    switch (envelope.type) {
      case 'process-instance.started':
      case 'process-instance.completed':
      case 'process-instance.cancelled':
        fetchInstances()
        break
    }
  }

  return {
    definitions,
    instances,
    currentDefinition,
    currentInstance,
    currentStructure,
    currentVariables,
    currentActivities,
    currentVersions,
    currentSubprocesses,
    loading,
    error,
    fetchDefinitions,
    fetchDefinition,
    fetchStructure,
    fetchVersions,
    fetchInstances,
    fetchInstance,
    fetchActivities,
    fetchSubprocesses,
    fetchVariables,
    startInstance,
    clearCurrent,
    handleEvent,
  }
})
