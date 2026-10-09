<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useProcessStore } from '@/stores/process'
import { useTaskStore } from '@/stores/task'
import { useIncidentStore } from '@/stores/incident'
import { useAuthStore } from '@/stores/auth'
import { listMembers, type Member } from '@/services/adminService'
import { useBreadcrumbLabel } from '@/composables/useBreadcrumbLabel'
import { useToast } from '@/composables/useToast'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import * as processService from '@/services/processService'
import * as variableService from '@/services/variableService'
import { cancelProcessInstance } from '@/services/instanceService'
import type { ProcessVariable, BpmnNode, BpmnFlow } from '@/types/api'
import { isTaskActive, processInstanceStatus, isProcessInstanceActive, errorMessage } from '@/shared/lib/utils'
import { RefreshCw, ArrowRight, ArrowLeft, Download, Calendar, OctagonX, Copy, Check } from 'lucide-vue-next'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import IoMappingTable from '@/widgets/shared/IoMappingTable.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'
import TabsBar from '@/widgets/shared/TabsBar.vue'
import PresetPicker from '@/widgets/presets/PresetPicker.vue'
import InstanceMessagePanel from '@/widgets/presets/InstanceMessagePanel.vue'
import InstanceSnapshotPanel from '@/widgets/presets/InstanceSnapshotPanel.vue'
import VariableRowMenu from '@/widgets/presets/VariableRowMenu.vue'
import { onCtrlEnter, usePresetModal } from '@/composables/usePresetModal'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { buildDiagnosticJson } from '@/shared/lib/diagnostic'
import { useInstanceLiveUpdates } from '@/composables/useInstanceLiveUpdates'
import { useInstanceViewState, type InstanceTabId } from '@/composables/useInstanceViewState'

const route = useRoute()
const router = useRouter()
const processStore = useProcessStore()
const taskStore = useTaskStore()
const incidentStore = useIncidentStore()
const auth = useAuthStore()
const toast = useToast()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

// WO-ACL-15 criterion 17: the instance crumb reads as the process name plus a
// short id — the name alone would repeat the parent crumb for every instance
// of the same definition. Filled via the shared useBreadcrumbLabel() when the
// instance arrives, cleared on unmount (criterion 18).
useBreadcrumbLabel(() => {
  const pi = processStore.currentInstance
  if (!pi) return null
  const name = pi.processName || pi.processKey || ''
  return name ? `${name} · ${pi.id.slice(0, 8)}` : pi.id.slice(0, 8)
})

// WO-UI-25 (критерий 8, дополнение 2026-10-09): «где я» живёт в адресе.
// Вид (вкладка, выбранный элемент, плоскость drill-down, страница активностей)
// синхронизирован с query URL через композабл: F5/deep link/back-forward
// восстанавливают вид, а не сбрасывают в корень.
const viewState = useInstanceViewState()
// Top-level ref-алиасы: в template рефы разворачиваются только на верхнем
// уровне setup-биндингов — viewState.plane напрямую остался бы Ref-объектом.
const activeTab = viewState.tab
const selectedElement = viewState.element
const viewPlane = viewState.plane

// WO-ACL-14 criteria 1-3: ONE tab component (TabsBar) — the local tab strip is gone.
// WO-UI-17 F24: no `as const` — TabsBar takes a mutable TabItem[].
// WO-UI-25: литеральный союз вкладок живёт в useInstanceViewState
// (InstanceTabId); activeTab IS viewState.tab, selectTab пишет в ?tab=.
// WO-VT-3 раунд 2 (E-VT3-1): вкладки «Шаблоны» больше нет — сообщение ушло в
// модалку из меню «Действия», снимок — на вкладку «Переменные», менеджер —
// на страницу определения. Из InstanceTabId 'presets' убран здесь же
// (см. useInstanceViewState.ts).
const instanceTabs = computed(() => [
  { id: 'bpmn', label: t('bpmnFlow') },
  { id: 'variables', label: t('variables') },
  { id: 'tasks', label: t('tasks') },
  { id: 'serviceTasks', label: t('serviceTasks') },
  { id: 'incidents', label: t('incidentsTab') },
  { id: 'history', label: t('history') },
  { id: 'subprocesses', label: t('subprocesses') },
])

// TabsBar emits `string`, but only ever one of our own tab ids — narrow it
// through the list instead of casting blindly. The union lives in
// useInstanceViewState (InstanceTabId); activeTab IS viewState.tab,
// so assignment here lands in ?tab= via the composable watcher.
function selectTab(id: string) {
  if (instanceTabs.value.some((tab) => tab.id === id)) {
    activeTab.value = id as typeof activeTab.value
  }
}

const bpmnXml = ref('')

// BPMN element highlighting derived from the instance activity history
const activeElementIds = computed(() =>
  processStore.currentActivities.filter((a) => a.status === 'CREATED' || a.status === 'IN_PROGRESS').map((a) => a.bpmnElementId))
const incidentElementIds = computed(() =>
  processStore.currentActivities.filter((a) => a.status === 'ERROR').map((a) => a.bpmnElementId))
// completed shown only where the element isn't currently active/incident, so a re-entered (looped)
// element shows as active (blue) rather than completed (green)
const completedElementIds = computed(() => {
  const busy = new Set([...activeElementIds.value, ...incidentElementIds.value])
  return processStore.currentActivities
    .filter((a) => a.status === 'COMPLETED' && !busy.has(a.bpmnElementId))
    .map((a) => a.bpmnElementId)
})

// Camunda-style token counts: number of active tokens sitting on each element
const elementCounts = computed<Record<string, number>>(() => {
  const counts: Record<string, number> = {}
  for (const a of processStore.currentActivities) {
    if (a.status === 'CREATED' || a.status === 'IN_PROGRESS') {
      counts[a.bpmnElementId] = (counts[a.bpmnElementId] || 0) + 1
    }
  }
  return counts
})
const tabLoading = ref(false)

// --- selected BPMN element: properties + cross-links to the relevant tab ---
function findNode(nodes: BpmnNode[], id: string): BpmnNode | null {
  for (const n of nodes) {
    if (n.id === id) return n
    for (const b of n.boundaryEvents || []) if (b.id === id) return b
    if (n.children) {
      const found = findNode(n.children.nodes, id)
      if (found) return found
    }
  }
  return null
}

const selectedNode = computed<BpmnNode | null>(() => {
  if (!selectedElement.value || !processStore.currentStructure) return null
  return findNode(processStore.currentStructure.nodes, selectedElement.value)
})

const selectedNodeProps = computed(() =>
  selectedNode.value
    ? Object.entries(selectedNode.value.properties || {})
        // WO-ENG-14: ioMapping declarations render in their own readable
        // block below, not as raw JSON in the generic properties list.
        .filter(([k]) => k !== 'inputMappings' && k !== 'outputMappings')
    : [])

// WO-ENG-14: resolved runtime input variables of the selected activity.
// View-local ref (not the store): only this panel needs them, and the store's
// currentVariables now carry root-only semantics.
const selectedActivityVariables = ref<ProcessVariable[]>([])

