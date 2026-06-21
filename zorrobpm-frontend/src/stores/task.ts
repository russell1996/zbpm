import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  UserTask,
  ServiceTask,
  ProcessVariable,
  PagedData,
  UserTaskQuery,
  ServiceTaskQuery,
} from '@/types/api'
import * as taskService from '@/services/taskService'
import * as variableService from '@/services/variableService'

export const useTaskStore = defineStore('task', () => {
  const userTasks = ref<PagedData<UserTask> | null>(null)
  const serviceTasks = ref<PagedData<ServiceTask> | null>(null)
  const currentTask = ref<UserTask | null>(null)
  const currentTaskVariables = ref<ProcessVariable[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchUserTasks(query: UserTaskQuery = {}) {
    loading.value = true
    error.value = null
    try {
      userTasks.value = await taskService.getUserTasks(query)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load user tasks'
    } finally {
      loading.value = false
    }
  }

  async function fetchUserTask(id: string) {
    loading.value = true
    error.value = null
    try {
      // Backend doesn't have GET /user-tasks/{id}, search through list
      // TODO: Backend needs GET /user-tasks/{id}
      const all = await taskService.getUserTasks({ pageIndex: 0, pageSize: 100 })
      currentTask.value = all.data.find((t) => t.id === id) || null
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
    loading.value = true
    error.value = null
    try {
      serviceTasks.value = await taskService.getServiceTasks(query)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load service tasks'
    } finally {
      loading.value = false
    }
  }

  function clearCurrent() {
    currentTask.value = null
    currentTaskVariables.value = []
  }

  return {
    userTasks,
    serviceTasks,
    currentTask,
    currentTaskVariables,
    loading,
    error,
    fetchUserTasks,
    fetchUserTask,
    fetchTaskVariables,
    completeUserTask,
    fetchServiceTasks,
    clearCurrent,
  }
})
