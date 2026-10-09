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
import { createPatchTracker } from '@/services/realtimePatch'
import { scheduleListRefresh } from '@/services/realtimeScheduler'

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

  // WO-REL-60: последний query каждого списка. handleEvent (SSE live-refetch)
  // обязан повторять ТЕКУЩИЙ фильтр страницы, а не голый fetch:
  // TaskList грузит с completed:false, а fetchUserTasks() без query сносил
  // фильтр — completed-задача возвращалась в список и строка не исчезала
  // (ночной E2E красный 27–28.09, user-task.completed долетал, refetch
  // отвечал без completed=false — доказано CDP-кадром + network-логом).
  let lastUserTasksQuery: UserTaskQuery = {}
  let lastServiceTasksQuery: ServiceTaskQuery = {}

  // WO-UI-26 Доп.4: трекер порядка/дедупа событий (патч или запасной refetch).
  const patchTracker = createPatchTracker()

  /**
   * WO-UI-26 Доп.2: тихий фоновый refresh (stale-while-revalidate) — НЕ ставит
   * `loading` (мерцание), не трогает `error`, merge по ключу вместо замены
   * массива (неизменённые строки — те же объекты, реактивность их не трогает).
   */
  function mergeUserTasks(patch: PagedData<UserTask>): void {
    const cur = userTasks.value
    if (!cur) {
      userTasks.value = patch
      return
    }
    const byId = new Map(cur.data.map((t) => [t.id, t]))
    let changed = false
    for (const row of patch.data) {
      if (byId.get(row.id) !== row) {
        byId.set(row.id, row)
        changed = true
      }
    }
    if (changed || cur.totalElements !== patch.totalElements) {
      userTasks.value = { ...patch, data: [...byId.values()] }
    }
  }

  function mergeServiceTasks(patch: PagedData<ServiceTask>): void {
    const cur = serviceTasks.value
    if (!cur) {
      serviceTasks.value = patch
      return
    }
    const byId = new Map(cur.data.map((t) => [t.id, t]))
    let changed = false
    for (const row of patch.data) {
      if (byId.get(row.id) !== row) {
        byId.set(row.id, row)
        changed = true
      }
    }
    if (changed || cur.totalElements !== patch.totalElements) {
      serviceTasks.value = { ...patch, data: [...byId.values()] }
    }
  }

  /**
   * WO-UI-26 Доп.4: адресный патч одной user-task по событию (вставка или
   * обновление ОДНОЙ строки + счётчик), 0 запросов списка. Возвращает false,
   * если патч невозможен (чужой фильтр/страница) — вызывающий планирует
   * запасной склеенный refetch.
   */
  function patchUserTaskRow(task: UserTask): boolean {
    const cur = userTasks.value
    // Фильтр видимости: completed-список не берёт активные и наоборот;
    // processInstanceId-скоуп — только свой инстанс.
    const q = lastUserTasksQuery
    if (q.completed === false && task.completedAt) return false
    if (q.completed === true && !task.completedAt) return false
    if (q.processInstanceId && task.processInstanceId !== q.processInstanceId) return false
    if (q.assignee && (task as { assignee?: string }).assignee !== q.assignee) return false
    if (!cur) return false
    const idx = cur.data.findIndex((t) => t.id === task.id)
    if (idx === -1) {
      // Новая строка: видна только на первой странице; иначе — только счётчик.
      if ((q.pageIndex ?? 0) !== 0) {
        userTasks.value = { ...cur, totalElements: cur.totalElements + 1 }
        return true
      }
      userTasks.value = {
        ...cur,
        data: [task, ...cur.data],
        totalElements: cur.totalElements + 1,
      }
      return true
    }
    if (cur.data[idx] === task) return true
    const data = [...cur.data]
    data[idx] = task
    userTasks.value = { ...cur, data }
    return true
  }

  function removeUserTaskRow(id: string): void {
    const cur = userTasks.value
    if (!cur) return
    const idx = cur.data.findIndex((t) => t.id === id)
    if (idx === -1) return
    // completed-уход из активного списка: строка исчезает, счётчик — нет
    // (totalElements — размер выборки сервера, не длина страницы).
    const data = [...cur.data]
    data.splice(idx, 1)
    userTasks.value = { ...cur, data }
  }

  async function fetchUserTasks(query: UserTaskQuery = {}) {
    lastUserTasksQuery = query
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
    lastServiceTasksQuery = query
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

  // WO-VT-1 (фронт): «ошибка» и throw error service task из шаблона элемента.
  async function failServiceTask(id: string, message: string, variables: ProcessVariable[]) {
    loading.value = true
    error.value = null
    try {
      await taskService.failServiceTask(id, message, variables)
      currentServiceTask.value = null
      currentTaskVariables.value = []
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fail service task'
    } finally {
      loading.value = false
    }
  }

  async function throwServiceTaskError(id: string, errorCode: string, variables: ProcessVariable[]) {
    loading.value = true
    error.value = null
    try {
      await taskService.throwServiceTaskError(id, errorCode, variables)
      currentServiceTask.value = null
      currentTaskVariables.value = []
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to throw service task error'
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
    // WO-UI-26 Доп.4 (главный принцип): событие → адресный патч одной
    // сущности по ключу; полный refetch — только запасной путь (разрыв
    // sequence/дубль-устаревание решает трекер, чужой контекст — фильтры).
    const patchable =
      envelope.type === 'user-task.created' ||
      envelope.type === 'user-task.completed' ||
      envelope.type === 'user-task.assigned' ||
      envelope.type === 'user-task.unassigned' ||
      envelope.type === 'service-task.created'
    if (patchable && !patchTracker.shouldPatch(envelope)) {
      // Дубль или устаревшее — no-op (для разрыва sequence shouldPatch тоже
      // false, но тогда нужен refetch — различаем ниже).
      const seq = typeof envelope.sequence === 'number' ? envelope.sequence : 0
      if (seq > 0 && seq > patchTracker.lastSequence() + 1 && patchTracker.lastSequence() > 0) {
        scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
        if (envelope.type === 'service-task.created') {
          scheduleListRefresh('serviceTasks', () => refreshServiceTasksQuiet())
        }
      }
      return
    }
    switch (envelope.type) {
      case 'user-task.created': {
        // Данных события (activityId) мало для строки — один GET сущности.
        const activityId = envelope.data?.['activityId']
        if (typeof activityId === 'string' && activityId) {
          void taskService
            .getUserTask(activityId)
            .then((task) => {
              patchTracker.markApplied(envelope)
              if (!patchUserTaskRow(task)) {
                scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
              }
            })
            .catch(() => {
              // Ошибка патча — запасной склеенный refetch (Доп.4 п.3).
              scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
            })
        } else {
          patchTracker.markApplied(envelope)
          scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
        }
        break
      }
      case 'user-task.completed': {
        // completed-уход: строка исчезает из активного списка без запросов;
        // completed-список подтянет её запасным путём при разрыве, иначе —
        // один GET для вставки (данные события не несут completedAt).
        const activityId = envelope.data?.['activityId']
        patchTracker.markApplied(envelope)
        if (lastUserTasksQuery.completed === false) {
          // WO-UI-26 Доп.4 кр.18: чужой строки нет в списке — 0 запросов.
          if (typeof activityId === 'string' && activityId) {
            const known = userTasks.value?.data.some((t) => t.id === activityId) ?? false
            if (known) removeUserTaskRow(activityId)
          }
        } else if (typeof activityId === 'string' && activityId) {
          void taskService
            .getUserTask(activityId)
            .then((task) => {
              if (!patchUserTaskRow(task)) {
                scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
              }
            })
            .catch(() => {
              scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
            })
        } else {
          scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
        }
        break
      }
      // WO-UI-25 (критерий 2): смена исполнителя не меняет статус, но меняет
      // видимость/ответственность — строку патчим одним GET по activityId.
      case 'user-task.assigned':
      case 'user-task.unassigned': {
        const activityId = envelope.data?.['activityId']
        if (typeof activityId === 'string' && activityId) {
          void taskService
            .getUserTask(activityId)
            .then((task) => {
              patchTracker.markApplied(envelope)
              if (!patchUserTaskRow(task)) {
                scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
              }
            })
            .catch(() => {
              scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
            })
        } else {
          patchTracker.markApplied(envelope)
          scheduleListRefresh('userTasks', () => refreshUserTasksQuiet())
        }
        break
      }
      case 'service-task.created': {
        patchTracker.markApplied(envelope)
        scheduleListRefresh('serviceTasks', () => refreshServiceTasksQuiet())
        break
      }
    }
  }

  /**
   * WO-UI-26 Доп.2/Доп.4: тихий запасной refetch (без loading, merge по ключу).
   * Экспортирован для планировщика и тестов.
   */
  async function refreshUserTasksQuiet(): Promise<void> {
    const myRequest = ++userTasksRequest
    try {
      const result = await taskService.getUserTasks(lastUserTasksQuery)
      if (myRequest !== userTasksRequest) return
      mergeUserTasks(result)
    } catch (e) {
      if (myRequest !== userTasksRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load user tasks'
    }
  }

  async function refreshServiceTasksQuiet(): Promise<void> {
    const myRequest = ++serviceTasksRequest
    try {
      const result = await taskService.getServiceTasks(lastServiceTasksQuery)
      if (myRequest !== serviceTasksRequest) return
      mergeServiceTasks(result)
    } catch (e) {
      if (myRequest !== serviceTasksRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load service tasks'
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
    failServiceTask,
    throwServiceTaskError,
    clearCurrent,
    handleEvent,
    // WO-UI-26 Доп.2/Доп.4: тихие точечные обновления (тесты + планировщик).
    refreshUserTasksQuiet,
    refreshServiceTasksQuiet,
    patchUserTaskRow,
    removeUserTaskRow,
    lastSequenceForTest: () => patchTracker.lastSequence(),
  }
})
