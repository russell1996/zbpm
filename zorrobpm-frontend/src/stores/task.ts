import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  UserTask,
  ServiceTask,
  ProcessVariable,
  PagedData,
  UserTaskQuery,
  ServiceTaskQuery,
  EventEnvelope,
} from '@/types/api'
import * as taskService from '@/services/taskService'
import * as variableService from '@/services/variableService'

export const useTaskStore = defineStore('task', () => {
  const userTasks = ref<PagedData<UserTask> | null>(null)
  const serviceTasks = ref<PagedData<ServiceTask> | null>(null)
  const currentTask = ref<UserTask | null>(null)
  const currentServiceTask = ref<ServiceTask | null>(null)
  const currentTaskVariables = ref<ProcessVariable[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  // WO-UI-18 часть B (Finding #5): монотонные id запросов. Быстрое двойное
  // переключение фильтра + переупорядоченные ответы раньше показывали данные
  // устаревшего фильтра поверх актуального — ответа не от последнего запроса
  // теперь игнорируется целиком (включая error/loading, чтобы устаревший
  // fulfilled не гасил спиннер актуального in-flight запроса).
  let userTasksRequest = 0
  let serviceTasksRequest = 0

  async function fetchUserTasks(query: UserTaskQuery = {}) {
    const myRequest = ++userTasksRequest
    loading.value = true
    error.value = null
    try {
      const result = await taskService.getUserTasks(query)
      if (myRequest !== userTasksRequest) return
      userTasks.value = result
    } catch (e) {
      if (myRequest !== userTasksRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load user tasks'
    } finally {
      if (myRequest === userTasksRequest) loading.value = false
    }
  }

  async function fetchUserTask(id: string) {
    loading.value = true
    error.value = null
    try {
      currentTask.value = await taskService.getUserTask(id)
      if (currentTask.value) {
        await fetchTaskVariables(currentTask.value.processInstanceId)
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load task'
    } finally {
      loading.value = false
    }
  }

  async function fetchTaskVariables(processInstanceId: string) {
    try {
      const result = await variableService.getVariables({ processInstanceId })
      currentTaskVariables.value = result.data
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load variables'
    }
  }

  async function completeUserTask(id: string, variables: ProcessVariable[]) {
    loading.value = true
    error.value = null
    try {
      await taskService.completeUserTask(id, { variables })
      currentTask.value = null
      currentTaskVariables.value = []
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to complete task'
    } finally {
      loading.value = false
    }
  }

  async function fetchServiceTasks(query: ServiceTaskQuery = {}) {
    const myRequest = ++serviceTasksRequest
    loading.value = true
    error.value = null
    try {
      const result = await taskService.getServiceTasks(query)
      if (myRequest !== serviceTasksRequest) return
      serviceTasks.value = result
    } catch (e) {
      if (myRequest !== serviceTasksRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load service tasks'
    } finally {
      if (myRequest === serviceTasksRequest) loading.value = false
    }
  }

  async function fetchServiceTask(id: string) {
    loading.value = true
    error.value = null
    try {
      currentServiceTask.value = await taskService.getServiceTask(id)
      if (currentServiceTask.value) {
        await fetchTaskVariables(currentServiceTask.value.processInstanceId)
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load service task'
    } finally {
      loading.value = false
    }
  }

  async function completeServiceTask(id: string, variables: ProcessVariable[]) {
    loading.value = true
    error.value = null
    try {
      await taskService.completeServiceTask(id, { variables })
      currentServiceTask.value = null
      currentTaskVariables.value = []
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to complete service task'
    } finally {
      loading.value = false
    }
  }

  function clearCurrent() {
    currentTask.value = null
    currentServiceTask.value = null
    currentTaskVariables.value = []
  }

  function handleEvent(envelope: EventEnvelope) {
    switch (envelope.type) {
      case 'user-task.created':
      case 'user-task.completed':
        fetchUserTasks()
        break
      case 'service-task.created':
        fetchServiceTasks()
        break
    }
  }

  return {
    userTasks,
    serviceTasks,
    currentTask,
    currentServiceTask,
    currentTaskVariables,
    loading,
    error,
    fetchUserTasks,
    fetchUserTask,
    fetchTaskVariables,
    completeUserTask,
    fetchServiceTasks,
    fetchServiceTask,
    completeServiceTask,
    clearCurrent,
    handleEvent,
  }
})
