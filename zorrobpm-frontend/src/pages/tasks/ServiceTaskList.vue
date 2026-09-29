<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useTaskStore } from '@/stores/task'
import { usePagination } from '@/composables/usePagination'
import { exportToCsv } from '@/shared/lib/export'
import { debounce } from '@/shared/lib/debounce'
import { Download, RefreshCw } from 'lucide-vue-next'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const router = useRouter()
const store = useTaskStore()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

const filterCompleted = ref(false)
const { page, pageSize, nextPage, prevPage, hasNext, hasPrev, resetPage } = usePagination(
  () => store.serviceTasks?.totalElements,
)

async function load() {
  await store.fetchServiceTasks({
    pageIndex: page.value,
    pageSize,
    completed: filterCompleted.value,
  })
}

function goNextPage() { nextPage(); load() }
function goPrevPage() { prevPage(); load() }

function viewDetail(id: string) {
  router.push(`/service-tasks/${id}`)
}

function exportData() {
  if (!store.serviceTasks?.data) return
  exportToCsv(store.serviceTasks.data.map((t) => ({
    id: t.id,
    name: t.name || t.code || '',
    job: t.job,
    processInstanceId: t.processInstanceId,
    status: t.completedAt ? 'Completed' : 'Active',
    createdAt: t.createdAt,
    completedAt: t.completedAt || '',
  })), 'service-tasks.csv')
}

onMounted(load)
// WO-UI-18 часть B: debounce (тот же паттерн, что TaskList).
watch(filterCompleted, debounce(() => { resetPage(); load() }))
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ t('serviceTasks') }}</h1>
      <div class="flex items-center gap-2">
        <button
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          :disabled="store.loading"
          @click="load"
        >
          <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': store.loading }" />
          {{ t('refresh') }}
        </button>
        <button
          v-if="store.serviceTasks?.data?.length"
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          @click="exportData"
        >
          <Download class="h-4 w-4" />
          {{ t('export') }}
        </button>
      </div>
    </div>

    <div class="flex items-center gap-4">
      <label class="flex items-center gap-2 text-sm">
        <input v-model="filterCompleted" type="checkbox" class="rounded" />
        {{ t('showCompleted') }}
      </label>
    </div>

    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">ID</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('jobType') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="task in (store.serviceTasks?.data || [])"
            :key="task.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            tabindex="0"
            @click="viewDetail(task.id)"
            @keydown.enter="viewDetail(task.id)"
          >
            <td class="px-4 py-3"><CopyableId :value="task.id" /></td>
            <td class="px-4 py-3">{{ task.name || task.code || '—' }}</td>
            <td class="px-4 py-3 font-mono text-xs">{{ task.job }}</td>
            <td class="px-4 py-3">
              <StatusBadge :status="task.status" :completed-at="task.completedAt" />
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(task.createdAt) }}</td>
          </tr>
          <tr v-if="!store.serviceTasks?.data?.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">{{ t('noServiceTasks') }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="store.serviceTasks" class="flex items-center justify-between text-sm text-muted-foreground">
      <span>{{ store.serviceTasks.totalElements }} {{ t('total') }}</span>
      <div class="flex items-center gap-2">
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="!hasPrev" @click="goPrevPage">{{ t('previous') }}</button>
        <span>{{ t('page') }} {{ page + 1 }}</span>
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="!hasNext" @click="goNextPage">{{ t('next') }}</button>
      </div>
    </div>
  </div>
</template>
