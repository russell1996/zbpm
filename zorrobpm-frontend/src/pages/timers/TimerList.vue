<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getTimerJobs } from '@/services/timerService'
import type { TimerJob } from '@/types/api'
import { Clock, CheckCircle, RefreshCw } from 'lucide-vue-next'
import { exportToCsv } from '@/shared/lib/export'
import { Download } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { useDateFormat } from '@/composables/useDateFormat'

const { t } = useI18n()
const toast = useToast()
const { formatDateTime } = useDateFormat()
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
    toast.error(t('loadError'))
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
    status: t.fired ? t('fired') : t('pending'),
    createdAt: t.createdAt,
  })), 'timers.csv')
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ t('timers') }}</h1>
      <div class="flex items-center gap-2">
        <button
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          :disabled="loading"
          @click="load"
        >
          <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': loading }" />
          {{ t('refresh') }}
        </button>
        <button
          v-if="timers.length"
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          @click="exportData"
        >
          <Download class="h-4 w-4" />
          {{ t('export') }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">{{ t('id') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('processInstance') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('dueAt') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="timer in timers" :key="timer.id" class="border-t border-border">
            <td class="px-4 py-3 font-mono text-xs">{{ timer.id.slice(0, 8) }}...</td>
            <td class="px-4 py-3 font-mono text-xs">{{ (timer.processInstanceId || timer.activityId || '—').slice(0, 8) }}{{ (timer.processInstanceId || timer.activityId) ? '...' : '' }}</td>
            <td class="px-4 py-3 text-sm">{{ formatDateTime(timer.dueAt) }}</td>
            <td class="px-4 py-3">
              <span :class="['inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium', timer.fired ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800']">
                <CheckCircle v-if="timer.fired" class="h-3 w-3" />
                <Clock v-else class="h-3 w-3" />
                {{ timer.fired ? t('fired') : t('pending') }}
              </span>
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(timer.createdAt) }}</td>
          </tr>
          <tr v-if="!timers.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">{{ t('noTimers') }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="total" class="text-sm text-muted-foreground">{{ total }} {{ t('total') }}</div>
  </div>
</template>
