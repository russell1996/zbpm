<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useProcessStore } from '@/stores/process'
import { useTaskStore } from '@/stores/task'
import { useIncidentStore } from '@/stores/incident'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import * as processService from '@/services/processService'
import { getVariables } from '@/services/variableService'

const route = useRoute()
const processStore = useProcessStore()
const taskStore = useTaskStore()
const incidentStore = useIncidentStore()

const activeTab = ref<'bpmn' | 'variables' | 'tasks' | 'incidents'>('bpmn')
const bpmnXml = ref('')
const selectedElement = ref<string | null>(null)
const elementVariables = ref<{ name: string; type: string; value: string }[]>([])

const activeElementIds = computed(() => {
  const ids: string[] = []
  // Active tasks are currently parked — their bpmnElementId is the active element
  for (const task of taskStore.userTasks?.data || []) {
    if (!task.completedAt) ids.push(task.id)
  }
  return ids
})

const completedElementIds = computed(() => {
  const ids: string[] = []
  for (const task of taskStore.userTasks?.data || []) {
    if (task.completedAt) ids.push(task.id)
  }
  return ids
})

const incidentElementIds = computed(() => {
  return (incidentStore.incidents?.data || [])
    .filter((i) => !i.completedAt)
    .map((i) => i.activityId)
})

async function onElementClick(elementId: string) {
  selectedElement.value = elementId
  // Load variables for the instance (all of them for now)
  if (processStore.currentInstance) {
    try {
      const result = await getVariables({ processInstanceId: processStore.currentInstance.id })
      elementVariables.value = result.data
    } catch {
      elementVariables.value = []
    }
  }
}

onMounted(async () => {
  const id = route.params.id as string
  await processStore.fetchInstance(id)
  if (processStore.currentInstance) {
    const pi = processStore.currentInstance
    await Promise.all([
      processStore.fetchVariables({ processInstanceId: pi.id }),
      taskStore.fetchUserTasks({ processInstanceId: pi.id, pageIndex: 0, pageSize: 100 }),
      incidentStore.fetchIncidents({ processInstanceId: pi.id, pageIndex: 0, pageSize: 100 }),
    ])
    // Load BPMN XML from the definition
    try {
      bpmnXml.value = await processService.getProcessDefinitionXml(pi.processDefinitionId)
    } catch {
      // XML not available
    }
  }
})
</script>

