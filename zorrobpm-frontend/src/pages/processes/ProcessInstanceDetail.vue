<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useProcessStore } from '@/stores/process'
import { useTaskStore } from '@/stores/task'
import { useIncidentStore } from '@/stores/incident'
import { useToast } from '@/composables/useToast'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import * as processService from '@/services/processService'
import { getVariables } from '@/services/variableService'
import type { ProcessVariable } from '@/types/api'
import { RefreshCw } from 'lucide-vue-next'
import CopyableId from '@/widgets/shared/CopyableId.vue'

const route = useRoute()
const router = useRouter()
const processStore = useProcessStore()
const taskStore = useTaskStore()
const incidentStore = useIncidentStore()
const toast = useToast()
const { t } = useI18n()

const activeTab = ref<'bpmn' | 'variables' | 'tasks' | 'serviceTasks' | 'incidents' | 'history' | 'subprocesses'>('bpmn')
const bpmnXml = ref('')
const selectedElement = ref<string | null>(null)

// BPMN element highlighting derived from the instance activity history
const activeElementIds = computed(() =>
  processStore.currentActivities.filter((a) => a.status === 'CREATED' || a.status === 'IN_PROGRESS').map((a) => a.bpmnElementId))
const completedElementIds = computed(() =>
  processStore.currentActivities.filter((a) => a.status === 'COMPLETED').map((a) => a.bpmnElementId))
const incidentElementIds = computed(() =>
  processStore.currentActivities.filter((a) => a.status === 'ERROR').map((a) => a.bpmnElementId))
const elementVariables = ref<{ name: string; type: string; value: string }[]>([])
const tabLoading = ref(false)

const showCompleteModal = ref(false)
const completingTaskId = ref('')
const completingTaskType = ref<'user' | 'service'>('user')
const completeVars = ref<{ name: string; type: string; value: string }[]>([])
const newVarName = ref('')
const newVarType = ref('STRING')
const newVarValue = ref('')

