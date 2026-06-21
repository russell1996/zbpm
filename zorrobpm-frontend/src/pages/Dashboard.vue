<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { GitBranch, ListTodo, AlertTriangle, CheckCircle, FileText, Play, RefreshCw, Cpu } from 'lucide-vue-next'
import { getDashboard } from '@/services/dashboardService'
import type { DashboardData } from '@/types/api'

const router = useRouter()

const dashboard = ref<DashboardData | null>(null)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    dashboard.value = await getDashboard()
  } catch {
    // API unavailable
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">Dashboard</h1>
      <button
        class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
        :disabled="loading"
        @click="load"
      >
        <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': loading }" />
        Refresh
      </button>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-4">
      <div
        class="border border-border rounded-lg p-4 bg-card cursor-pointer hover:bg-muted/50 transition-colors"
        @click="router.push('/processes/instances')"
      >
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-muted-foreground">Active Processes</span>
          <GitBranch class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="text-2xl font-bold">{{ loading ? '—' : dashboard?.activeProcessInstances ?? 0 }}</div>
      </div>

      <div
        class="border border-border rounded-lg p-4 bg-card cursor-pointer hover:bg-muted/50 transition-colors"
        @click="router.push('/tasks')"
      >
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-muted-foreground">Open Tasks</span>
          <ListTodo class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="text-2xl font-bold">{{ loading ? '—' : dashboard?.openUserTasks ?? 0 }}</div>
      </div>

      <div
        class="border border-border rounded-lg p-4 bg-card cursor-pointer hover:bg-muted/50 transition-colors"
        @click="router.push('/service-tasks')"
      >
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-muted-foreground">Service Tasks</span>
          <Cpu class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="text-2xl font-bold">{{ loading ? '—' : dashboard?.openServiceTasks ?? 0 }}</div>
      </div>

      <div
        class="border border-border rounded-lg p-4 bg-card cursor-pointer hover:bg-muted/50 transition-colors"
        @click="router.push('/incidents')"
      >
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-muted-foreground">Open Incidents</span>
          <AlertTriangle class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="text-2xl font-bold">{{ loading ? '—' : dashboard?.openIncidents ?? 0 }}</div>
      </div>

      <div class="border border-border rounded-lg p-4 bg-card">
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm font-medium text-muted-foreground">Completed Today</span>
          <CheckCircle class="h-4 w-4 text-muted-foreground" />
        </div>
        <div class="text-2xl font-bold">{{ loading ? '—' : dashboard?.completedToday ?? 0 }}</div>
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
        <div v-else-if="dashboard?.recentDefinitions?.length" class="space-y-3">
          <div
            v-for="def in dashboard.recentDefinitions"
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
