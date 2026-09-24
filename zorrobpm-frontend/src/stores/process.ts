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

  // WO-UI-18 часть B (критерий 6): тот же request-id guard, что в task.ts —
  // списки определений/инстансов тоже перезапрашиваются со сменой фильтров
  // (ProcessDefinitionList/ProcessInstanceList), устаревший отклик игнорируется.
  let definitionsRequest = 0
  let instancesRequest = 0
  let activitiesRequest = 0

  // WO-UI-18 часть C: пагинация activities. Серверный лимит страницы —
  // QueryPaginationSupport.MAX_PAGE_SIZE (200); 100 оставляет запас и совпадает
  // с pageSize остальных списков детальной страницы.
  const currentActivitiesTotal = ref(0)
  const currentActivitiesPageSize = 100
  const hasMoreActivities = ref(false)
  // Сколько страниц уже загружено подряд с 0-й: индекс следующей = это число.
  // Считаем явно, а не через длину (короткая страница сервера сломала бы
  // арифметику floor/ceil и привела к повторной загрузке той же страницы).
  let loadedActivityPages = 0

  async function fetchDefinitions(query: ProcessDefinitionsQuery = {}) {
    const myRequest = ++definitionsRequest
    loading.value = true
    error.value = null
    try {
      const result = await processService.getProcessDefinitions(query)
      if (myRequest !== definitionsRequest) return
      definitions.value = result
    } catch (e) {
      if (myRequest !== definitionsRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load definitions'
    } finally {
      if (myRequest === definitionsRequest) loading.value = false
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
    const myRequest = ++instancesRequest
    loading.value = true
    error.value = null
    try {
      const result = await instanceService.getProcessInstances(query)
      if (myRequest !== instancesRequest) return
      instances.value = result
    } catch (e) {
      if (myRequest !== instancesRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load instances'
    } finally {
      if (myRequest === instancesRequest) loading.value = false
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

  // WO-UI-18 часть C (Finding #3): встроенный SPA идёт пагинированным путём
  // GET .../activities/paged вместо голого List. Первая страница подгружается
  // вместе с остальными табами, дальше — fetchMoreActivities() по кнопке.
  // Непагинированный эндпоинт на сервере СОХРАНЁН для внешних клиентов
  // (критерий 8 — см. отчёт; убирать его = ломать публичный контракт, G-C).
  async function fetchActivities(id: string) {
    const myRequest = ++activitiesRequest
    try {
      const page = await instanceService.getProcessInstanceActivitiesPaged(
        id, 0, currentActivitiesPageSize)
      if (myRequest !== activitiesRequest) return
      currentActivities.value = page.data
      currentActivitiesTotal.value = page.totalElements
      loadedActivityPages = 1
      hasMoreActivities.value =
        page.data.length < page.totalElements
    } catch (e) {
      if (myRequest !== activitiesRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load activities'
    }
  }

  async function fetchMoreActivities(id: string) {
    if (!hasMoreActivities.value) return
    const myRequest = ++activitiesRequest
    try {
      const page = await instanceService.getProcessInstanceActivitiesPaged(
        id, loadedActivityPages, currentActivitiesPageSize)
      if (myRequest !== activitiesRequest) return
      currentActivities.value = [...currentActivities.value, ...page.data]
      currentActivitiesTotal.value = page.totalElements
      loadedActivityPages += 1
      hasMoreActivities.value =
        currentActivities.value.length < page.totalElements
    } catch (e) {
      if (myRequest !== activitiesRequest) return
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
    currentActivitiesTotal.value = 0
    hasMoreActivities.value = false
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
    currentActivitiesTotal,
    hasMoreActivities,
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
    fetchMoreActivities,
    fetchSubprocesses,
    fetchVariables,
    startInstance,
    clearCurrent,
    handleEvent,
  }
})
