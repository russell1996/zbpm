<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getProcessInstances } from '@/services/instanceService'
import { getUserTasks } from '@/services/taskService'
import { getIncidents } from '@/services/incidentService'

const loading = ref(true)

const totalInstances = ref(0)
const completedInstances = ref(0)
const runningInstances = ref(0)
const totalTasks = ref(0)
const completedTasks = ref(0)
const totalIncidents = ref(0)
const resolvedIncidents = ref(0)

const completionRate = ref(0)
const avgDuration = ref('—')
const incidentRate = ref(0)

onMounted(async () => {
  try {
    const [instances, tasks, incidents] = await Promise.all([
      getProcessInstances({ pageSize: 100 }),
      getUserTasks({ pageSize: 100 }),
      getIncidents({ pageSize: 100 }),
    ])

    totalInstances.value = instances.totalElements
    completedInstances.value = instances.data.filter((i) => i.completedAt).length
    runningInstances.value = totalInstances.value - completedInstances.value

    totalTasks.value = tasks.totalElements
    completedTasks.value = tasks.data.filter((t) => t.completedAt).length

    totalIncidents.value = incidents.totalElements
    resolvedIncidents.value = incidents.data.filter((i) => i.completedAt).length

    completionRate.value = totalInstances.value > 0
      ? Math.round((completedInstances.value / totalInstances.value) * 100)
      : 0
    incidentRate.value = totalInstances.value > 0
      ? Math.round((totalIncidents.value / totalInstances.value) * 100)
      : 0

    // Calculate average duration from completed instances
    const durations = instances.data
      .filter((i) => i.completedAt)
      .map((i) => new Date(i.completedAt!).getTime() - new Date(i.startedAt).getTime())
    if (durations.length > 0) {
      const avg = durations.reduce((a, b) => a + b, 0) / durations.length
      const mins = Math.round(avg / 60000)
      avgDuration.value = mins < 60 ? `${mins}m` : `${Math.round(mins / 60)}h ${mins % 60}m`
    }
  } catch {
    // API unavailable — show empty analytics
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold">Analytics</h1>

    <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>

    <template v-else>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div class="border border-border rounded-lg p-4 bg-card">
          <h3 class="text-sm font-medium text-muted-foreground mb-1">Completion Rate</h3>
          <div class="text-3xl font-bold">{{ completionRate }}%</div>
          <p class="text-xs text-muted-foreground mt-1">{{ completedInstances }} of {{ totalInstances }} completed</p>
        </div>
        <div class="border border-border rounded-lg p-4 bg-card">
          <h3 class="text-sm font-medium text-muted-foreground mb-1">Average Duration</h3>
          <div class="text-3xl font-bold">{{ avgDuration }}</div>
          <p class="text-xs text-muted-foreground mt-1">Across completed instances</p>
        </div>
        <div class="border border-border rounded-lg p-4 bg-card">
          <h3 class="text-sm font-medium text-muted-foreground mb-1">Incident Rate</h3>
          <div class="text-3xl font-bold">{{ incidentRate }}%</div>
          <p class="text-xs text-muted-foreground mt-1">{{ totalIncidents }} incidents / {{ totalInstances }} instances</p>
        </div>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div class="border border-border rounded-lg p-6 bg-card">
          <h2 class="text-lg font-bold mb-4">Process Instances</h2>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm">Running</span>
              <div class="flex items-center gap-2">
                <div class="w-32 h-2 bg-muted rounded-full overflow-hidden">
                  <div class="h-full bg-blue-500 rounded-full" :style="{ width: `${totalInstances ? (runningInstances / totalInstances) * 100 : 0}%` }" />
                </div>
                <span class="text-sm font-medium w-8 text-right">{{ runningInstances }}</span>
              </div>
            </div>
            <div class="flex items-center justify-between">
              <span class="text-sm">Completed</span>
              <div class="flex items-center gap-2">
                <div class="w-32 h-2 bg-muted rounded-full overflow-hidden">
                  <div class="h-full bg-green-500 rounded-full" :style="{ width: `${totalInstances ? (completedInstances / totalInstances) * 100 : 0}%` }" />
                </div>
                <span class="text-sm font-medium w-8 text-right">{{ completedInstances }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="border border-border rounded-lg p-6 bg-card">
          <h2 class="text-lg font-bold mb-4">Tasks</h2>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm">Active</span>
              <div class="flex items-center gap-2">
                <div class="w-32 h-2 bg-muted rounded-full overflow-hidden">
                  <div class="h-full bg-yellow-500 rounded-full" :style="{ width: `${totalTasks ? ((totalTasks - completedTasks) / totalTasks) * 100 : 0}%` }" />
                </div>
                <span class="text-sm font-medium w-8 text-right">{{ totalTasks - completedTasks }}</span>
              </div>
            </div>
            <div class="flex items-center justify-between">
              <span class="text-sm">Completed</span>
              <div class="flex items-center gap-2">
                <div class="w-32 h-2 bg-muted rounded-full overflow-hidden">
                  <div class="h-full bg-green-500 rounded-full" :style="{ width: `${totalTasks ? (completedTasks / totalTasks) * 100 : 0}%` }" />
                </div>
                <span class="text-sm font-medium w-8 text-right">{{ completedTasks }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="border border-border rounded-lg p-6 bg-card">
          <h2 class="text-lg font-bold mb-4">Incidents</h2>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-sm">Open</span>
              <div class="flex items-center gap-2">
                <div class="w-32 h-2 bg-muted rounded-full overflow-hidden">
                  <div class="h-full bg-red-500 rounded-full" :style="{ width: `${totalIncidents ? ((totalIncidents - resolvedIncidents) / totalIncidents) * 100 : 0}%` }" />
                </div>
                <span class="text-sm font-medium w-8 text-right">{{ totalIncidents - resolvedIncidents }}</span>
              </div>
            </div>
            <div class="flex items-center justify-between">
              <span class="text-sm">Resolved</span>
              <div class="flex items-center gap-2">
                <div class="w-32 h-2 bg-muted rounded-full overflow-hidden">
                  <div class="h-full bg-green-500 rounded-full" :style="{ width: `${totalIncidents ? (resolvedIncidents / totalIncidents) * 100 : 0}%` }" />
                </div>
                <span class="text-sm font-medium w-8 text-right">{{ resolvedIncidents }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="border border-border rounded-lg p-6 bg-card">
          <h2 class="text-lg font-bold mb-4">Summary</h2>
          <div class="space-y-2 text-sm">
            <div class="flex justify-between">
              <span class="text-muted-foreground">Total Process Definitions</span>
              <span class="font-medium">—</span>
            </div>
            <div class="flex justify-between">
              <span class="text-muted-foreground">Total Process Instances</span>
              <span class="font-medium">{{ totalInstances }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-muted-foreground">Total Tasks</span>
              <span class="font-medium">{{ totalTasks }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-muted-foreground">Total Incidents</span>
              <span class="font-medium">{{ totalIncidents }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
