<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getTimerJobs } from '@/services/timerService'
import type { TimerJob } from '@/types/api'
import { Clock, CheckCircle, RefreshCw } from 'lucide-vue-next'
import { exportToCsv } from '@/shared/lib/export'
import { Download } from 'lucide-vue-next'

const timers = ref<TimerJob[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await getTimerJobs({ pageIndex: 0, pageSize: 100 })
    timers.value = result.data
    total.value = result.totalElements
  } catch {
    // ignore
  } finally {
    loading.value = false
  }
}

onMounted(load)

function exportData() {
  exportToCsv(timers.value.map((t) => ({
    id: t.id,
    processInstanceId: t.processInstanceId || '',
    activityId: t.activityId || '',
    dueAt: t.dueAt,
    status: t.fired ? 'Fired' : 'Pending',
    createdAt: t.createdAt,
  })), 'timers.csv')
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">Timers</h1>
      <div class="flex items-center gap-2">
        <button
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          :disabled="loading"
          @click="load"
        >
          <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': loading }" />
          Refresh
        </button>
        <button
          v-if="timers.length"
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          @click="exportData"
        >
          <Download class="h-4 w-4" />
          Export CSV
        </button>
      </div>
    </div>

    <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">ID</th>
            <th class="px-4 py-3 text-left font-medium">Process Instance</th>
            <th class="px-4 py-3 text-left font-medium">Due At</th>
            <th class="px-4 py-3 text-left font-medium">Status</th>
            <th class="px-4 py-3 text-left font-medium">Created</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="timer in timers" :key="timer.id" class="border-t border-border">
            <td class="px-4 py-3 font-mono text-xs">{{ timer.id.slice(0, 8) }}...</td>
            <td class="px-4 py-3 font-mono text-xs">{{ (timer.processInstanceId || timer.activityId || '—').slice(0, 8) }}{{ (timer.processInstanceId || timer.activityId) ? '...' : '' }}</td>
            <td class="px-4 py-3 text-sm">{{ new Date(timer.dueAt).toLocaleString() }}</td>
            <td class="px-4 py-3">
              <span :class="['inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium', timer.fired ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800']">
                <CheckCircle v-if="timer.fired" class="h-3 w-3" />
                <Clock v-else class="h-3 w-3" />
                {{ timer.fired ? 'Fired' : 'Pending' }}
              </span>
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ new Date(timer.createdAt).toLocaleString() }}</td>
          </tr>
          <tr v-if="!timers.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">No timers found</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="total" class="text-sm text-muted-foreground">{{ total }} total</div>
  </div>
</template>