watch(selectedNode, async (node) => {
  selectedActivityVariables.value = []
  const pi = processStore.currentInstance
  if (!node || !pi) return
  const activity = processStore.currentActivities.find((a) => a.bpmnElementId === node.id)
  if (!activity) return
  try {
    const page = await variableService.getVariables({ processInstanceId: pi.id, activityId: activity.id })
    selectedActivityVariables.value = page.data || []
  } catch {
    // scoped variables are best-effort panel detail, not a page error
    selectedActivityVariables.value = []
  }
})
const selectedFlow = computed<BpmnFlow | null>(() =>
  selectedElement.value && !selectedNode.value && processStore.currentStructure
    ? (processStore.currentStructure.flows.find((f) => f.id === selectedElement.value) || null)
    : null)

const selectedHasUserTask = computed(() =>
  !!selectedElement.value && (taskStore.userTasks?.data || []).some((tk) => tk.code === selectedElement.value))
const selectedHasServiceTask = computed(() =>
  !!selectedElement.value && (taskStore.serviceTasks?.data || []).some((tk) => tk.code === selectedElement.value))
const selectedHasIncident = computed(() =>
  !!selectedElement.value && incidentElementIds.value.includes(selectedElement.value))

// Element dialog: replaces goToElementTab — shows tasks/incidents for the clicked BPMN element
// without switching away from the BPMN tab
const showElementDialog = ref(false)
const dialogSelectedElement = ref<string | null>(null)
const elementDialogView = ref<'list' | 'form'>('list')

const dialogUserTasks = computed(() => {
  if (!dialogSelectedElement.value) return []
  return (taskStore.userTasks?.data || []).filter((tk) => tk.code === dialogSelectedElement.value)
})
const dialogServiceTasks = computed(() => {
  if (!dialogSelectedElement.value) return []
  return (taskStore.serviceTasks?.data || []).filter((tk) => tk.code === dialogSelectedElement.value)
})
const dialogIncidents = computed(() => {
  if (!dialogSelectedElement.value) return []
  const activityIds = processStore.currentActivities
    .filter((a) => a.bpmnElementId === dialogSelectedElement.value && a.status === 'ERROR')
    .map((a) => a.id)
  return (incidentStore.incidents?.data || []).filter((inc) => activityIds.includes(inc.activityId))
})

// WO-UI-25 (критерий 8): выбор элемента пишется в ?element= через viewState.
function onElementClick(elementId: string) {
  selectedElement.value = elementId
}

function openElementDialog(elementId: string) {
  dialogSelectedElement.value = elementId
  showElementDialog.value = true
  elementDialogView.value = 'list'
}

function startElementDialogComplete(taskId: string, type: 'user' | 'service') {
  completingTaskId.value = taskId
  completingTaskType.value = type
  completeAskMissing.value = []
  completePickerInvalid.value = false
  completePresetRef.value = dialogSelectedElement.value
  elementDialogView.value = 'form'
}

function startElementDialogResolve(incidentId: string) {
  completingTaskId.value = incidentId
  completingTaskType.value = 'resolve'
  completeAskMissing.value = []
  completePickerInvalid.value = false
  completePresetRef.value = dialogSelectedElement.value
  elementDialogView.value = 'form'
}

function cancelElementDialog() {
  showElementDialog.value = false
  elementDialogView.value = 'list'
  dialogSelectedElement.value = null
}

function cancelElementDialogForm() {
  elementDialogView.value = 'list'
}

const showCompleteModal = ref(false)
const completingTaskId = ref('')
const completingTaskType = ref<'user' | 'service' | 'resolve'>('user')
// WO-VT-1: complete/resolve из шаблона элемента — переменные отдаёт
// PresetPicker (ref = bpmnElementId завершаемой задачи / инцидента).
const completePickerRef = ref<InstanceType<typeof PresetPicker> | null>(null)
const completeAskMissing = ref<string[]>([])
const completePickerInvalid = ref(false)
const completePresetRef = ref<string | null>(null)

function onCompletePickerChange() {
  completeAskMissing.value = completePickerRef.value?.missingAsk ?? []
  completePickerInvalid.value = completePickerRef.value?.hasErrors ?? false
}

/** targetKind пикера по типу завершаемого (resolve → INCIDENT). */
function completePickerKind(): 'USER_TASK' | 'SERVICE_TASK' | 'INCIDENT' {
  return completingTaskType.value === 'resolve'
    ? 'INCIDENT'
    : completingTaskType.value === 'service'
      ? 'SERVICE_TASK'
      : 'USER_TASK'
}

function openCompleteModal(taskId: string, type: 'user' | 'service') {
  completingTaskId.value = taskId
  completingTaskType.value = type
  completeAskMissing.value = []
  completePickerInvalid.value = false
  completePresetRef.value =
    (type === 'service'
      ? taskStore.serviceTasks?.data.find((tk) => tk.id === taskId)?.code
      : taskStore.userTasks?.data.find((tk) => tk.id === taskId)?.code) ?? null
  showCompleteModal.value = true
}

function openResolveModal(incidentId: string) {
  completingTaskId.value = incidentId
  completingTaskType.value = 'resolve'
  completeAskMissing.value = []
  completePickerInvalid.value = false
  completePresetRef.value =
    incidentStore.incidents?.data.find((inc) => inc.id === incidentId)?.bpmnElementId ?? null
  showCompleteModal.value = true
}

// WO-VT-3 раунд 2 (E-VT3-1, мокап А): публикация сообщения — действие над
// инстансом в модалке из меню «Действия», а не блок вкладки. Пикер «Из
// шаблона» живёт внутри InstanceMessagePanel.
const showMessageModal = ref(false)
const messageModalRef = ref<HTMLElement | null>(null)
const showMessageModalRef = computed(() => showMessageModal.value)
usePresetModal(showMessageModalRef, messageModalRef, () => {
  showMessageModal.value = false
})

function openTemplatesOnDefinition() {
  const defId = processStore.currentInstance?.processDefinitionId
  if (defId) void router.push({ name: 'process-definition-detail', params: { id: defId } })
}

// WO-VT-3 раунд 2: меню «Действия ▾» в шапке — точка входа «Отправить
// сообщение…», дубль «Сохранить переменные как шаблон» и ссылка на менеджер.
const snapshotOnVariablesRef = ref<InstanceType<typeof InstanceSnapshotPanel> | null>(null)

async function onInstanceMenuAction(id: string) {
  if (id === 'send') {
    showMessageModal.value = true
  } else if (id === 'snapshot') {
    activeTab.value = 'variables'
    await nextTick()
    await nextTick()
    snapshotOnVariablesRef.value?.openDialog()
  } else if (id === 'templates') {
    openTemplatesOnDefinition()
  }
}

async function confirmComplete() {
  if (!completePickerRef.value || completeAskMissing.value.length || completePickerInvalid.value) return
  const variables: ProcessVariable[] = completePickerRef.value.getVariables().map((v) => ({
    name: v.name,
    type: v.type,
    value: v.value,
  }))
  let error: string | null
  if (completingTaskType.value === 'user') {
    await taskStore.completeUserTask(completingTaskId.value, variables)
    error = taskStore.error
  } else if (completingTaskType.value === 'service') {
    await taskStore.completeServiceTask(completingTaskId.value, variables)
    error = taskStore.error
  } else {
    await incidentStore.resolveIncident(completingTaskId.value, variables)
    error = incidentStore.error
  }
  if (!error) {
    toast.success(completingTaskType.value === 'resolve' ? t('incidentResolved') : t('taskCompleted'))
    showCompleteModal.value = false
    showElementDialog.value = false
    elementDialogView.value = 'list'
    await reloadAll()
  } else {
    toast.error(error)
  }
}

