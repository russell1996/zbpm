<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useProcessStore } from '@/stores/process'
import { usePagination } from '@/composables/usePagination'
import { exportToCsv } from '@/shared/lib/export'
import { Download, RefreshCw } from 'lucide-vue-next'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const router = useRouter()
const store = useProcessStore()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

const filterKey = ref('')
const { page, pageSize, nextPage, prevPage, hasNext, hasPrev, resetPage } = usePagination(
  () => store.instances?.totalElements,
)

async function load() {
  await store.fetchInstances({
    pageIndex: page.value,
    pageSize,
    processDefinitionKey: filterKey.value || undefined,
  })
}

function goNextPage() { nextPage(); load() }
function goPrevPage() { prevPage(); load() }

function viewDetail(id: string) {
  router.push(`/processes/instances/${id}`)
}

function exportData() {
  if (!store.instances?.data) return
  exportToCsv(store.instances.data.map((i) => ({
    id: i.id,
    status: i.completedAt ? 'Completed' : 'Running',
    startedAt: i.startedAt,
    completedAt: i.completedAt || '',
  })), 'process-instances.csv')
}

onMounted(load)
watch(filterKey, () => { resetPage(); load() })
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ t('instances') }}</h1>
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
          v-if="store.instances?.data?.length"
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          @click="exportData"
        >
          <Download class="h-4 w-4" />
          {{ t('export') }}
        </button>
      </div>
    </div>

    <div class="flex items-center gap-4">
      <input
        v-model="filterKey"
        type="text"
        :placeholder="t('filterByKey')"
        class="px-3 py-2 border border-input rounded-md text-sm w-72 focus:outline-none focus:ring-2 focus:ring-ring"
      />
    </div>

    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">ID</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('process') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('started') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('completed') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="pi in (store.instances?.data || [])"
            :key="pi.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            tabindex="0"
            @click="viewDetail(pi.id)"
            @keydown.enter="viewDetail(pi.id)"
          >
            <td class="px-4 py-3"><CopyableId :value="pi.id" /></td>
            <td class="px-4 py-3">
              <span>{{ pi.processName || pi.processKey || '—' }}</span>
              <span v-if="pi.processVersion" class="ml-1 text-xs text-muted-foreground">v{{ pi.processVersion }}</span>
            </td>
            <td class="px-4 py-3">
              <StatusBadge :status="pi.completedAt ? 'COMPLETED' : 'RUNNING'" />
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(pi.startedAt) }}</td>
            <td class="px-4 py-3 text-muted-foreground">{{ pi.completedAt ? formatDateTime(pi.completedAt) : '—' }}</td>
          </tr>
          <tr v-if="!store.instances?.data?.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">{{ t('noInstances') }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="store.instances" class="flex items-center justify-between text-sm text-muted-foreground">
      <span>{{ store.instances.totalElements }} {{ t('total') }}</span>
      <div class="flex items-center gap-2">
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="!hasPrev" @click="goPrevPage">{{ t('previous') }}</button>
        <span>{{ t('page') }} {{ page + 1 }}</span>
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="!hasNext" @click="goNextPage">{{ t('next') }}</button>
      </div>
    </div>
  </div>
</template>
