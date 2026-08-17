<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useProcessStore } from '@/stores/process'
import { usePagination } from '@/composables/usePagination'
import { getMyMemberships } from '@/services/adminService'
import { exportToCsv } from '@/shared/lib/export'
import ProcessDeploySection from '@/widgets/processes/ProcessDeploySection.vue'
import MySubmissions from '@/pages/processes/MySubmissions.vue'
import { Download, RefreshCw, FileText } from 'lucide-vue-next'

const router = useRouter()
const store = useProcessStore()
const { t } = useI18n()
const { formatDate } = useDateFormat()

const search = ref('')
const latestOnly = ref(true)
const { page, pageSize, nextPage, prevPage, hasNext, hasPrev, resetPage } = usePagination(
  () => store.definitions?.totalElements,
)

// WO-ACL-8 criterion 8: "my processes" filter — narrows the list to processes the
// caller is a member of, via GET /me/memberships (WO-ACL-7). Client-side filtering
// since the backend has no membership filter parameter and the contract is out of scope (G-C).
const myOnly = ref(false)
const myProcessKeys = ref<Set<string>>(new Set())
const myMembershipsLoading = ref(false)

const visibleDefinitions = computed(() => {
  const data = store.definitions?.data || []
  if (!myOnly.value) return data
  return data.filter((d) => myProcessKeys.value.has(d.key))
})

async function load() {
  await store.fetchDefinitions({
    pageIndex: page.value,
    pageSize,
    name: search.value || undefined,
    latestVersionOnly: latestOnly.value || undefined,
  })
}

async function toggleMyOnly() {
  myProcessKeys.value = new Set()
  if (myOnly.value) {
    myMembershipsLoading.value = true
    try {
      const memberships = await getMyMemberships()
      myProcessKeys.value = new Set(memberships.map((m) => m.processKey).filter((k): k is string => !!k))
    } finally {
      myMembershipsLoading.value = false
    }
  }
  resetPage()
  load()
}

function goNextPage() { nextPage(); load() }
function goPrevPage() { prevPage(); load() }

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

// WO-ACL-8 criterion 10: My Submissions as a dialog from the definitions page.
const showSubmissions = ref(false)

onMounted(load)
watch([search, latestOnly], () => { resetPage(); load() })
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
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          @click="showSubmissions = true"
        >
          <FileText class="h-4 w-4" />
          {{ t('mySubmissions') }}
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

    <!-- WO-ACL-6: upload lives INSIDE the definitions list — the old standalone
         /processes/deploy page and its sidebar entry are gone. -->
    <ProcessDeploySection />

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
      <!-- WO-ACL-8 criterion 8: "my processes" — empty result is a normal state. -->
      <label class="flex items-center gap-2 text-sm">
        <input v-model="myOnly" type="checkbox" class="rounded" @change="toggleMyOnly" />
        {{ t('myProcessesOnly') }}
      </label>
      <span v-if="myMembershipsLoading" class="text-xs text-muted-foreground">{{ t('loading') }}</span>
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
            v-for="def in visibleDefinitions"
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
            <td class="px-4 py-3 text-muted-foreground">{{ formatDate(def.createdAt) }}</td>
            <td class="px-4 py-3">
              <button class="text-sm text-primary hover:underline" @click.stop="viewDetail(def.id)">
                View
              </button>
            </td>
          </tr>
          <tr v-if="!visibleDefinitions.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">
              {{ myOnly ? t('noMyProcesses') : t('noDefinitions') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="store.definitions" class="flex items-center justify-between text-sm text-muted-foreground">
      <span>{{ store.definitions.totalElements }} {{ t('total') }}</span>
      <div class="flex items-center gap-2">
        <button
          class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50"
          :disabled="!hasPrev"
          @click="goPrevPage"
        >
          {{ t('previous') }}
        </button>
        <span>{{ t('page') }} {{ page + 1 }}</span>
        <button
          class="px-3 py-1 border border-border rounded hover:bg-muted disabled:opacity-50"
          :disabled="!hasNext"
          @click="goNextPage"
        >
          {{ t('next') }}
        </button>
      </div>
    </div>

    <!-- WO-ACL-8 criterion 10: My Submissions as a dialog from the definitions page.
         The sidebar entry is gone (criterion 14); this is the only entry point now. -->
    <div
      v-if="showSubmissions"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="showSubmissions = false"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-3xl max-h-[85vh] overflow-y-auto p-6">
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-lg font-bold">{{ t('mySubmissions') }}</h2>
          <button class="text-sm text-muted-foreground hover:text-foreground" @click="showSubmissions = false">
            {{ t('close') }}
          </button>
        </div>
        <MySubmissions />
      </div>
    </div>
  </div>
</template>
