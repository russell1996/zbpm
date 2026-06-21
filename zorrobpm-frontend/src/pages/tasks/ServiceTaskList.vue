<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useTaskStore } from '@/stores/task'
import { exportToCsv } from '@/shared/lib/export'
import { Download, RefreshCw } from 'lucide-vue-next'
import CopyableId from '@/widgets/shared/CopyableId.vue'

const router = useRouter()
const store = useTaskStore()
const { t } = useI18n()

const filterCompleted = ref(false)
const page = ref(0)
const pageSize = 10

async function load() {
  await store.fetchServiceTasks({
    pageIndex: page.value,
    pageSize,
    completed: filterCompleted.value,
  })
}

function nextPage() {
  if (store.serviceTasks && (page.value + 1) * pageSize < store.serviceTasks.totalElements) {
    page.value++
    load()
  }
}

function prevPage() {
  if (page.value > 0) {
    page.value--
    load()
  }
}

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
watch(filterCompleted, () => { page.value = 0; load() })
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">Service Tasks</h1>
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
            @click="viewDetail(task.id)"
          >
            <td class="px-4 py-3"><CopyableId :value="task.id" /></td>
            <td class="px-4 py-3">{{ task.name || task.code || '—' }}</td>
            <td class="px-4 py-3 font-mono text-xs">{{ task.job }}</td>
            <td class="px-4 py-3">
              <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', task.completedAt ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800']">
                {{ task.completedAt ? t('completed') : t('active') }}
              </span>
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ new Date(task.createdAt).toLocaleString() }}</td>
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
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="page === 0" @click="prevPage">{{ t('previous') }}</button>
        <span>{{ t('page') }} {{ page + 1 }}</span>
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="(page + 1) * pageSize >= store.serviceTasks.totalElements" @click="nextPage">{{ t('next') }}</button>
      </div>
    </div>
  </div>
</template>
