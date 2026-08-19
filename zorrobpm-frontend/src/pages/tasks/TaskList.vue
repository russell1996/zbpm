<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useTaskStore } from '@/stores/task'
import { usePagination } from '@/composables/usePagination'
import { useToast } from '@/composables/useToast'
import { exportToCsv } from '@/shared/lib/export'
import { Download, CheckSquare, RefreshCw } from 'lucide-vue-next'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const router = useRouter()
const store = useTaskStore()
const toast = useToast()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

const filterCompleted = ref(false)
const { page, pageSize, nextPage, prevPage, hasNext, hasPrev, resetPage } = usePagination(
  () => store.userTasks?.totalElements,
)
const selectedIds = ref<Set<string>>(new Set())
const bulkCompleting = ref(false)

async function load() {
  selectedIds.value.clear()
  await store.fetchUserTasks({
    pageIndex: page.value,
    pageSize,
    completed: filterCompleted.value,
  })
}

function goNextPage() { nextPage(); load() }
function goPrevPage() { prevPage(); load() }

function viewDetail(id: string) {
  router.push(`/tasks/${id}`)
}

const activeTasks = computed(() => (store.userTasks?.data || []).filter((t) => !t.completedAt))

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) {
    selectedIds.value.delete(id)
  } else {
    selectedIds.value.add(id)
  }
}

function toggleSelectAll() {
  if (selectedIds.value.size === activeTasks.value.length) {
    selectedIds.value.clear()
  } else {
    selectedIds.value = new Set(activeTasks.value.map((t) => t.id))
  }
}

async function bulkComplete() {
  if (selectedIds.value.size === 0) return
  bulkCompleting.value = true
  try {
    const ids = Array.from(selectedIds.value)
    let completed = 0
    let failed = 0
    for (const id of ids) {
      await store.completeUserTask(id, [])
      if (store.error) {
        failed++
        store.error = null
      } else {
        completed++
      }
    }
    if (failed === 0) {
      toast.success(`${completed} task(s) completed`)
    } else if (completed === 0) {
      toast.error(`${failed} task(s) failed`)
    } else {
      toast.info(`${completed} completed, ${failed} failed`)
    }
    selectedIds.value.clear()
    await load()
  } finally {
    bulkCompleting.value = false
  }
}

function exportData() {
  if (!store.userTasks?.data) return
  exportToCsv(store.userTasks.data.map((t) => ({
    id: t.id,
    name: t.name || t.code || '',
    processInstanceId: t.processInstanceId,
    status: t.completedAt ? 'Completed' : 'Active',
    createdAt: t.createdAt,
    completedAt: t.completedAt || '',
  })), 'user-tasks.csv')
}

onMounted(load)
watch(filterCompleted, () => { resetPage(); load() })
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ t('userTasks') }}</h1>
      <div class="flex items-center gap-2">
        <button
          v-if="selectedIds.size > 0"
          class="flex items-center gap-2 px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity disabled:opacity-50"
          :disabled="bulkCompleting"
          @click="bulkComplete"
        >
          <CheckSquare class="h-4 w-4" />
          {{ t('completeSelected') }} ({{ selectedIds.size }})
        </button>
        <button
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          :disabled="store.loading"
          @click="load"
        >
          <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': store.loading }" />
          {{ t('refresh') }}
        </button>
        <button
          v-if="store.userTasks?.data?.length"
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
            <th class="px-4 py-3 text-left font-medium w-10">
              <input
                type="checkbox"
                class="rounded"
                :checked="activeTasks.length > 0 && selectedIds.size === activeTasks.length"
                @change="toggleSelectAll"
              />
            </th>
            <th class="px-4 py-3 text-left font-medium">ID</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="task in (store.userTasks?.data || [])"
            :key="task.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            tabindex="0"
            @click="viewDetail(task.id)"
            @keydown.enter="viewDetail(task.id)"
          >
            <td class="px-4 py-3">
              <input
                v-if="!task.completedAt"
                type="checkbox"
                class="rounded"
                :checked="selectedIds.has(task.id)"
                @click.stop
                @change="toggleSelect(task.id)"
              />
            </td>
            <td class="px-4 py-3"><CopyableId :value="task.id" /></td>
            <td class="px-4 py-3">{{ task.name || task.code || '—' }}</td>
            <td class="px-4 py-3">
              <StatusBadge :status="task.status" :completed-at="task.completedAt" />
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(task.createdAt) }}</td>
          </tr>
          <tr v-if="!store.userTasks?.data?.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">{{ t('noTasks') }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="store.userTasks" class="flex items-center justify-between text-sm text-muted-foreground">
      <span>{{ store.userTasks.totalElements }} {{ t('total') }}</span>
      <div class="flex items-center gap-2">
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="!hasPrev" @click="goPrevPage">{{ t('previous') }}</button>
        <span>{{ t('page') }} {{ page + 1 }}</span>
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="!hasNext" @click="goNextPage">{{ t('next') }}</button>
      </div>
    </div>
  </div>
</template>
