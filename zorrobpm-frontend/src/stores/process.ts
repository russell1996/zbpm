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
import { createPatchTracker } from '@/services/realtimePatch'
import { scheduleListRefresh } from '@/services/realtimeScheduler'

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

  // WO-REL-60: тот же lastQuery-паттерн, что в task.ts — live-refetch
  // повторяет текущий фильтр страницы (processDefinitionKey), иначе
  // событие сбрасывает выбранный фильтр ключом.
  let lastInstancesQuery: ProcessInstanceQuery = {}

  // WO-UI-26 Доп.4: трекер порядка/дедупа событий.
  const patchTracker = createPatchTracker()

  /**
   * WO-UI-26 Доп.2: тихий фоновый refresh (stale-while-revalidate) — НЕ ставит
   * `loading`, merge по ключу (неизменённые строки — те же объекты).
   */
  function mergeInstances(patch: PagedData<ProcessInstance>): void {
    const cur = instances.value
    if (!cur) {
      instances.value = patch
      return
    }
    const byId = new Map(cur.data.map((p) => [p.id, p]))
    let changed = false
    for (const row of patch.data) {
      if (byId.get(row.id) !== row) {
        byId.set(row.id, row)
        changed = true
      }
    }
    if (changed || cur.totalElements !== patch.totalElements) {
      instances.value = { ...patch, data: [...byId.values()] }
    }
  }

  /** WO-UI-26 Доп.2/Доп.4: тихий запасной refetch (без loading, merge по ключу). */
  async function refreshInstancesQuiet(): Promise<void> {
    const myRequest = ++instancesRequest
    try {
      const result = await instanceService.getProcessInstances(lastInstancesQuery)
      if (myRequest !== instancesRequest) return
      mergeInstances(result)
    } catch (e) {
      if (myRequest !== instancesRequest) return
      error.value = e instanceof Error ? e.message : 'Failed to load instances'
    }
  }

  /**
   * WO-UI-26 Доп.4: адресный патч одной строки инстанса (статус/completedAt),
   * 0 запросов списка. False — патч невозможен (нет строки / чужой фильтр).
   */
  function patchInstanceRow(patch: Partial<ProcessInstance> & { id: string }): boolean {
    const cur = instances.value
    if (!cur) return false
    const idx = cur.data.findIndex((p) => p.id === patch.id)
    if (idx === -1) return false
    const next = { ...cur.data[idx], ...patch }
    if (JSON.stringify(next) === JSON.stringify(cur.data[idx])) return true
    const data = [...cur.data]
    data[idx] = next
    instances.value = { ...cur, data }
    return true
  }

  async function fetchInstances(query: ProcessInstanceQuery = {}) {
    lastInstancesQuery = query
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

  /**
   * WO-UI-26 Б-1: тихий перечит инстанса для живой страницы (stale-while-
   * revalidate) — НЕ ставит `loading` (мерцание), только `refreshing`-семантика
   * вызывающей страницы. Ошибка — в error, без спиннера.
   */
  async function fetchInstanceQuiet(id: string): Promise<void> {
    try {
      currentInstance.value = await instanceService.getProcessInstance(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load instance'
    }
  }

  /**
   * WO-UI-26 Б-1: локальный патч текущего инстанса (0 запросов) — статус/
   * completedAt из envelope. True — применён.
   */
  function patchCurrentInstanceLocal(patch: Partial<ProcessInstance>): boolean {
    const cur = currentInstance.value
    if (!cur) return false
    const next = { ...cur, ...patch }
    if (JSON.stringify(next) === JSON.stringify(cur)) return true
    currentInstance.value = next
    return true
  }

  /**
   * WO-UI-26 Б-1: локальный патч activity по elementId (0 запросов) —
   * найденную CREATED/IN_PROGRESS запись переводим в COMPLETED. True —
   * применён (BPMN-маркеры обновятся реактивно без fetch).
   */
  function patchActivityCompletedLocal(elementId: string): boolean {
    if (!elementId) return false
    const cur = currentActivities.value
    const idx = cur.findIndex(
      (a) => a.bpmnElementId === elementId && (a.status === 'CREATED' || a.status === 'IN_PROGRESS'),
    )
    if (idx === -1) return false
    const next = [...cur]
    next[idx] = { ...next[idx], status: 'COMPLETED' as const }
    currentActivities.value = next
    return true
  }

  /**
   * WO-UI-26 Б-1: тихий перечит переменных инстанса (уже был тихим — явный
   * алиас для живой страницы, чтобы refreshLiveQuiet не звал громкие пути).
   */
  async function refreshVariablesQuiet(query: VariableQuery): Promise<void> {
    try {
      const result = await variableService.getVariables(query)
      currentVariables.value = result.data
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load variables'
    }
  }

  /**
   * WO-UI-26 Б-1: тихий перечит подпроцессов (уже был тихим — явный алиас).
   */
  async function refreshSubprocessesQuiet(id: string): Promise<void> {
    try {
      const result = await instanceService.getProcessInstances({ parentProcessInstanceId: id, pageIndex: 0, pageSize: 100 })
      currentSubprocesses.value = result.data
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load subprocesses'
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

  // WO-UI-25 (критерий 3): живое обновление activities БЕЗ сброса пагинации.
  // fetchActivities перезапускает окно с 0-й страницы (кнопка «догрузить ещё»
  // теряет уже подгруженное), а здесь перечитывается ровно то окно, которое
  // пользователь уже открыл (0..loadedActivityPages-1, обычно 1 запрос).
  // activity.completed намеренно НЕ идёт через process.handleEvent списком:
  // высокочастотный тип обновляет только открытую детальную страницу через
  // useInstanceLiveUpdates (дебаунс), списки инстансов он не меняет.
  async function refreshActivities(id: string) {
    const pages = Math.max(loadedActivityPages, 1)
    const myRequest = ++activitiesRequest
    try {
      let all: ActivityInstance[] = []
      let total = 0
      for (let p = 0; p < pages; p++) {
        const page = await instanceService.getProcessInstanceActivitiesPaged(
          id, p, currentActivitiesPageSize)
        if (myRequest !== activitiesRequest) return
        if (p === 0) {
          all = page.data
          total = page.totalElements
        } else {
          all = [...all, ...page.data]
        }
        // Сервер отдал короткую страницу — дальше страниц нет, лишний
        // запрос не делаем (окно могло сжаться между чтениями).
        if (page.data.length < currentActivitiesPageSize) {
          total = page.totalElements
          break
        }
      }
      currentActivities.value = all
      currentActivitiesTotal.value = total
      hasMoreActivities.value = all.length < total
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
    // WO-UI-26 Доп.4: событие → адресный патч; полный refetch — только запасной.
    const patchable =
      envelope.type === 'process-instance.started' ||
      envelope.type === 'process-instance.completed' ||
      envelope.type === 'process-instance.cancelled'
    if (patchable && !patchTracker.shouldPatch(envelope)) {
      const seq = typeof envelope.sequence === 'number' ? envelope.sequence : 0
      if (seq > 0 && seq > patchTracker.lastSequence() + 1 && patchTracker.lastSequence() > 0) {
        scheduleListRefresh('instances', () => refreshInstancesQuiet())
      }
      return
    }
    switch (envelope.type) {
      case 'process-instance.started': {
        // Новой строки целиком в событии нет — один GET сущности.
        const piId = envelope.processInstanceId
        if (piId) {
          void instanceService
            .getProcessInstance(piId)
            .then((pi) => {
              patchTracker.markApplied(envelope)
              const cur = instances.value
              if (!cur) {
                scheduleListRefresh('instances', () => refreshInstancesQuiet())
                return
              }
              // Чужой фильтр/страница: только счётчик (Доп.4 п.1).
              const q = lastInstancesQuery
              const fitsKey =
                (!q.processDefinitionKey || pi.processKey === q.processDefinitionKey) &&
                (!q.processDefinitionId || pi.processDefinitionId === q.processDefinitionId)
              if (!fitsKey) return
              if (cur.data.some((p) => p.id === pi.id)) {
                patchInstanceRow(pi)
                return
              }
              if ((q.pageIndex ?? 0) !== 0) {
                instances.value = { ...cur, totalElements: cur.totalElements + 1 }
                return
              }
              instances.value = { ...cur, data: [pi, ...cur.data], totalElements: cur.totalElements + 1 }
            })
            .catch(() => {
              scheduleListRefresh('instances', () => refreshInstancesQuiet())
            })
        } else {
          patchTracker.markApplied(envelope)
          scheduleListRefresh('instances', () => refreshInstancesQuiet())
        }
        break
      }
      case 'process-instance.completed':
      case 'process-instance.cancelled': {
        // Статус одной строки — без запросов (completedAt ставит сервер;
        // точное время подтянет запасной путь/следующий патч, статус — сразу).
        const piId = envelope.processInstanceId
        patchTracker.markApplied(envelope)
        if (piId) {
          const done = envelope.type === 'process-instance.completed'
          if (!patchInstanceRow(done ? { id: piId, completedAt: envelope.occurredAt ?? new Date().toISOString() } : { id: piId })) {
            scheduleListRefresh('instances', () => refreshInstancesQuiet())
          }
          if (!done) {
            // cancelled-флаг — одним GET (в событии его нет).
            void instanceService
              .getProcessInstance(piId)
              .then((pi) => {
                patchInstanceRow(pi)
              })
              .catch(() => {})
          }
        } else {
          scheduleListRefresh('instances', () => refreshInstancesQuiet())
        }
        break
      }
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
    fetchInstanceQuiet,
    patchCurrentInstanceLocal,
    patchActivityCompletedLocal,
    refreshVariablesQuiet,
    refreshSubprocessesQuiet,
    fetchActivities,
    fetchMoreActivities,
    refreshActivities,
    fetchSubprocesses,
    fetchVariables,
    startInstance,
    clearCurrent,
    handleEvent,
    // WO-UI-26 Доп.2/Доп.4: тихие точечные обновления (тесты + планировщик).
    refreshInstancesQuiet,
    patchInstanceRow,
    lastSequenceForTest: () => patchTracker.lastSequence(),
  }
})
