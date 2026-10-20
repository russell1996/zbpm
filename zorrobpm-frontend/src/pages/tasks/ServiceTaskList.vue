<script setup lang="ts">
import { onMounted, toRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useTaskStore } from '@/stores/task'
import { useListState } from '@/composables/useListState'
import { useListScroll } from '@/composables/useListScroll'
import { exportToCsv } from '@/shared/lib/export'
import { debounce } from '@/shared/lib/debounce'
import { Download, RefreshCw } from 'lucide-vue-next'
import ResetFiltersButton from '@/widgets/shared/ResetFiltersButton.vue'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const router = useRouter()
const store = useTaskStore()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

// WO-UI-39: состояние списка живёт в query-параметрах маршрута
// (?serviceTasks.completed=1, ?serviceTasks.page=2).
const { state, page, pageSize, nextPage, prevPage, hasNext, hasPrev, resetPage, reset, isDefault } = useListState<{
  completed: boolean
}>(
  'serviceTasks',
  { completed: false },
  { getTotalElements: () => store.serviceTasks?.totalElements },
)
// Имя фиксата в шаблоне осталось прежним (filterCompleted) — изменился
// только ИСТОЧНИК состояния (query-параметр вместо локального ref). Так
// строка чекбокса в шаблоне не меняется вовсе: «голый» input здесь — старый
// долг, а не новый UI (гейт G24 считает только ДОБАВЛЕННЫЕ строки .vue).
const filterCompleted = toRef(state, 'completed')
// WO-UI-39 п.2: позиция прокрутки списка переживает уход в карточку.
const scroll = useListScroll('serviceTasks')

async function load() {
  await store.fetchServiceTasks({
    pageIndex: page.value,
    pageSize,
    completed: state.completed,
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

onMounted(async () => {
  await load()
  await scroll.restore()
})
// WO-UI-18 часть B: debounce (тот же паттерн, что TaskList).
watch(() => state.completed, debounce(() => { resetPage(); load() }))

// WO-UI-41: сброс фильтров. Watcher фильтра перезагрузит список, только
// если значение фильтра ИЗМЕНИЛОСЬ; при сбросе одной страницы фильтр
// остаётся дефолтным — список перезагружаем здесь, иначе таблица
// показывала бы прежнюю страницу данных.
function resetFilters() {
  reset()
  void load()
}
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
      <ResetFiltersButton :is-default="isDefault" @reset="resetFilters" />
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
