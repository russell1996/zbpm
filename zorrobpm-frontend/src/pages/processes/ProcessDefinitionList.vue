<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useProcessStore } from '@/stores/process'
import { exportToCsv } from '@/shared/lib/export'
import { Download, RefreshCw } from 'lucide-vue-next'

const router = useRouter()
const store = useProcessStore()
const { t } = useI18n()

const search = ref('')
const latestOnly = ref(true)
const page = ref(0)
const pageSize = 10

async function load() {
  await store.fetchDefinitions({
    pageIndex: page.value,
    pageSize,
    name: search.value || undefined,
    latestVersionOnly: latestOnly.value || undefined,
  })
}

function nextPage() {
  if (store.definitions && (page.value + 1) * pageSize < store.definitions.totalElements) {
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
  router.push(`/processes/definitions/${id}`)
}

function exportData() {
  if (!store.definitions?.data) return
  exportToCsv(store.definitions.data.map((d) => ({
    id: d.id,
    name: d.name || '',
    key: d.key,
    version: d.version,
    createdAt: d.createdAt,
  })), 'process-definitions.csv')
}

onMounted(load)
watch([search, latestOnly], () => { page.value = 0; load() })
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">Process Definitions</h1>
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
          v-if="store.definitions?.data?.length"
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
        v-model="search"
        type="text"
        :placeholder="t('searchPlaceholder')"
        class="px-3 py-2 border border-input rounded-md text-sm w-64 focus:outline-none focus:ring-2 focus:ring-ring"
      />
      <label class="flex items-center gap-2 text-sm">
        <input v-model="latestOnly" type="checkbox" class="rounded" />
        {{ t('latestOnly') }}
      </label>
    </div>

    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('key') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('version') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
            <th class="px-4 py-3 text-left font-medium"></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="def in (store.definitions?.data || [])"
            :key="def.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            @click="viewDetail(def.id)"
          >
            <td class="px-4 py-3 font-medium">{{ def.name || '—' }}</td>
            <td class="px-4 py-3 font-mono text-xs">{{ def.key }}</td>
            <td class="px-4 py-3">
              <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
                v{{ def.version }}
              </span>
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ new Date(def.createdAt).toLocaleDateString() }}</td>
            <td class="px-4 py-3">
              <button class="text-sm text-primary hover:underline" @click.stop="viewDetail(def.id)">
                View
              </button>
            </td>
          </tr>
          <tr v-if="!store.definitions?.data?.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">{{ t('noDefinitions') }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="store.definitions" class="flex items-center justify-between text-sm text-muted-foreground">
      <span>{{ store.definitions.totalElements }} {{ t('total') }}</span>
      <div class="flex items-center gap-2">
        <button
          class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50"
          :disabled="page === 0"
          @click="prevPage"
        >
          {{ t('previous') }}
        </button>
        <span>{{ t('page') }} {{ page + 1 }}</span>
        <button
          class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50"
          :disabled="(page + 1) * pageSize >= store.definitions.totalElements"
          @click="nextPage"
        >
          {{ t('next') }}
        </button>
      </div>
    </div>
  </div>
</template>
