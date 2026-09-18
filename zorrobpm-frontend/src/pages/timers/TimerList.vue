<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getTimerJobs } from '@/services/timerService'
import type { TimerJob } from '@/types/api'
import { RefreshCw } from 'lucide-vue-next'
import { exportToCsv } from '@/shared/lib/export'
import { Download } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useToast } from '@/composables/useToast'
import { useDateFormat } from '@/composables/useDateFormat'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const { t } = useI18n()
const toast = useToast()
const { formatDateTime } = useDateFormat()
const timers = ref<TimerJob[]>([])
const total = ref(0)
const loading = ref(false)
const router = useRouter()

/** WO-ACL-11 criterion 6/10: a timer row navigates to its process instance —
 * there is no timer detail page. The full id is always visible/copyable via
 * CopyableId, so the truncated `4394c7b1…` text is gone. */
function openInstance(timer: TimerJob) {
  if (timer.processInstanceId) router.push(`/processes/instances/${timer.processInstanceId}`)
}

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
  // WO-UI-17 F24: the callback param MUST NOT be named `t` — it shadows the
  // i18n `t` from useI18n, so `t('fired')` called the timer row as a function
  // (runtime TypeError on every Export click, not just a type error).
  exportToCsv(timers.value.map((timer) => ({
    id: timer.id,
    processInstanceId: timer.processInstanceId || '',
    activityId: timer.activityId || '',
    dueAt: timer.dueAt,
    status: timer.fired ? t('fired') : t('pending'),
    createdAt: timer.createdAt,
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
          <tr
            v-for="timer in timers"
            :key="timer.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            :tabindex="timer.processInstanceId ? 0 : -1"
            @click="openInstance(timer)"
            @keydown.enter="openInstance(timer)"
          >
            <td class="px-4 py-3"><CopyableId :value="timer.id" /></td>
            <td class="px-4 py-3">
              <CopyableId v-if="timer.processInstanceId" :value="timer.processInstanceId" />
              <span v-else-if="timer.activityId" class="font-mono text-xs"><CopyableId :value="timer.activityId" /></span>
              <span v-else class="text-muted-foreground">—</span>
            </td>
            <td class="px-4 py-3 text-sm">{{ formatDateTime(timer.dueAt) }}</td>
            <td class="px-4 py-3">
              <!-- WO-ACL-14 criteria 6-8: the icon lives INSIDE StatusBadge (with-icon),
                   decided by the status — no more loose CheckCircle/Clock next to the pill. -->
              <StatusBadge :status="timer.fired ? 'FIRED' : 'WAITING'" with-icon />
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