// WO-ACL-22: кнопка отмены видна только пока instance активен И у пользователя
// есть право DELETE_PROCESS (SUPER_ADMIN либо OWNER/DESIGNER текущего процесса).
// Паттерн — как myMembership в ProcessDefinitionDetail (WO-ACL-6/8): роль берём
// из уже загруженного списка членов процесса (listMembers), не хардкодим;
// VIEWER кнопку не видит (раньше видел и получал 403+тост — найдено CTO живьём).
const members = ref<Member[]>([])

const myMembership = computed(() => {
  if (!auth.user) return null
  return members.value.find((m) => m.userId === auth.user?.id) || null
})

const canCancelInstance = computed(
  () =>
    auth.isSuperAdmin ||
    myMembership.value?.role === 'OWNER' ||
    myMembership.value?.role === 'DESIGNER',
)

const isInstanceCancellable = computed(() => {
  const pi = processStore.currentInstance
  return !!pi && isProcessInstanceActive(pi) && canCancelInstance.value
})

// WO-UI-21 Раунд 2: ключ подписи статуса шапки — computed в script, а не
// тернарий с литералом 'CANCELLED' в шаблоне (сканер непереведённых строк
// WO-ACL-11 criterion 11 флагит строковые литералы внутри {{ }}).
const headerStatusLabelKey = computed(() => {
  const pi = processStore.currentInstance
  if (!pi) return 'running'
  if (processInstanceStatus(pi) === 'CANCELLED') return 'statusCancelled'
  return pi.completedAt ? 'completed' : 'running'
})

const showCancelDialog = ref(false)
const cancelInFlight = ref(false)

function openCancelDialog() {
  showCancelDialog.value = true
}

async function confirmCancel() {
  const pi = processStore.currentInstance
  if (!pi || cancelInFlight.value) return
  cancelInFlight.value = true
  try {
    await cancelProcessInstance(pi.id)
    toast.success(t('processCancelled'))
    showCancelDialog.value = false
    // Статус CANCELLED без ручного refresh: перечитываем instance —
    // fetchInstance подтянет cancelled=true, бейдж переключится сам.
    await reloadAll()
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status
    if (status === 409) {
      // Race: кто-то другой уже завершил/отменил — понятная ошибка +
      // перезагрузка, чтобы показать актуальный (терминальный) статус
      // вместо протухшего активного вида с кнопкой отмены.
      toast.error(t('instanceAlreadyFinished'))
      showCancelDialog.value = false
      await reloadAll()
    } else if (status === 403) {
      toast.error(t('cancelNotAllowed'))
    } else {
      // Тот же паттерн, что toggleArchive: текст бэкенда, иначе generic.
      toast.error(errorMessage(e, t('loadError')))
    }
  } finally {
    cancelInFlight.value = false
  }
}

// WO-UI-23: разворачиваемое полное значение переменной. Ячейка таблицы режет
// max-w-xs + truncate (title-only страховка: hover-only, не работает на таче,
// не даёт скопировать). Длинным считается значение длиннее видимой ячейки
// (порог 120 символов — консервативно над ~45 видимыми) или ЛЮБОЕ JSON
// (даже короткое — его стоит показать отформатированным). Короткие — как
// раньше, truncate + title, без лишних кнопок.
const VALUE_PREVIEW_LIMIT = 120

function isLongVariableValue(v: { type: string; value: string }): boolean {
  return v.type === 'JSON' || (v.value?.length ?? 0) > VALUE_PREVIEW_LIMIT
}

// JSON — красиво отформатирован, с защитой на не-валидный (fallback — сырая
// строка, не исключение в шаблоне).
function formatVariableValue(v: { type: string; value: string }): string {
  if (v.type === 'JSON') {
    try {
      return JSON.stringify(JSON.parse(v.value), null, 2)
    } catch {
      return v.value
    }
  }
  return v.value
}

const inspectedVariable = ref<{ name: string; type: string; value: string } | null>(null)
const variableCopied = ref(false)

function openVariableInspector(v: { name: string; type: string; value: string }) {
  inspectedVariable.value = v
  variableCopied.value = false
}

function closeVariableInspector() {
  inspectedVariable.value = null
  variableCopied.value = false
}

async function copyInspectedVariable() {
  if (!inspectedVariable.value) return
  await navigator.clipboard.writeText(formatVariableValue(inspectedVariable.value))
  variableCopied.value = true
  setTimeout(() => { variableCopied.value = false }, 1500)
}

// WO-UI-25 (критерий 8): activities грузятся окном с учётом ?page= —
// «Обновить»/SSE не сбрасывают подгруженные страницы в первую.
async function loadActivitiesWithPages(id: string) {
  await processStore.fetchActivities(id)
  for (let i = 0; i < viewState.activitiesPage.value && processStore.hasMoreActivities; i++) {
    await processStore.fetchMoreActivities(id)
  }
}

async function loadTabData() {
  const pi = processStore.currentInstance
  if (!pi) return
  tabLoading.value = true
  try {
    await Promise.all([
      taskStore.fetchUserTasks({ processInstanceId: pi.id, pageIndex: 0, pageSize: 100 }),
      taskStore.fetchServiceTasks({ processInstanceId: pi.id, pageIndex: 0, pageSize: 100 }),
      incidentStore.fetchIncidents({ processInstanceId: pi.id, pageIndex: 0, pageSize: 100 }),
      processStore.fetchVariables({ processInstanceId: pi.id }),
      loadActivitiesWithPages(pi.id),
      processStore.fetchSubprocesses(pi.id),
    ])
  } finally {
    tabLoading.value = false
  }
}

// WO-UI-25 (критерий 8, механизм б): диаграмма кэшируется по definition.
// Тот же xml-объект = watch в BpmnViewer не стреляет = viewer живёт дальше
// (плоскость/зум не слетают при «Обновить»/SSE). Новая диаграмма (другой
// инстанс другого определения) — новая строка → честный перерендер.
let loadedXmlDefinitionId: string | null = null
async function loadBpmnXml() {
  const pi = processStore.currentInstance
  if (!pi) return
  if (bpmnXml.value && loadedXmlDefinitionId === pi.processDefinitionId) return
  try {
    bpmnXml.value = await processService.getProcessDefinitionXml(pi.processDefinitionId)
    loadedXmlDefinitionId = pi.processDefinitionId
  } catch {
    // XML not available
    loadedXmlDefinitionId = null
  }
}

async function onTabChange() {
  if (activeTab.value === 'bpmn') {
    await loadBpmnXml()
  } else {
    const pi = processStore.currentInstance
    if (!pi) return
    const hasData =
      (activeTab.value === 'variables' && processStore.currentVariables.length > 0) ||
      (activeTab.value === 'tasks' && taskStore.userTasks) ||
      (activeTab.value === 'serviceTasks' && taskStore.serviceTasks) ||
      (activeTab.value === 'incidents' && incidentStore.incidents)
    if (!hasData) {
      await loadTabData()
    }
  }
}

async function reloadAll() {
  const pi = processStore.currentInstance
  if (!pi) return
  // WO-UI-25 (критерий 8, механизм б): bpmnXml НЕ сбрасываем — v-if держит
  // BpmnViewer живым, плоскость/масштаб/выбор целы; обновляются только данные
  // (маркеры/подсветка — через prop-watcher'ы viewer). Сброс xml пересоздавал
  // viewer и «выкидывал в основной».
  await processStore.fetchInstance(pi.id) // refresh the instance itself so its status badge updates
  await loadTabData()
  await loadBpmnXml()
}

