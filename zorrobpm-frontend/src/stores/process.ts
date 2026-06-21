import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  ProcessDefinition,
  ProcessInstance,
  ProcessVariable,
  BpmnProcessStructure,
  PagedData,
  ProcessDefinitionsQuery,
  ProcessInstanceQuery,
  VariableQuery,
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
      const result = await instanceService.getProcessInstances({ pageIndex: 0, pageSize: 1 })
      // Backend doesn't have GET /process-instances/{id}, fetch by filtering
      // TODO: Backend needs GET /process-instances/{id}
      const all = await instanceService.getProcessInstances({ pageIndex: 0, pageSize: 100 })
      currentInstance.value = all.data.find((i) => i.id === id) || null
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load instance'
    } finally {
      loading.value = false
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
  }

  return {
    definitions,
    instances,
    currentDefinition,
    currentInstance,
    currentStructure,
    currentVariables,
    loading,
    error,
    fetchDefinitions,
    fetchDefinition,
    fetchStructure,
    fetchInstances,
    fetchInstance,
    fetchVariables,
    startInstance,
    clearCurrent,
  }
})
