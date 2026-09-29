<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useIncidentStore } from '@/stores/incident'
import { usePagination } from '@/composables/usePagination'
import { exportToCsv } from '@/shared/lib/export'
import { debounce } from '@/shared/lib/debounce'
import { Download, RefreshCw } from 'lucide-vue-next'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const router = useRouter()
const store = useIncidentStore()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

// unchecked -> only open incidents (server-side resolved=false); checked -> all
const showResolved = ref(false)
const { page, pageSize, nextPage, prevPage, hasNext, hasPrev, resetPage } = usePagination(
  () => store.incidents?.totalElements,
)

async function load() {
  await store.fetchIncidents({
    pageIndex: page.value,
    pageSize,
    resolved: showResolved.value ? undefined : false,
  })
}

function goNextPage() { nextPage(); load() }
function goPrevPage() { prevPage(); load() }

function viewDetail(id: string) {
  router.push(`/incidents/${id}`)
}

onMounted(load)
// WO-UI-18 часть B: debounce (тот же паттерн, что TaskList).
watch(showResolved, debounce(() => { resetPage(); load() }))

function exportData() {
  if (!store.incidents?.data) return
  exportToCsv(store.incidents.data.map((i) => ({
    id: i.id,
    activityId: i.activityId,
    message: i.message,
    status: i.completedAt ? 'Resolved' : 'Open',
    createdAt: i.createdAt,
    completedAt: i.completedAt || '',
  })), 'incidents.csv')
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ t('incidents') }}</h1>
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
          v-if="store.incidents?.data?.length"
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
        <input v-model="showResolved" type="checkbox" class="rounded" />
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
            <th class="px-4 py-3 text-left font-medium">{{ t('message') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('process') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('element') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('completedAt') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="inc in (store.incidents?.data || [])"
            :key="inc.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            tabindex="0"
            @click="viewDetail(inc.id)"
            @keydown.enter="viewDetail(inc.id)"
          >
            <td class="px-4 py-3"><CopyableId :value="inc.id" /></td>
            <td class="px-4 py-3 text-sm max-w-xs truncate" :title="inc.message">{{ inc.message }}</td>
            <td class="px-4 py-3">{{ inc.processName || '—' }}</td>
            <td class="px-4 py-3">{{ inc.elementName || inc.bpmnElementId || '—' }}</td>
            <td class="px-4 py-3">
              <StatusBadge :status="inc.completedAt ? 'RESOLVED' : 'OPEN'" />
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(inc.createdAt) }}</td>
            <td class="px-4 py-3 text-muted-foreground">{{ inc.completedAt ? formatDateTime(inc.completedAt) : '—' }}</td>
          </tr>
          <tr v-if="!store.incidents?.data?.length">
            <td colspan="6" class="px-4 py-8 text-center text-muted-foreground">{{ t('noIncidents') }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="store.incidents" class="flex items-center justify-between text-sm text-muted-foreground">
      <span>{{ store.incidents.totalElements }} {{ t('total') }}</span>
      <div class="flex items-center gap-2">
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="!hasPrev" @click="goPrevPage">{{ t('previous') }}</button>
        <span>{{ t('page') }} {{ page + 1 }}</span>
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="!hasNext" @click="goNextPage">{{ t('next') }}</button>
      </div>
    </div>
  </div>
</template>