// WO-UI-25 (критерий 3): живое обновление по realtime-событиям инстанса.
// В отличие от reloadAll НЕ трогает bpmnXml (картинка не перерисовывается —
// нет мерцания) и читает activities окном refreshActivities (пагинация
// «догрузить ещё» сохраняется). Состояние UI (таб, скролл, выбор) живёт в
// локальных ref и этим путём не сбрасывается — обновляются только данные.
async function refreshLive() {
  const pi = processStore.currentInstance
  if (!pi) return
  await Promise.all([
    processStore.fetchInstance(pi.id),
    processStore.refreshActivities(pi.id),
    taskStore.fetchUserTasks({ processInstanceId: pi.id, pageIndex: 0, pageSize: 100 }),
    taskStore.fetchServiceTasks({ processInstanceId: pi.id, pageIndex: 0, pageSize: 100 }),
    incidentStore.fetchIncidents({ processInstanceId: pi.id, pageIndex: 0, pageSize: 100 }),
    processStore.fetchVariables({ processInstanceId: pi.id }),
    processStore.fetchSubprocesses(pi.id),
  ])
}

// WO-UI-25: подписка на общую realtime-шину с фильтром по текущему инстансу
// (дебаунс 250 мс, фолбэк-опрос 4 с, пауза в скрытой вкладке — всё внутри
// композабла). Отписка — его же onUnmounted; route-смена id переинициализирует
// через init/watch ниже, getInstanceId всегда читает актуальный стор.
// WO-UI-26 Доп.3: терминальный инстанс — живые механизмы выключены
// (isTerminal по completedAt), при running→completed — один finalRefresh.
const { liveState, finalRefresh } = useInstanceLiveUpdates({
  getInstanceId: () => processStore.currentInstance?.id ?? null,
  refresh: refreshLive,
  isTerminal: () => {
    const pi = processStore.currentInstance
    return !!pi && (!!pi.completedAt || processInstanceStatus(pi) === 'CANCELLED')
  },
})

// WO-UI-26 Доп.3: завершение в открытой странице — финальный refresh и тишина.
// События completed/cancelled ЭТОГО инстанса досылаем сюда отдельной подпиской
// (основная подписка композабла терминальные уже игнорирует).
import { subscribeRealtimeEvents as subscribeTerminalWatch } from '@/services/realtimeBus'
const unsubscribeTerminalWatch = subscribeTerminalWatch((envelope) => {
  const pi = processStore.currentInstance
  if (!pi || envelope.processInstanceId !== pi.id) return
  if (envelope.type === 'process-instance.completed' || envelope.type === 'process-instance.cancelled') {
    finalRefresh()
  }
})
onUnmounted(() => unsubscribeTerminalWatch())

// WO-UI-26 Доп.7: индикатор канала УБРАН из шапки страницы целиком —
// состояние транспорта не должно быть среди кнопок действий и отвлекать.
// При сбое — одна нейтральная точка в глобальной шапке (ChannelStatusDot),
// детали по клику. liveState ниже используется только внутренней логикой.
void liveState

// WO-UI-18 часть C: догрузка следующей страницы activities.
// WO-UI-25 (критерий 8): номер страницы — в ?page= (переживает F5).
async function loadMoreActivities() {
  const pi = processStore.currentInstance
  if (!pi) return
  const before = processStore.currentActivities.length
  await processStore.fetchMoreActivities(pi.id)
  if (processStore.currentActivities.length > before) {
    viewState.activitiesPage.value += 1
  }
}

