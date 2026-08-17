<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { getMySubmissions, type ProcessSubmission } from '@/services/submissionService'
import { errorMessage } from '@/shared/lib/utils'
import { AlertCircle, RefreshCw } from 'lucide-vue-next'

const router = useRouter()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

const submissions = ref<ProcessSubmission[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

function statusBadge(status: string): { label: string; cls: string } {
  switch (status) {
    case 'APPROVED': return { label: t('statusApproved'), cls: 'bg-green-100 text-green-800' }
    case 'REJECTED': return { label: t('statusRejected'), cls: 'bg-red-100 text-red-800' }
    default: return { label: t('statusPending'), cls: 'bg-yellow-100 text-yellow-800' }
  }
}

async function load() {
  loading.value = true
  error.value = null
  try {
    submissions.value = await getMySubmissions()
  } catch (e) {
    error.value = errorMessage(e, t('failedToLoadSubmissions'))
  } finally {
    loading.value = false
  }
}

/** WO-ACL-6 criterion 6: resubmission creates a new record chained to the previous
 * one (the backend links previousSubmissionId) — the UI just opens the uploader. */
function resubmit() {
  router.push('/processes/definitions')
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
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('key') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('submittedAt') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('reason') }}</th>
            <th class="px-4 py-3 text-left font-medium"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in submissions" :key="s.id" class="border-t border-border">
            <td class="px-4 py-3 font-medium">{{ s.name || '—' }}</td>
            <td class="px-4 py-3 font-mono text-xs">{{ s.processKey }}</td>
            <td class="px-4 py-3">
              <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium" :class="statusBadge(s.status).cls">
                {{ statusBadge(s.status).label }}
              </span>
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(s.submittedAt) }}</td>
            <td class="px-4 py-3">
              <div v-if="s.status === 'REJECTED'" class="flex items-start gap-1.5 text-red-600">
                <AlertCircle class="h-4 w-4 shrink-0 mt-0.5" />
                <span>{{ s.rejectReason || t('noReason') }}</span>
              </div>
              <span v-else class="text-muted-foreground">—</span>
            </td>
            <td class="px-4 py-3 text-right">
              <button
                v-if="s.status === 'REJECTED'"
                class="text-sm text-primary hover:underline"
                @click="resubmit"
              >
                {{ t('resubmit') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else class="border border-border rounded-lg p-8 text-center text-sm text-muted-foreground">
      {{ t('noSubmissions') }}
    </div>
  </div>
</template>
