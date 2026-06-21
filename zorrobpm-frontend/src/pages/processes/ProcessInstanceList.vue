<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useProcessStore } from '@/stores/process'
import { exportToCsv } from '@/shared/lib/export'
import { Download } from 'lucide-vue-next'

const router = useRouter()
const store = useProcessStore()

function exportData() {
  if (!store.instances?.data) return
  exportToCsv(store.instances.data.map((i) => ({
    id: i.id,
    processDefinitionId: i.processDefinitionId,
    status: i.completedAt ? 'Completed' : 'Running',
    startedAt: i.startedAt,
    completedAt: i.completedAt || '',
  })), 'process-instances.csv')
}

const filterDefId = ref('')
const page = ref(0)
const pageSize = 10

async function load() {
  await store.fetchInstances({
    pageIndex: page.value,
    pageSize,
    processDefinitionId: filterDefId.value || undefined,
  })
}

function nextPage() {
  if (store.instances && (page.value + 1) * pageSize < store.instances.totalElements) {
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
  router.push(`/processes/instances/${id}`)
}

function status(pi: { completedAt: string | null }) {
  return pi.completedAt ? 'Completed' : 'Running'
}

function statusClass(pi: { completedAt: string | null }) {
  return pi.completedAt
    ? 'bg-green-100 text-green-800'
    : 'bg-blue-100 text-blue-800'
}

onMounted(load)
watch(filterDefId, () => { page.value = 0; load() })
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">Process Instances</h1>
      <button
        v-if="store.instances?.data?.length"
        class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
        @click="exportData"
      >
        <Download class="h-4 w-4" />
        Export CSV
      </button>
    </div>

    <div class="flex items-center gap-4">
      <input
        v-model="filterDefId"
        type="text"
        placeholder="Filter by definition ID..."
        class="px-3 py-2 border border-input rounded-md text-sm w-72 focus:outline-none focus:ring-2 focus:ring-ring"
      />
    </div>

    <div v-if="store.loading" class="text-sm text-muted-foreground">Loading...</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">ID</th>
            <th class="px-4 py-3 text-left font-medium">Definition</th>
            <th class="px-4 py-3 text-left font-medium">Status</th>
            <th class="px-4 py-3 text-left font-medium">Started</th>
            <th class="px-4 py-3 text-left font-medium">Completed</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="pi in (store.instances?.data || [])"
            :key="pi.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            @click="viewDetail(pi.id)"
          >
            <td class="px-4 py-3 font-mono text-xs">{{ pi.id.slice(0, 8) }}...</td>
            <td class="px-4 py-3 font-mono text-xs">{{ pi.processDefinitionId.slice(0, 8) }}...</td>
            <td class="px-4 py-3">
              <span :class="['inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', statusClass(pi)]">
                {{ status(pi) }}
              </span>
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ new Date(pi.startedAt).toLocaleString() }}</td>
            <td class="px-4 py-3 text-muted-foreground">{{ pi.completedAt ? new Date(pi.completedAt).toLocaleString() : '—' }}</td>
          </tr>
          <tr v-if="!store.instances?.data?.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">No instances found</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="store.instances" class="flex items-center justify-between text-sm text-muted-foreground">
      <span>{{ store.instances.totalElements }} total</span>
      <div class="flex items-center gap-2">
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="page === 0" @click="prevPage">Previous</button>
        <span>Page {{ page + 1 }}</span>
        <button class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50" :disabled="(page + 1) * pageSize >= store.instances.totalElements" @click="nextPage">Next</button>
      </div>
    </div>
  </div>
</template>