function downloadDiagnostic() {
  const pi = processStore.currentInstance
  if (!pi) return
  const json = buildDiagnosticJson({
    instance: pi,
    activities: processStore.currentActivities,
    variables: processStore.currentVariables,
    userTasks: taskStore.userTasks?.data || [],
    serviceTasks: taskStore.serviceTasks?.data || [],
    incidents: incidentStore.incidents?.data || [],
    bpmnXml: bpmnXml.value,
  })
  const blob = new Blob([JSON.stringify(json, null, 2)], {
    type: 'application/json;charset=utf-8;',
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `instance-${pi.id}-diagnostic.json`
  link.click()
  URL.revokeObjectURL(url)
}

async function init(id: string) {
  members.value = []
  // WO-UI-25 (критерий 8): selectedElement/plane/tab/page НЕ сбрасываем —
  // ими владеет viewState (адрес): back/forward и deep link восстанавливают
  // вид, сброс здесь «выкидывал» обратно в корень.
  await processStore.fetchInstance(id)
  // load tasks/service-tasks/incidents/activities/subprocesses up-front so the BPMN element
  // panel can offer cross-links and highlighting immediately
  await loadTabData()
  const pi = processStore.currentInstance
  // Смена определения = новая диаграмма: сброс xml ПЕРЕД loadBpmnXml, viewer
  // пересоздастся честно. То же определение (родитель→подпроцесс) — xml тот
  // же объект, viewer живёт, плоскость/зум целы.
  if (pi && pi.processDefinitionId !== loadedXmlDefinitionId) {
    bpmnXml.value = ''
  }
  if (pi) {
    await processStore.fetchStructure(pi.processDefinitionId)
  }
  // WO-ACL-22: членство грузим НЕ дожидаясь (fire-and-forget): отдельный
  // запрос вне критического пути страницы — await сериализовал бы за ним и
  // loadBpmnXml, а в jsdom-тестах без мока adminService реальный XHR вообще
  // не резолвится, вешая весь init. Кнопка отмены реактивно появится, когда
  // членство приедет (до этого — скрыта, fail-closed).
  void loadMembers()
  await loadBpmnXml()
  // WO-VT-3 раунд 2: редирект ?tab=presets → ?tab=variables живёт в
  // useInstanceViewState.readFromQuery (с сохранением ?element/?plane/?page),
  // здесь дублировать нечего.
}

// WO-ACL-22: члены процесса для проверки права отмены (тот же источник, что
// ProcessDefinitionDetail.loadMembers — GET /processes/{key}/members).
// Ключ — из уже загруженного инстанса; тихий пропуск при отсутствии ключа:
// кнопка просто не показывается без подтвержденного членства (fail-closed).
// Протухший ответ чужого инстанса (быстрая навигация) не применяется —
// сверяем ключ на момент ответа, а не только на момент запроса.
async function loadMembers() {
  const key = processStore.currentInstance?.processKey
  if (!key) return
  try {
    const loaded = await listMembers(key)
    if (processStore.currentInstance?.processKey === key) {
      members.value = loaded
    }
  } catch {
    members.value = []
  }
}

onMounted(() => init(route.params.id as string))

// navigating to another instance (e.g. a subprocess via "view") reuses this component —
// reload everything when the route id changes
watch(() => route.params.id, (id) => { if (id) init(id as string) })

watch(activeTab, onTabChange)
</script>

<template>
  <!-- WO-ACL-11 criteria 37-38: min-h-full + flex so the bpmn tab can stretch
       to the bottom edge of the window (layout, not fixed pixels). -->
  <div class="space-y-2 min-h-full flex flex-col">
    <div v-if="processStore.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="processStore.error" class="text-sm text-red-500">{{ processStore.error }}</div>

    <template v-else-if="processStore.currentInstance">
      <!-- WO-UI-14: compact enterprise header -->
      <div class="flex items-center gap-2">
        <RouterLink :to="{ name: 'process-instances' }" class="inline-flex items-center justify-center h-9 w-9 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors shrink-0 self-center">
          <ArrowLeft class="h-5 w-5" />
        </RouterLink>
        <div class="flex flex-col gap-0.5 flex-1 min-w-0">
          <!-- Row 1: Экземпляры процессов · UUID -->
          <!-- WO-VT-3 раунд 2: flex-wrap — на 360px крошки+UUID+кнопки не
               влезали (pre-existing 551px, замерено живым Chromium); на
               десктопе всё в одну строку как раньше. -->
          <div class="flex items-center gap-2 flex-wrap">
            <span class="text-sm text-muted-foreground whitespace-nowrap">{{ t('processInstances') }}</span>
            <span class="text-foreground/80 text-xs">·</span>
            <span class="text-sm text-muted-foreground/80"><CopyableId :value="processStore.currentInstance.id" /></span>
            <span class="flex-1" />
            <Button variant="outline" size="sm" class="h-8 px-3 text-xs" @click="downloadDiagnostic">
              <Download class="h-4 w-4" />
              {{ t('downloadDiagnostic') }}
            </Button>
            <!-- WO-UI-21 Раунд 2: ручная отмена — только для активного instance.
                 WO-UI-23: иконка OctagonX — та же иконка+текст форма, что у
                 соседей (Download/RefreshCw); variant="destructive" оставлен —
                 это осмысленный сигнал деструктивного действия, цель была
                 согласованность, не смена смысла. -->
            <Button
              v-if="isInstanceCancellable"
              variant="destructive"
              size="sm"
              class="h-8 px-3 text-xs"
              data-testid="cancel-instance-btn"
              @click="openCancelDialog"
            >
              <OctagonX class="h-4 w-4" />
              {{ t('cancelProcess') }}
            </Button>
            <Button variant="outline" size="sm" class="h-8 px-3 text-xs" :disabled="processStore.loading || tabLoading" @click="reloadAll">
              <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': processStore.loading || tabLoading }" />
              {{ t('refresh') }}
            </Button>
            <!-- WO-UI-26 Доп.7: индикатор канала убран из шапки страницы —
                 состояние транспорта не среди кнопок действий. При сбое —
                 нейтральная точка в глобальной шапке (ChannelStatusDot). -->
            <!-- WO-VT-3 раунд 2 (E-VT3-1): «Действия ▾» — отправка сообщения,
                 снимок переменных, ссылка на шаблоны процесса. -->
            <VariableRowMenu
              data-testid="instance-actions-menu"
              :label="t('presetInstanceActions')"
              :items="[
                { id: 'send', label: t('presetSendMessage') },
                { id: 'snapshot', label: t('presetSaveSnapshotMenu') },
                { id: 'templates', label: t('presetOpenManager') },
              ]"
              @select="onInstanceMenuAction($event)"
            />
          </div>
          <!-- Row 2: Name · v2 · Status · date — center-aligned -->
          <div class="flex items-center gap-2 flex-wrap -mt-1">
            <span class="text-base font-semibold truncate">{{ processStore.currentInstance.processName || processStore.currentInstance.processKey || t('processInstance') }}</span>
            <span class="inline-flex items-center text-[11px] font-bold px-2 py-0.5 rounded-full bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300 shrink-0">
              v{{ processStore.currentInstance.processVersion }}
            </span>
            <span class="text-foreground/80 text-xs shrink-0">·</span>
            <Badge
              variant="secondary"
              class="shrink-0 text-[11px] px-1.5 py-px rounded-full"
              data-testid="instance-status-badge"
              :class="processInstanceStatus(processStore.currentInstance) === 'CANCELLED'
                ? 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
                : processStore.currentInstance.completedAt
                  ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                  : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'"
            >
              {{ t(headerStatusLabelKey) }}
            </Badge>
            <span class="text-foreground/80 text-xs shrink-0">·</span>
            <span class="inline-flex items-center text-[11px] px-1 py-px rounded-full bg-secondary text-muted-foreground shrink-0">
              <Calendar class="h-3 w-3 mr-0.5" />
              {{ formatDateTime(processStore.currentInstance.startedAt) }}
            </span>
          </div>
        </div>
      </div>

      <!-- Tabs: WO-ACL-14 — the shared TabsBar (single -mb-px on the nav, keyboard
           rotation, horizontal scroll on narrow screens). The old local strip
           carried -mb-px on every button, which pushed the active underline
           under the container border (P-55). -->
      <div class="flex border-b border-border">
        <TabsBar :tabs="instanceTabs" :active-id="activeTab" @update:active-id="selectTab" class="flex-1" />
      </div>

      <div v-if="tabLoading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

      <template v-if="!tabLoading">
        <div v-if="activeTab === 'bpmn'" class="flex-1 min-h-0 flex flex-col">
          <div v-if="bpmnXml" class="border border-border rounded-lg bg-card flex flex-1 min-h-0">
            <div class="flex-1 min-h-0">
              <BpmnViewer
                :xml="bpmnXml"
                :active-element-ids="activeElementIds"
                :incident-element-ids="incidentElementIds"
                :completed-element-ids="completedElementIds"
                :element-counts="elementCounts"
                :plane-element-id="viewPlane"
                @element-click="onElementClick"
                @plane-change="viewPlane = $event"
              />
            </div>
            <!-- WO-ACL-11 criterion 38: the properties panel is the same height as
                 the canvas (flex stretch) and scrolls INSIDE itself (overflow-y-auto);
                 the fixed max-height is gone. -->
            <div v-if="selectedElement" class="w-80 border-l border-border p-4 space-y-3 bg-muted/30 overflow-y-auto">
              <div class="flex items-center justify-between">
                <h3 class="text-sm font-bold">{{ t('elementDetails') }}</h3>
                <button class="text-xs text-muted-foreground hover:text-foreground" @click="selectedElement = null">{{ t('close') }}</button>
              </div>

              <div class="text-sm space-y-1">
                <div v-if="selectedNode?.name"><span class="text-muted-foreground">{{ t('name') }}:</span> {{ selectedNode.name }}</div>
                <div v-if="selectedNode?.type">
                  <span class="text-muted-foreground">{{ t('elementType') }}:</span>
                  <span class="ml-1 inline-flex items-center px-2 py-0.5 rounded text-xs font-mono bg-muted">{{ selectedNode.type }}<template v-if="selectedNode.eventDefinition">/{{ selectedNode.eventDefinition }}</template></span>
                </div>
                <div><span class="text-muted-foreground">{{ t('elementId') }}:</span> <CopyableId :value="selectedElement" /></div>
              </div>

              <!-- cross-links: open dialog with element tasks/incidents instead of switching tab -->
              <div v-if="selectedHasUserTask || selectedHasServiceTask || selectedHasIncident" class="space-y-2 pt-2 border-t border-border">
                <button v-if="selectedHasUserTask" class="flex items-center gap-1.5 w-full text-left text-sm text-primary hover:underline" @click="openElementDialog(selectedElement!)">
                  <ArrowRight class="h-3.5 w-3.5" /> {{ t('openUserTask') }}
                </button>
                <button v-if="selectedHasServiceTask" class="flex items-center gap-1.5 w-full text-left text-sm text-primary hover:underline" @click="openElementDialog(selectedElement!)">
                  <ArrowRight class="h-3.5 w-3.5" /> {{ t('openServiceTask') }}
                </button>
                <button v-if="selectedHasIncident" class="flex items-center gap-1.5 w-full text-left text-sm text-red-600 hover:underline" @click="openElementDialog(selectedElement!)">
                  <ArrowRight class="h-3.5 w-3.5" /> {{ t('openIncident') }}
                </button>
              </div>

              <!-- element configuration breakdown (BPMN settings) -->
              <div v-if="selectedNodeProps.length" class="pt-2 border-t border-border space-y-1.5">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('properties') }}</h4>
                <div v-for="[k, v] in selectedNodeProps" :key="k" class="text-xs">
                  <span class="text-muted-foreground font-mono">{{ k }}:</span>
                  <span class="ml-1 font-mono break-all">{{ typeof v === 'object' ? JSON.stringify(v) : v }}</span>
                </div>
              </div>

              <!-- WO-ENG-14/15: static ioMapping declaration as a table -->
              <IoMappingTable title-key="inputMappings" :mappings="selectedNode?.properties['inputMappings']" />
              <IoMappingTable title-key="outputMappings" :mappings="selectedNode?.properties['outputMappings']" />

              <!-- WO-ENG-14: resolved runtime input variables of this activity run -->
              <div v-if="selectedActivityVariables.length" class="pt-2 border-t border-border space-y-1">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('activityLocalVariables') }}</h4>
                <table class="w-full text-xs">
                  <tbody>
                    <tr v-for="v in selectedActivityVariables" :key="v.name" class="border-t border-border">
                      <td class="py-1 pr-2 font-mono">{{ v.name }}</td>
                      <td class="py-1 pr-2"><span class="inline-flex items-center px-1.5 py-0.5 rounded text-xs bg-muted">{{ v.type }}</span></td>
                      <td class="py-1 font-mono break-all">{{ v.value }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <!-- node documentation -->
              <div v-if="selectedNode?.documentation" class="pt-2 border-t border-border space-y-1">
                <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('properties') }}</h4>
                <p class="text-xs whitespace-pre-wrap break-words">{{ selectedNode.documentation }}</p>
              </div>

              <!-- sequence flow: name, source -> target, FEEL condition -->
              <template v-if="selectedFlow">
                <div class="text-sm space-y-1">
                  <div v-if="selectedFlow.name"><span class="text-muted-foreground">{{ t('name') }}:</span> {{ selectedFlow.name }}</div>
                  <div class="text-xs text-muted-foreground font-mono">{{ selectedFlow.sourceRef }} → {{ selectedFlow.targetRef }}</div>
                </div>
                <div class="pt-2 border-t border-border space-y-1">
                  <h4 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('conditionFeel') }}</h4>
                  <p v-if="selectedFlow.conditionExpression" class="text-xs font-mono break-all bg-muted rounded px-2 py-1">{{ selectedFlow.conditionExpression }}</p>
                  <p v-else class="text-xs text-muted-foreground">{{ t('noConditionFlow') }}</p>
                </div>
              </template>

              <div v-if="!selectedNode && !selectedFlow" class="pt-2 border-t border-border text-xs text-muted-foreground">{{ t('noDetailsForElement') }}</div>
            </div>
          </div>
          <div v-else class="p-6 text-sm text-muted-foreground">{{ t('bpmnNotAvailable') }}</div>
        </div>

        <div v-if="activeTab === 'variables'" class="space-y-4">
        <div class="border border-border rounded-lg overflow-hidden">
          <table class="w-full text-sm">
            <thead class="bg-muted">
              <tr>
                <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('type') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('value') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in processStore.currentVariables" :key="v.name" class="border-t border-border">
                <td class="px-4 py-3 font-mono">{{ v.name }}</td>
                <td class="px-4 py-3">
                  <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted">{{ v.type }}</span>
                </td>
                <!-- WO-UI-23: короткие — как раньше (truncate + title); у длинных —
                     кнопка-инспектор с полным значением (модалка + копия).
                     truncate живёт на внутреннем span, а не на td: на table-cell
                     браузерный computed overflow остаётся visible и ellipsis не
                     срабатывает (проверено живым Chromium — ячейка растягивалась
                     до 718px); block-span внутри max-w-xs-ячейки клиппит честно. -->
                <td class="px-4 py-3 max-w-xs" :title="v.value">
                  <span class="block truncate">{{ v.value }}</span>
                  <button
                    v-if="isLongVariableValue(v)"
                    class="mt-1 text-xs text-primary hover:underline shrink-0"
                    :data-testid="`inspect-variable-${v.name}`"
                    @click.stop="openVariableInspector(v)"
                  >
                    {{ t('showFullValue') }}
                  </button>
                </td>
              </tr>
              <tr v-if="!processStore.currentVariables.length">
                <td colspan="3" class="px-4 py-6 text-center text-muted-foreground">{{ t('noVariables') }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <!-- WO-VT-3 раунд 2 (E-VT3-1, мокап Б): «Сохранить переменные как
             шаблон» рядом с таблицей переменных; после сохранения — тост со
             ссылкой на менеджер страницы определения. -->
        <InstanceSnapshotPanel
          v-if="processStore.currentInstance"
          ref="snapshotOnVariablesRef"
          :process-key="processStore.currentInstance.processKey ?? ''"
          :process-definition-id="processStore.currentInstance.processDefinitionId"
          :instance-variables="processStore.currentVariables"
        />
        </div>

        <div v-if="activeTab === 'tasks'" class="border border-border rounded-lg overflow-hidden">
          <table class="w-full text-sm">
            <thead class="bg-muted">
              <tr>
                <th class="px-4 py-3 text-left font-medium">ID</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('completedAt') }}</th>
                <th class="px-4 py-3 text-left font-medium"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in (taskStore.userTasks?.data || [])" :key="task.id" class="border-t border-border">
                <td class="px-4 py-3"><CopyableId :value="task.id" /></td>
                <td class="px-4 py-3">{{ task.name || task.code || '—' }}</td>
                <td class="px-4 py-3">
                  <StatusBadge :status="task.status" :completed-at="task.completedAt" />
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(task.createdAt) }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ task.completedAt ? formatDateTime(task.completedAt) : '—' }}</td>
                <td class="px-4 py-3">
                  <button
                    v-if="isTaskActive(task.status, task.completedAt)"
                    class="text-sm text-primary hover:underline"
                    @click.stop="openCompleteModal(task.id, 'user')"
                  >
                    {{ t('complete') }}
                  </button>
                </td>
              </tr>
              <tr v-if="!taskStore.userTasks?.data?.length">
                <td colspan="6" class="px-4 py-6 text-center text-muted-foreground">{{ t('noUserTasks') }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="activeTab === 'serviceTasks'" class="border border-border rounded-lg overflow-hidden">
          <table class="w-full text-sm">
            <thead class="bg-muted">
              <tr>
                <th class="px-4 py-3 text-left font-medium">ID</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('jobType') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('completedAt') }}</th>
                <th class="px-4 py-3 text-left font-medium"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in (taskStore.serviceTasks?.data || [])" :key="task.id" class="border-t border-border">
                <td class="px-4 py-3"><CopyableId :value="task.id" /></td>
                <td class="px-4 py-3">{{ task.name || task.code || '—' }}</td>
                <td class="px-4 py-3 font-mono text-xs">{{ task.job }}</td>
                <td class="px-4 py-3">
                  <StatusBadge :status="task.status" :completed-at="task.completedAt" />
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(task.createdAt) }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ task.completedAt ? formatDateTime(task.completedAt) : '—' }}</td>
                <td class="px-4 py-3">
                  <button
                    v-if="isTaskActive(task.status, task.completedAt)"
                    class="text-sm text-primary hover:underline"
                    @click.stop="openCompleteModal(task.id, 'service')"
                  >
                    {{ t('complete') }}
                  </button>
                </td>
              </tr>
              <tr v-if="!taskStore.serviceTasks?.data?.length">
                <td colspan="7" class="px-4 py-6 text-center text-muted-foreground">{{ t('noServiceTasksInTab') }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="activeTab === 'incidents'" class="border border-border rounded-lg overflow-hidden">
          <table class="w-full text-sm">
            <thead class="bg-muted">
              <tr>
                <th class="px-4 py-3 text-left font-medium">ID</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('message') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('completedAt') }}</th>
                <th class="px-4 py-3 text-left font-medium"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="inc in (incidentStore.incidents?.data || [])" :key="inc.id" class="border-t border-border">
                <td class="px-4 py-3"><CopyableId :value="inc.id" /></td>
                <td class="px-4 py-3 text-sm max-w-xs truncate" :title="inc.message">{{ inc.message }}</td>
                <td class="px-4 py-3">
                  <StatusBadge :status="inc.completedAt ? 'RESOLVED' : 'OPEN'" />
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(inc.createdAt) }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ inc.completedAt ? formatDateTime(inc.completedAt) : '—' }}</td>
                <td class="px-4 py-3">
                  <button
                    v-if="!inc.completedAt"
                    class="text-sm text-primary hover:underline"
                    @click.stop="openResolveModal(inc.id)"
                  >
                    {{ t('resolveAction') }}
                  </button>
                </td>
              </tr>
              <tr v-if="!incidentStore.incidents?.data?.length">
                <td colspan="6" class="px-4 py-6 text-center text-muted-foreground">{{ t('noIncidentsInTab') }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="activeTab === 'history'" class="border border-border rounded-lg overflow-hidden">
          <table class="w-full text-sm">
            <thead class="bg-muted">
              <tr>
                <th class="px-4 py-3 text-left font-medium">{{ t('elementId') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('type') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('completedAt') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="act in processStore.currentActivities" :key="act.id" class="border-t border-border">
                <td class="px-4 py-3 font-mono text-xs">{{ act.bpmnElementId }}</td>
                <td class="px-4 py-3 text-muted-foreground text-xs">{{ act.type }}</td>
                <td class="px-4 py-3">
                  <StatusBadge :status="act.status" />
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(act.createdAt) }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ act.completedAt ? formatDateTime(act.completedAt) : '—' }}</td>
              </tr>
              <tr v-if="!processStore.currentActivities.length">
                <td colspan="5" class="px-4 py-6 text-center text-muted-foreground">{{ t('noHistory') }}</td>
              </tr>
            </tbody>
          </table>
          <!-- WO-UI-18 часть C: activities идут постранично (paged-эндпоинт),
               остаток подгружается кнопкой, а не всем списком разом. -->
          <div v-if="processStore.hasMoreActivities" class="px-4 py-3 border-t border-border bg-muted/30 text-center">
            <button
              class="px-4 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
              @click="loadMoreActivities"
            >
              {{ t('showMore') }} ({{ processStore.currentActivities.length }} / {{ processStore.currentActivitiesTotal }})
            </button>
          </div>
        </div>

        <div v-if="activeTab === 'subprocesses'" class="border border-border rounded-lg overflow-hidden">
          <table class="w-full text-sm">
            <thead class="bg-muted">
              <tr>
                <th class="px-4 py-3 text-left font-medium">ID</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('process') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('started') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('completed') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="sp in processStore.currentSubprocesses" :key="sp.id" class="border-t border-border hover:bg-muted/50 cursor-pointer" tabindex="0" @click="router.push(`/processes/instances/${sp.id}`)" @keydown.enter="router.push(`/processes/instances/${sp.id}`)">
                <td class="px-4 py-3"><CopyableId :value="sp.id" /></td>
                <td class="px-4 py-3">
                  {{ sp.processName || sp.processKey || '—' }}
                  <span v-if="sp.processVersion" class="ml-1 text-xs text-muted-foreground">v{{ sp.processVersion }}</span>
                </td>
                <td class="px-4 py-3">
                  <!-- WO-UI-21 Раунд 2: тот же дериватор, что в списке и шапке -->
                  <StatusBadge :status="processInstanceStatus(sp)" />
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(sp.startedAt) }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ sp.completedAt ? formatDateTime(sp.completedAt) : '—' }}</td>
              </tr>
              <tr v-if="!processStore.currentSubprocesses.length">
                <td colspan="5" class="px-4 py-6 text-center text-muted-foreground">{{ t('noSubprocesses') }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- WO-VT-3 раунд 2 (E-VT3-1): вкладка «Шаблоны» убрана — сообщение в
             модалке из меню «Действия», снимок на вкладке «Переменные»,
             менеджер на странице определения. -->
      </template>
    </template>

    <!-- WO-VT-3 раунд 2 (E-VT3-1, мокап А): модалка «Отправить сообщение». -->
    <div
      v-if="showMessageModal && processStore.currentInstance"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4"
      data-testid="send-message-modal"
      @click.self="showMessageModal = false"
    >
      <div
        ref="messageModalRef"
        class="bg-card rounded-lg shadow-lg w-full max-w-2xl p-6 space-y-4 max-h-[90dvh] overflow-y-auto"
        role="dialog"
        aria-modal="true"
        :aria-label="t('presetSendMessageTitle')"
      >
        <div class="flex items-center justify-between gap-2">
          <h2 class="text-lg font-bold">{{ t('presetSendMessageTitle') }}</h2>
          <button class="text-xs text-muted-foreground hover:text-foreground h-8 px-2" @click="showMessageModal = false">{{ t('close') }}</button>
        </div>
        <InstanceMessagePanel
          bare
          :process-key="processStore.currentInstance.processKey ?? ''"
          :process-instance-id="processStore.currentInstance.id"
        />
        <div class="flex justify-end">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted h-9" @click="showMessageModal = false">{{ t('close') }}</button>
        </div>
      </div>
    </div>

    <div
      v-if="showCompleteModal"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showCompleteModal = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <h2 class="text-lg font-bold">{{ completingTaskType === 'resolve' ? t('resolveIncidentTitle') : t('completeTask') }}</h2>
        <!-- WO-VT-1: переменные завершения — ручной ввод или шаблон элемента -->
        <PresetPicker
          v-if="processStore.currentInstance?.processKey"
          ref="completePickerRef"
          :process-key="processStore.currentInstance.processKey"
          :target-kind="completePickerKind()"
          :target-ref="completePresetRef"
          @change="onCompletePickerChange"
        />
        <div class="flex justify-end gap-2 pt-2">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="showCompleteModal = false">{{ t('cancelAction') }}</button>
          <button
            class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50"
            :disabled="completeAskMissing.length > 0 || completePickerInvalid"
            :title="completeAskMissing.length ? t('presetFillAskFields', { fields: completeAskMissing.join(', ') }) : ''"
            @click="confirmComplete"
          >
            {{ completingTaskType === 'resolve' ? t('resolveAction') : t('confirm') }}
          </button>
        </div>
      </div>
    </div>

    <!-- WO-UI-21 Раунд 2: confirm-диалог отмены (необратимое действие).
         Тот же page-local overlay-паттерн, что showCompleteModal выше
         (AlertDialog-примитива в components/ui нет). Поля причины нет:
         бэкенд-эндпоинт POST .../cancel причины не принимает, причина —
         follow-up с изменением контракта (см. отчёт). -->
    <div
      v-if="showCancelDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      data-testid="cancel-dialog"
      @click.self="showCancelDialog = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <h2 class="text-lg font-bold">{{ t('cancelProcessTitle') }}</h2>
        <p class="text-sm text-muted-foreground">{{ t('cancelProcessConfirm') }}</p>
        <div class="flex justify-end gap-2 pt-2">
          <button
            class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted"
            data-testid="cancel-dialog-back"
            @click="showCancelDialog = false"
          >
            {{ t('cancelAction') }}
          </button>
          <Button
            variant="destructive"
            class="px-4 py-2 text-sm"
            data-testid="cancel-dialog-confirm"
            :disabled="cancelInFlight"
            @click="confirmCancel"
          >
            {{ t('cancelProcess') }}
          </Button>
        </div>
      </div>
    </div>

    <!-- WO-UI-23: инспектор полного значения переменной. Тот же page-local
         overlay-паттерн, что cancel-dialog выше (AlertDialog-примитива в
         components/ui нет). <pre> — стиль IncidentDetail.vue (message-блок):
         whitespace-pre-wrap + font-mono + bg-muted. Кнопка копии — та же
         Copy/Check-пара, что CopyableId рядом в этом же файле. -->
    <div
      v-if="inspectedVariable"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      data-testid="variable-inspector"
      @click.self="closeVariableInspector"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-2xl p-6 space-y-4">
        <div class="flex items-center justify-between gap-2 min-w-0">
          <h2 class="text-lg font-bold font-mono truncate" data-testid="variable-inspector-title">{{ inspectedVariable.name }}</h2>
          <button class="text-xs text-muted-foreground hover:text-foreground shrink-0" @click="closeVariableInspector">{{ t('close') }}</button>
        </div>
        <pre class="text-sm whitespace-pre-wrap font-mono bg-muted p-3 rounded max-h-[60vh] overflow-auto break-words" data-testid="variable-inspector-value">{{ formatVariableValue(inspectedVariable) }}</pre>
        <div class="flex justify-end gap-2 pt-2">
          <Button
            variant="outline"
            size="sm"
            class="h-8 px-3 text-xs"
            data-testid="variable-inspector-copy"
            @click="copyInspectedVariable"
          >
            <Check v-if="variableCopied" class="h-4 w-4 text-green-500" />
            <Copy v-else class="h-4 w-4" />
            {{ t('copyToClipboard') }}
          </Button>
        </div>
      </div>
    </div>

    <!-- Element dialog: shows tasks/incidents for the selected BPMN element, with inline complete/resolve form -->
    <div
      v-if="showElementDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="cancelElementDialog"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-lg p-6 space-y-4">
        <!-- List view: tasks and incidents for this element -->
        <template v-if="elementDialogView === 'list'">
          <div class="flex items-center justify-between">
            <h2 class="text-lg font-bold font-mono text-sm">{{ dialogSelectedElement }}</h2>
            <button class="text-xs text-muted-foreground hover:text-foreground" @click="cancelElementDialog">{{ t('close') }}</button>
          </div>

          <div v-if="dialogUserTasks.length" class="space-y-2">
            <h3 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('tasks') }}</h3>
            <div v-for="task in dialogUserTasks" :key="task.id" class="flex items-center justify-between text-sm border-b border-border pb-1 last:border-b-0">
              <div class="flex items-center gap-2 min-w-0">
                <span class="font-mono truncate">{{ task.name || task.code || task.id }}</span>
                <span v-if="task.status"><StatusBadge :status="task.status" :completed-at="task.completedAt" /></span>
              </div>
              <button
                v-if="isTaskActive(task.status, task.completedAt)"
                class="text-sm text-primary hover:underline shrink-0 ml-2"
                @click="startElementDialogComplete(task.id, 'user')"
              >
                {{ t('complete') }}
              </button>
            </div>
          </div>

          <div v-if="dialogServiceTasks.length" class="space-y-2">
            <h3 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('serviceTasks') }}</h3>
            <div v-for="task in dialogServiceTasks" :key="task.id" class="flex items-center justify-between text-sm border-b border-border pb-1 last:border-b-0">
              <div class="flex items-center gap-2 min-w-0">
                <span class="font-mono truncate">{{ task.name || task.code || task.id }}</span>
                <span v-if="task.status"><StatusBadge :status="task.status" :completed-at="task.completedAt" /></span>
              </div>
              <button
                v-if="isTaskActive(task.status, task.completedAt)"
                class="text-sm text-primary hover:underline shrink-0 ml-2"
                @click="startElementDialogComplete(task.id, 'service')"
              >
                {{ t('complete') }}
              </button>
            </div>
          </div>

          <div v-if="dialogIncidents.length" class="space-y-2">
            <h3 class="text-xs font-semibold text-muted-foreground uppercase">{{ t('incidentsTab') }}</h3>
            <div v-for="inc in dialogIncidents" :key="inc.id" class="flex items-center justify-between text-sm border-b border-border pb-1 last:border-b-0">
              <div class="flex items-center gap-2 min-w-0">
                <span class="text-xs truncate" :title="inc.message">{{ inc.message }}</span>
                <span v-if="!inc.completedAt"><StatusBadge status="OPEN" /></span>
              </div>
              <button
                v-if="!inc.completedAt"
                class="text-sm text-red-600 hover:underline shrink-0 ml-2"
                @click="startElementDialogResolve(inc.id)"
              >
                {{ t('resolveAction') }}
              </button>
            </div>
          </div>

          <div v-if="!dialogUserTasks.length && !dialogServiceTasks.length && !dialogIncidents.length" class="text-sm text-muted-foreground py-4 text-center">
            {{ t('noTasksOrIncidents') }}
          </div>

          <div class="flex justify-end pt-2">
            <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="cancelElementDialog">{{ t('close') }}</button>
          </div>
        </template>

        <!-- Form view: variable editor for complete / resolve -->
        <template v-if="elementDialogView === 'form'">
          <h2 class="text-lg font-bold">{{ completingTaskType === 'resolve' ? t('resolveIncidentTitle') : t('completeTask') }}</h2>
          <!-- WO-VT-1: тот же PresetPicker, что в showCompleteModal выше -->
          <PresetPicker
            v-if="processStore.currentInstance?.processKey"
            ref="completePickerRef"
            :process-key="processStore.currentInstance.processKey"
            :target-kind="completePickerKind()"
            :target-ref="completePresetRef"
            @change="onCompletePickerChange"
          />
          <div class="flex justify-end gap-2 pt-2">
            <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="cancelElementDialogForm">{{ t('cancelAction') }}</button>
            <button
              class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50"
              :disabled="completeAskMissing.length > 0 || completePickerInvalid"
              :title="completeAskMissing.length ? t('presetFillAskFields', { fields: completeAskMissing.join(', ') }) : ''"
              @click="confirmComplete"
            >
              {{ completingTaskType === 'resolve' ? t('resolveAction') : t('confirm') }}
            </button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