function addVariable() {
  if (newVarName.value) {
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
  showCompleteModal.value = true
}

async function confirmComplete() {
  const variables: ProcessVariable[] = completeVars.value.map((v) => ({
    name: v.name,
    type: v.type as ProcessVariable['type'],
    value: v.value,
  }))
  if (completingTaskType.value === 'user') {
    await taskStore.completeUserTask(completingTaskId.value, variables)
  } else {
    await taskStore.completeServiceTask(completingTaskId.value, variables)
  }
  if (!taskStore.error) {
    toast.success('Task completed')
    showCompleteModal.value = false
    await loadTabData()
  } else {
    toast.error(taskStore.error)
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
  await loadTabData()
  await loadBpmnXml()
}

onMounted(async () => {
  const id = route.params.id as string
  await processStore.fetchInstance(id)
  // activities drive both the BPMN highlighting and the history tab — load them up-front
  await processStore.fetchActivities(id)
  if (activeTab.value === 'bpmn') {
    await loadBpmnXml()
  }
})

watch(activeTab, onTabChange)
</script>

<template>
  <div class="space-y-6">
    <div v-if="processStore.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="processStore.error" class="text-sm text-red-500">{{ processStore.error }}</div>

    <template v-else-if="processStore.currentInstance">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold">Process Instance</h1>
          <CopyableId :value="processStore.currentInstance.id" />
        </div>
        <button
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          :disabled="processStore.loading || tabLoading"
          @click="reloadAll"
        >
          <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': processStore.loading || tabLoading }" />
          {{ t('refresh') }}
        </button>
      </div>

      <div class="flex items-center gap-4 text-sm">
        <span
          :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
            processStore.currentInstance.completedAt ? 'bg-green-100 text-green-800' : 'bg-blue-100 text-blue-800']"
        >
          {{ processStore.currentInstance.completedAt ? t('completed') : t('running') }}
        </span>
        <span class="text-muted-foreground">
          {{ t('startedAt') }}: {{ new Date(processStore.currentInstance.startedAt).toLocaleString() }}
        </span>
        <span v-if="processStore.currentInstance.completedAt" class="text-muted-foreground">
          {{ t('completedAtLabel') }}: {{ new Date(processStore.currentInstance.completedAt).toLocaleString() }}
        </span>
      </div>

      <div class="flex gap-1 border-b border-border overflow-x-auto">
        <button
          v-for="tab in (['bpmn', 'variables', 'tasks', 'serviceTasks', 'incidents', 'history', 'subprocesses'] as const)"
          :key="tab"
          class="px-4 py-2 text-sm font-medium border-b-2 transition-colors whitespace-nowrap"
          :class="activeTab === tab ? 'border-primary text-primary' : 'border-transparent text-muted-foreground hover:text-foreground'"
          @click="activeTab = tab"
        >
          {{ tab === 'bpmn' ? t('bpmnFlow') : tab === 'serviceTasks' ? t('serviceTasks') : tab === 'tasks' ? t('tasks') : tab === 'variables' ? t('variables') : tab === 'incidents' ? t('incidentsTab') : tab === 'history' ? t('history') : t('subprocesses') }}
        </button>
      </div>

      <div v-if="tabLoading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

      <template v-if="!tabLoading">
        <div v-if="activeTab === 'bpmn'" class="border border-border rounded-lg bg-card">
          <div v-if="bpmnXml" class="flex">
            <div class="flex-1">
              <BpmnViewer
                :xml="bpmnXml"
                :active-element-ids="activeElementIds"
                :incident-element-ids="incidentElementIds"
                :completed-element-ids="completedElementIds"
                style="height: 500px;"
                @element-click="selectedElement = $event"
              />
            </div>
            <div v-if="selectedElement" class="w-72 border-l border-border p-4 space-y-3 bg-muted/30">
              <h3 class="text-sm font-bold">{{ t('elementDetails') }}</h3>
              <div class="text-sm">
                <span class="text-muted-foreground">{{ t('elementId') }}:</span>
                <CopyableId :value="selectedElement" />
              </div>
              <button class="text-xs text-muted-foreground hover:text-foreground" @click="selectedElement = null">{{ t('close') }}</button>
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
                  <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', task.completedAt ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800']">
                    {{ task.completedAt ? t('completed') : t('active') }}
                  </span>
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ new Date(task.createdAt).toLocaleString() }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ task.completedAt ? new Date(task.completedAt).toLocaleString() : '—' }}</td>
                <td class="px-4 py-3">
                  <button
                    v-if="!task.completedAt"
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
                  <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', task.completedAt ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800']">
                    {{ task.completedAt ? t('completed') : t('active') }}
                  </span>
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ new Date(task.createdAt).toLocaleString() }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ task.completedAt ? new Date(task.completedAt).toLocaleString() : '—' }}</td>
                <td class="px-4 py-3">
                  <button
                    v-if="!task.completedAt"
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
              </tr>
            </thead>
            <tbody>
              <tr v-for="inc in (incidentStore.incidents?.data || [])" :key="inc.id" class="border-t border-border">
                <td class="px-4 py-3"><CopyableId :value="inc.id" /></td>
                <td class="px-4 py-3 text-sm max-w-xs truncate" :title="inc.message">{{ inc.message }}</td>
                <td class="px-4 py-3">
                  <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', inc.completedAt ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800']">
                    {{ inc.completedAt ? t('resolved') : t('open') }}
                  </span>
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ new Date(inc.createdAt).toLocaleString() }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ inc.completedAt ? new Date(inc.completedAt).toLocaleString() : '—' }}</td>
              </tr>
              <tr v-if="!incidentStore.incidents?.data?.length">
                <td colspan="5" class="px-4 py-6 text-center text-muted-foreground">{{ t('noIncidentsInTab') }}</td>
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
                  <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
                    act.status === 'ERROR' ? 'bg-red-100 text-red-800'
                    : act.status === 'COMPLETED' ? 'bg-green-100 text-green-800'
                    : act.status === 'CANCELLED' ? 'bg-gray-100 text-gray-800'
                    : 'bg-yellow-100 text-yellow-800']">
                    {{ act.status }}
                  </span>
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ new Date(act.createdAt).toLocaleString() }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ act.completedAt ? new Date(act.completedAt).toLocaleString() : '—' }}</td>
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
                <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('started') }}</th>
                <th class="px-4 py-3 text-left font-medium">{{ t('completed') }}</th>
                <th class="px-4 py-3 text-left font-medium"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="sp in processStore.currentSubprocesses" :key="sp.id" class="border-t border-border hover:bg-muted/50">
                <td class="px-4 py-3"><CopyableId :value="sp.id" /></td>
                <td class="px-4 py-3">
                  <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', sp.completedAt ? 'bg-green-100 text-green-800' : 'bg-blue-100 text-blue-800']">
                    {{ sp.completedAt ? t('completed') : t('running') }}
                  </span>
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ new Date(sp.startedAt).toLocaleString() }}</td>
                <td class="px-4 py-3 text-muted-foreground">{{ sp.completedAt ? new Date(sp.completedAt).toLocaleString() : '—' }}</td>
                <td class="px-4 py-3">
                  <button class="text-sm text-primary hover:underline" @click="router.push(`/processes/instances/${sp.id}`)">{{ t('view') }}</button>
                </td>
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
        <h2 class="text-lg font-bold">{{ t('completeTask') }}</h2>
        <div class="space-y-3">
          <div v-for="(v, i) in completeVars" :key="i" class="flex items-center gap-2 text-sm">
            <span class="font-mono">{{ v.name }}</span>
            <span class="text-muted-foreground">({{ v.type }})</span>
            <span>= {{ v.value }}</span>
            <button class="text-red-500 hover:underline ml-auto" @click="removeVariable(i)">{{ t('remove') }}</button>
          </div>
          <div class="flex items-center gap-2">
            <input v-model="newVarName" :placeholder="t('name')" class="px-2 py-1 border border-input rounded text-sm w-24" />
            <select v-model="newVarType" class="px-2 py-1 border border-input rounded text-sm">
              <option>STRING</option>
              <option>LONG</option>
              <option>BOOLEAN</option>
            </select>
            <input v-model="newVarValue" :placeholder="t('value')" class="px-2 py-1 border border-input rounded text-sm flex-1" />
            <button class="text-sm text-primary hover:underline" @click="addVariable">{{ t('add') }}</button>
          </div>
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <button class="px-4 py-2 text-sm border border-border rounded-md hover:bg-muted" @click="showCompleteModal = false">{{ t('cancelAction') }}</button>
          <button class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90" @click="confirmComplete">{{ t('confirm') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