<template>
  <div class="space-y-6">
    <div v-if="processStore.loading" class="text-sm text-muted-foreground">Loading...</div>
    <div v-else-if="processStore.error" class="text-sm text-red-500">{{ processStore.error }}</div>

    <template v-else-if="processStore.currentInstance">
      <h1 class="text-2xl font-bold">Process Instance</h1>
      <p class="text-sm text-muted-foreground font-mono">{{ processStore.currentInstance.id }}</p>

      <div class="flex items-center gap-4 text-sm">
        <span
          :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
            processStore.currentInstance.completedAt ? 'bg-green-100 text-green-800' : 'bg-blue-100 text-blue-800']"
        >
          {{ processStore.currentInstance.completedAt ? 'Completed' : 'Running' }}
        </span>
        <span class="text-muted-foreground">
          Started: {{ new Date(processStore.currentInstance.startedAt).toLocaleString() }}
        </span>
        <span v-if="processStore.currentInstance.completedAt" class="text-muted-foreground">
          Completed: {{ new Date(processStore.currentInstance.completedAt).toLocaleString() }}
        </span>
      </div>

      <div class="flex gap-1 border-b border-border">
        <button
          v-for="tab in (['bpmn', 'variables', 'tasks', 'incidents'] as const)"
          :key="tab"
          class="px-4 py-2 text-sm font-medium border-b-2 transition-colors"
          :class="activeTab === tab ? 'border-primary text-primary' : 'border-transparent text-muted-foreground hover:text-foreground'"
          @click="activeTab = tab"
        >
          {{ tab === 'bpmn' ? 'BPMN Flow' : tab.charAt(0).toUpperCase() + tab.slice(1) }}
        </button>
      </div>

      <div v-if="activeTab === 'bpmn'" class="border border-border rounded-lg bg-card">
        <div v-if="bpmnXml" class="flex">
          <div class="flex-1">
            <BpmnViewer
              :xml="bpmnXml"
              :active-element-ids="activeElementIds"
              :incident-element-ids="incidentElementIds"
              :completed-element-ids="completedElementIds"
              style="height: 500px;"
              @element-click="onElementClick"
            />
          </div>
          <div v-if="selectedElement" class="w-72 border-l border-border p-4 space-y-3 bg-muted/30">
            <h3 class="text-sm font-bold">Element Details</h3>
            <div class="text-sm">
              <span class="text-muted-foreground">ID:</span>
              <span class="font-mono ml-1">{{ selectedElement }}</span>
            </div>
            <div v-if="elementVariables.length" class="space-y-1">
              <h4 class="text-xs font-bold text-muted-foreground mt-2">Variables</h4>
              <div v-for="v in elementVariables" :key="v.name" class="text-xs">
                <span class="font-mono">{{ v.name }}</span>
                <span class="text-muted-foreground"> = {{ v.value }}</span>
              </div>
            </div>
            <button class="text-xs text-muted-foreground hover:text-foreground" @click="selectedElement = null">Close</button>
          </div>
        </div>
        <div v-else class="p-6 text-sm text-muted-foreground">BPMN XML not available for this definition.</div>
      </div>

      <div v-if="activeTab === 'variables'" class="border border-border rounded-lg overflow-hidden">
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">Name</th>
              <th class="px-4 py-3 text-left font-medium">Type</th>
              <th class="px-4 py-3 text-left font-medium">Value</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="v in processStore.currentVariables" :key="v.name" class="border-t border-border">
              <td class="px-4 py-3 font-mono">{{ v.name }}</td>
              <td class="px-4 py-3">
                <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted">{{ v.type }}</span>
              </td>
              <td class="px-4 py-3">{{ v.value }}</td>
            </tr>
            <tr v-if="!processStore.currentVariables.length">
              <td colspan="3" class="px-4 py-6 text-center text-muted-foreground">No variables</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="activeTab === 'tasks'" class="border border-border rounded-lg overflow-hidden">
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">ID</th>
              <th class="px-4 py-3 text-left font-medium">Name</th>
              <th class="px-4 py-3 text-left font-medium">Status</th>
              <th class="px-4 py-3 text-left font-medium">Created</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in (taskStore.userTasks?.data || [])" :key="t.id" class="border-t border-border">
              <td class="px-4 py-3 font-mono text-xs">{{ t.id.slice(0, 8) }}...</td>
              <td class="px-4 py-3">{{ t.name || t.code || '—' }}</td>
              <td class="px-4 py-3">
                <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', t.completedAt ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800']">
                  {{ t.completedAt ? 'Completed' : 'Active' }}
                </span>
              </td>
              <td class="px-4 py-3 text-muted-foreground">{{ new Date(t.createdAt).toLocaleString() }}</td>
            </tr>
            <tr v-if="!taskStore.userTasks?.data?.length">
              <td colspan="4" class="px-4 py-6 text-center text-muted-foreground">No tasks</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="activeTab === 'incidents'" class="border border-border rounded-lg overflow-hidden">
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">ID</th>
              <th class="px-4 py-3 text-left font-medium">Message</th>
              <th class="px-4 py-3 text-left font-medium">Status</th>
              <th class="px-4 py-3 text-left font-medium">Created</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="inc in (incidentStore.incidents?.data || [])" :key="inc.id" class="border-t border-border">
              <td class="px-4 py-3 font-mono text-xs">{{ inc.id.slice(0, 8) }}...</td>
              <td class="px-4 py-3 text-sm">{{ inc.message }}</td>
              <td class="px-4 py-3">
                <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', inc.completedAt ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800']">
                  {{ inc.completedAt ? 'Resolved' : 'Open' }}
                </span>
              </td>
              <td class="px-4 py-3 text-muted-foreground">{{ new Date(inc.createdAt).toLocaleString() }}</td>
            </tr>
            <tr v-if="!incidentStore.incidents?.data?.length">
              <td colspan="4" class="px-4 py-6 text-center text-muted-foreground">No incidents</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
