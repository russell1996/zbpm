<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { GitBranch, ListTodo, AlertTriangle, CheckCircle, FileText, Play } from 'lucide-vue-next'
import { getProcessDefinitions } from '@/services/processService'
import { getProcessInstances } from '@/services/instanceService'
import { getUserTasks } from '@/services/taskService'
import { getIncidents } from '@/services/incidentService'

const router = useRouter()

const activeProcesses = ref(0)
const openTasks = ref(0)
const openIncidents = ref(0)
const completedToday = ref(0)
const loading = ref(true)

const recentDefinitions = ref<{ id: string; name: string; key: string; version: number }[]>([])

onMounted(async () => {
  loading.value = true
  try {
    const [defs, instances, tasks, incidents] = await Promise.all([
      getProcessDefinitions({ latestVersionOnly: true, pageSize: 5 }),
      getProcessInstances({ pageSize: 100 }),
      getUserTasks({ completed: false, pageSize: 100 }),
      getIncidents({ pageSize: 100 }),
    ])

    recentDefinitions.value = defs.data
    activeProcesses.value = instances.data.filter((i) => !i.completedAt).length
    openTasks.value = tasks.totalElements
    openIncidents.value = incidents.data.filter((i) => !i.completedAt).length

    const today = new Date().toDateString()
    completedToday.value = instances.data.filter(
      (i) => i.completedAt && new Date(i.completedAt).toDateString() === today,
    ).length
  } catch {
    // API unavailable — show empty dashboard
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold">Dashboard</h1>

    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      <div class="border border-border rounded-lg p-4 bg-card">
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-muted-foreground">Active Processes</span>
          <GitBranch class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="text-2xl font-bold">{{ loading ? '—' : activeProcesses }}</div>
      </div>

      <div class="border border-border rounded-lg p-4 bg-card">
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-muted-foreground">Open Tasks</span>
          <ListTodo class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="text-2xl font-bold">{{ loading ? '—' : openTasks }}</div>
      </div>

      <div class="border border-border rounded-lg p-4 bg-card">
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-muted-foreground">Open Incidents</span>
          <AlertTriangle class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="text-2xl font-bold">{{ loading ? '—' : openIncidents }}</div>
      </div>

      <div class="border border-border rounded-lg p-4 bg-card">
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-muted-foreground">Completed Today</span>
          <CheckCircle class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="text-2xl font-bold">{{ loading ? '—' : completedToday }}</div>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
      <div class="border border-border rounded-lg p-6 bg-card">
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-lg font-bold">Process Definitions</h2>
          <button
            class="text-sm text-primary hover:underline"
            @click="router.push('/processes/definitions')"
          >
            View all
          </button>
        </div>
        <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>
        <div v-else-if="recentDefinitions.length" class="space-y-3">
          <div
            v-for="def in recentDefinitions"
            :key="def.id"
            class="flex items-center justify-between text-sm cursor-pointer hover:bg-muted/50 p-2 rounded"
            @click="router.push(`/processes/definitions/${def.id}`)"
          >
            <div class="flex items-center gap-3">
              <FileText class="h-4 w-4 text-muted-foreground" />
              <span class="font-medium">{{ def.name || def.key }}</span>
            </div>
            <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
              v{{ def.version }}
            </span>
          </div>
        </div>
        <div v-else class="text-sm text-muted-foreground">No definitions deployed yet.</div>
      </div>

      <div class="border border-border rounded-lg p-6 bg-card">
        <h2 class="text-lg font-bold mb-4">Quick Actions</h2>
        <div class="space-y-3">
          <button
            class="w-full flex items-center gap-3 px-4 py-3 border border-border rounded-lg hover:bg-muted/50 transition-colors text-left text-sm"
            @click="router.push('/processes/deploy')"
          >
            <FileText class="h-4 w-4 text-primary" />
            Deploy BPMN Process
          </button>
          <button
            class="w-full flex items-center gap-3 px-4 py-3 border border-border rounded-lg hover:bg-muted/50 transition-colors text-left text-sm"
            @click="router.push('/processes/definitions')"
          >
            <Play class="h-4 w-4 text-primary" />
            Start Process Instance
          </button>
          <button
            class="w-full flex items-center gap-3 px-4 py-3 border border-border rounded-lg hover:bg-muted/50 transition-colors text-left text-sm"
            @click="router.push('/tasks')"
          >
            <ListTodo class="h-4 w-4 text-primary" />
            View My Tasks
          </button>
          <button
            class="w-full flex items-center gap-3 px-4 py-3 border border-border rounded-lg hover:bg-muted/50 transition-colors text-left text-sm"
            @click="router.push('/incidents')"
          >
            <AlertTriangle class="h-4 w-4 text-primary" />
            View Incidents
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
