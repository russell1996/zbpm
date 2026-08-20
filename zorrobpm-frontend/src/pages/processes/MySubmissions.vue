<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { getMySubmissions, type ProcessSubmission } from '@/services/submissionService'
import { translatedError } from '@/shared/lib/utils'
import { AlertCircle, RefreshCw } from 'lucide-vue-next'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const { t } = useI18n()
const { formatDateTime } = useDateFormat()

const submissions = ref<ProcessSubmission[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    submissions.value = await getMySubmissions()
  } catch (e) {
    error.value = translatedError(e, t, t('failedToLoadSubmissions'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold">{{ t('mySubmissions') }}</h1>
        <p class="text-sm text-muted-foreground">{{ t('mySubmissionsHint') }}</p>
      </div>
      <button
        class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
        :disabled="loading"
        @click="load"
      >
        <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': loading }" />
        {{ t('refresh') }}
      </button>
    </div>

    <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="error" class="text-sm text-red-500">{{ error }}</div>

    <div v-else-if="submissions.length" class="border border-border rounded-lg overflow-hidden">
      <!-- WO-ACL-14 criteria 21-22: the 5-column table must not push the drawer
           wider than max-w-4xl — the table body scrolls horizontally inside the
           panel, and a long reject-reason wraps inside its cell (break-words)
           instead of inflating the column. -->
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead class="bg-muted">
            <tr>
              <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
              <th class="px-4 py-3 text-left font-medium">{{ t('key') }}</th>
              <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
              <th class="px-4 py-3 text-left font-medium">{{ t('submittedAt') }}</th>
              <th class="px-4 py-3 text-left font-medium">{{ t('reason') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in submissions" :key="s.id" class="border-t border-border">
              <td class="px-4 py-3 font-medium">{{ s.name || '—' }}</td>
              <td class="px-4 py-3 font-mono text-xs">{{ s.processKey }}</td>
              <td class="px-4 py-3">
                <StatusBadge :status="s.status" />
              </td>
              <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(s.submittedAt) }}</td>
              <td class="px-4 py-3">
                <div v-if="s.status === 'REJECTED'" class="flex items-start gap-1.5 text-red-600">
                  <AlertCircle class="h-4 w-4 shrink-0 mt-0.5" />
                  <span class="break-words">{{ s.rejectReason || t('noReason') }}</span>
                </div>
                <span v-else class="text-muted-foreground">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-else class="border border-border rounded-lg p-8 text-center text-sm text-muted-foreground">
      {{ t('noSubmissions') }}
    </div>
  </div>
</template>
