<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useProcessStore } from '@/stores/process'
import { useTaskStore } from '@/stores/task'
import { useIncidentStore } from '@/stores/incident'
import { useBreadcrumbLabel } from '@/composables/useBreadcrumbLabel'
import { useToast } from '@/composables/useToast'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import * as processService from '@/services/processService'
import * as variableService from '@/services/variableService'
import type { ProcessVariable, BpmnNode, BpmnFlow } from '@/types/api'
import { isTaskActive } from '@/shared/lib/utils'
import { RefreshCw, ArrowRight, ArrowLeft, Download, Calendar } from 'lucide-vue-next'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import IoMappingTable from '@/widgets/shared/IoMappingTable.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'
import TabsBar from '@/widgets/shared/TabsBar.vue'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { buildDiagnosticJson } from '@/shared/lib/diagnostic'

const route = useRoute()
const router = useRouter()
const processStore = useProcessStore()
const taskStore = useTaskStore()
const incidentStore = useIncidentStore()
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

const activeTab = ref<'bpmn' | 'variables' | 'tasks' | 'serviceTasks' | 'incidents' | 'history' | 'subprocesses'>('bpmn')

// WO-ACL-14 criteria 1-3: ONE tab component (TabsBar) — the local tab strip is gone.
// WO-UI-17 F24: no `as const` — TabsBar takes a mutable TabItem[]; the literal
// union lives on activeTab, and selectTab narrows the string payload honestly.
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
// through the list instead of casting blindly.
function selectTab(id: string) {
  if (instanceTabs.value.some((tab) => tab.id === id)) {
    activeTab.value = id as typeof activeTab.value
  }
}
const bpmnXml = ref('')
const selectedElement = ref<string | null>(null)

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

function openElementDialog(elementId: string) {
  dialogSelectedElement.value = elementId
  showElementDialog.value = true
  elementDialogView.value = 'list'
}

function startElementDialogComplete(taskId: string, type: 'user' | 'service') {
  completingTaskId.value = taskId
  completingTaskType.value = type
  completeVars.value = []
  newVarName.value = ''
  newVarValue.value = ''
  jsonError.value = ''
  elementDialogView.value = 'form'
}

function startElementDialogResolve(incidentId: string) {
  completingTaskId.value = incidentId
  completingTaskType.value = 'resolve'
  completeVars.value = []
  newVarName.value = ''
  newVarValue.value = ''
  jsonError.value = ''
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
const completeVars = ref<{ name: string; type: string; value: string }[]>([])
const newVarName = ref('')
const newVarType = ref('STRING')
const newVarValue = ref('')
const jsonError = ref('')

function addVariable() {
  jsonError.value = ''
  if (newVarName.value) {
    if (newVarType.value === 'JSON') {
      try {
        JSON.parse(newVarValue.value)
      } catch {
        jsonError.value = 'Invalid JSON'
        return
      }
    }
    completeVars.value.push({ name: newVarName.value, type: newVarType.value, value: newVarValue.value })
    newVarName.value = ''
    newVarValue.value = ''
  }
}

function removeVariable(index: number) {
  completeVars.value.splice(index, 1)
}

function openCompleteModal(taskId: string, type: 'user' | 'service') {
  completingTaskId.value = taskId
  completingTaskType.value = type
  completeVars.value = []
  newVarName.value = ''
  newVarValue.value = ''
  showCompleteModal.value = true
}

function openResolveModal(incidentId: string) {
  completingTaskId.value = incidentId
  completingTaskType.value = 'resolve'
  completeVars.value = []
  newVarName.value = ''
  newVarValue.value = ''
  showCompleteModal.value = true
}

async function confirmComplete() {
  if (jsonError.value) return
  // flush a variable that was typed but not yet "added" — otherwise it would be silently dropped
  if (newVarName.value) addVariable()
  if (jsonError.value) return
  const variables: ProcessVariable[] = completeVars.value.map((v) => ({
    name: v.name,
    type: v.type as ProcessVariable['type'],
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
      processStore.fetchActivities(pi.id),
      processStore.fetchSubprocesses(pi.id),
    ])
  } finally {
    tabLoading.value = false
  }
}

async function loadBpmnXml() {
  const pi = processStore.currentInstance
  if (!pi || bpmnXml.value) return
  try {
    bpmnXml.value = await processService.getProcessDefinitionXml(pi.processDefinitionId)
  } catch {
    // XML not available
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
  bpmnXml.value = ''
  await processStore.fetchInstance(pi.id) // refresh the instance itself so its status badge updates
  await loadTabData()
  await loadBpmnXml()
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
  bpmnXml.value = ''
  selectedElement.value = null
  await processStore.fetchInstance(id)
  // load tasks/service-tasks/incidents/activities/subprocesses up-front so the BPMN element
  // panel can offer cross-links and highlighting immediately
  await loadTabData()
  const pi = processStore.currentInstance
  if (pi) {
    await processStore.fetchStructure(pi.processDefinitionId)
  }
  await loadBpmnXml()
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
          <div class="flex items-center gap-2">
            <span class="text-sm text-muted-foreground whitespace-nowrap">{{ t('processInstances') }}</span>
            <span class="text-foreground/80 text-xs">·</span>
            <span class="text-sm text-muted-foreground/80"><CopyableId :value="processStore.currentInstance.id" /></span>
            <span class="flex-1" />
            <Button variant="outline" size="sm" class="h-8 px-3 text-xs" @click="downloadDiagnostic">
              <Download class="h-4 w-4" />
              {{ t('downloadDiagnostic') }}
            </Button>
            <Button variant="outline" size="sm" class="h-8 px-3 text-xs" :disabled="processStore.loading || tabLoading" @click="reloadAll">
              <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': processStore.loading || tabLoading }" />
              {{ t('refresh') }}
            </Button>
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
              :class="processStore.currentInstance.completedAt
                ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'"
            >
              {{ processStore.currentInstance.completedAt ? t('completed') : t('running') }}
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
                @element-click="selectedElement = $event"
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

        <div v-if="activeTab === 'variables'" class="border border-border rounded-lg overflow-hidden">
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
                <td class="px-4 py-3 max-w-xs truncate" :title="v.value">{{ v.value }}</td>
              </tr>
              <tr v-if="!processStore.currentVariables.length">
                <td colspan="3" class="px-4 py-6 text-center text-muted-foreground">{{ t('noVariables') }}</td>
              </tr>
            </tbody>
          </table>
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
                  <StatusBadge :status="sp.completedAt ? 'COMPLETED' : 'RUNNING'" />
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
      </template>
    </template>

    <div
      v-if="showCompleteModal"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showCompleteModal = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <h2 class="text-lg font-bold">{{ completingTaskType === 'resolve' ? t('resolveIncidentTitle') : t('completeTask') }}</h2>
        <div class="space-y-3">
          <div v-for="(v, i) in completeVars" :key="i" class="flex items-center gap-2 text-sm">
            <span class="font-mono">{{ v.name }}</span>
            <span class="text-muted-foreground">({{ v.type }})</span>
            <span>= {{ v.value }}</span>
            <button class="text-red-500 hover:underline ml-auto" @click="removeVariable(i)">{{ t('remove') }}</button>
          </div>
          <div class="grid grid-cols-[6rem_5.5rem_1fr] gap-2">
            <input v-model="newVarName" :placeholder="t('name')" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
            <select v-model="newVarType" class="px-2 py-1 border border-input rounded text-sm">
              <option>STRING</option>
              <option>UUID</option>
              <option>LONG</option>
              <option>DOUBLE</option>
              <option>BOOLEAN</option>
              <option>JSON</option>
            </select>
            <input v-if="newVarType !== 'JSON'" v-model="newVarValue" :placeholder="t('value')" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
          </div>
          <textarea v-if="newVarType === 'JSON'" v-model="newVarValue" :placeholder="t('value')" class="w-full px-2 py-1 border border-input rounded text-sm font-mono" rows="3"></textarea>
          <p v-if="jsonError" class="text-xs text-red-500">{{ jsonError }}</p>
          <button
            class="w-full px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors disabled:opacity-50"
            :disabled="!newVarName"
            @click="addVariable"
          >
            + {{ t('addVariable') }}
          </button>
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="showCompleteModal = false">{{ t('cancelAction') }}</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="confirmComplete">
            {{ completingTaskType === 'resolve' ? t('resolveAction') : t('confirm') }}
          </button>
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
          <div class="space-y-3">
            <div v-for="(v, i) in completeVars" :key="i" class="flex items-center gap-2 text-sm">
              <span class="font-mono">{{ v.name }}</span>
              <span class="text-muted-foreground">({{ v.type }})</span>
              <span>= {{ v.value }}</span>
              <button class="text-red-500 hover:underline ml-auto" @click="removeVariable(i)">{{ t('remove') }}</button>
            </div>
            <div class="grid grid-cols-[6rem_5.5rem_1fr] gap-2">
              <input v-model="newVarName" :placeholder="t('name')" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
              <select v-model="newVarType" class="px-2 py-1 border border-input rounded text-sm">
                <option>STRING</option>
                <option>UUID</option>
                <option>LONG</option>
                <option>DOUBLE</option>
                <option>BOOLEAN</option>
                <option>JSON</option>
              </select>
              <input v-if="newVarType !== 'JSON'" v-model="newVarValue" :placeholder="t('value')" class="px-2 py-1 border border-input rounded text-sm" @keyup.enter="addVariable" />
            </div>
            <textarea v-if="newVarType === 'JSON'" v-model="newVarValue" :placeholder="t('value')" class="w-full px-2 py-1 border border-input rounded text-sm font-mono" rows="3"></textarea>
            <p v-if="jsonError" class="text-xs text-red-500">{{ jsonError }}</p>
            <button
              class="w-full px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors disabled:opacity-50"
              :disabled="!newVarName"
              @click="addVariable"
            >
              + {{ t('addVariable') }}
            </button>
          </div>
          <div class="flex justify-end gap-2 pt-2">
            <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="cancelElementDialogForm">{{ t('cancelAction') }}</button>
            <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="confirmComplete">
              {{ completingTaskType === 'resolve' ? t('resolveAction') : t('confirm') }}
            </button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
