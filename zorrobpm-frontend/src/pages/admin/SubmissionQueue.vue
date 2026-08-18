<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useToast } from '@/composables/useToast'
import { getPendingSubmissions, approveSubmission, rejectSubmission, getSubmissionBpmn, type ProcessSubmission } from '@/services/submissionService'
import { errorMessage } from '@/shared/lib/utils'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import { RefreshCw, Eye } from 'lucide-vue-next'

const { t } = useI18n()
const { formatDateTime } = useDateFormat()
const toast = useToast()

const submissions = ref<ProcessSubmission[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const busyId = ref<string | null>(null)

/** Reject dialog: reason is mandatory (WO-ACL-3 — a rejection without an
 * explanation leaves the submitter guessing; ADR-8 п.9). */
const rejectTarget = ref<ProcessSubmission | null>(null)
const rejectReason = ref('')

/** WO-ACL-10 criterion 13: model preview from the queue — the reviewer sees
 *  the actual BPMN before approving (endpoint /process-submissions/{id}/bpmn). */
const viewTarget = ref<ProcessSubmission | null>(null)
const viewXml = ref('')
const viewLoading = ref(false)
const viewError = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    submissions.value = await getPendingSubmissions()
  } catch (e) {
    error.value = errorMessage(e, t('failedToLoadSubmissions'))
  } finally {
    loading.value = false
  }
}

async function openView(s: ProcessSubmission) {
  viewTarget.value = s
  viewXml.value = ''
  viewError.value = null
  viewLoading.value = true
  try {
    viewXml.value = await getSubmissionBpmn(s.id)
  } catch (e) {
    viewError.value = errorMessage(e, t('failedToLoadSubmissionModel'))
  } finally {
    viewLoading.value = false
  }
}

function closeView() {
  viewTarget.value = null
  viewXml.value = ''
  viewError.value = null
}

async function approve(s: ProcessSubmission) {
  busyId.value = s.id
  error.value = null
  try {
    await approveSubmission(s.id)
    toast.success(t('submissionApproved'))
    await load()
  } catch (e) {
    error.value = errorMessage(e, t('failedToApproveSubmission'))
  } finally {
    busyId.value = null
  }
}

function openReject(s: ProcessSubmission) {
  rejectTarget.value = s
  rejectReason.value = ''
}

async function confirmReject() {
  if (!rejectTarget.value) return
  if (!rejectReason.value.trim()) return
  busyId.value = rejectTarget.value.id
  error.value = null
  try {
    await rejectSubmission(rejectTarget.value.id, rejectReason.value.trim())
    toast.success(t('submissionRejected'))
    rejectTarget.value = null
    await load()
  } catch (e) {
    error.value = errorMessage(e, t('failedToRejectSubmission'))
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold">{{ t('submissionQueue') }}</h1>
        <p class="text-sm text-muted-foreground">{{ t('submissionQueueHint') }}</p>
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
            <th class="px-4 py-3 text-left font-medium">{{ t('submittedBy') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('submittedAt') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in submissions" :key="s.id" class="border-t border-border">
            <td class="px-4 py-3 font-medium">{{ s.name || '—' }}</td>
            <td class="px-4 py-3 font-mono text-xs">{{ s.processKey }}</td>
            <!-- WO-ACL-10 criterion 12: show the submitter's identity, never the raw
                 UUID — the DTO carries submittedByUsername/FullName/Email since ACL-9. -->
            <td class="px-4 py-3">
              <div class="font-medium">{{ s.submittedByFullName || s.submittedByUsername || '—' }}</div>
              <div v-if="s.submittedByEmail" class="text-xs text-muted-foreground">{{ s.submittedByEmail }}</div>
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(s.submittedAt) }}</td>
            <td class="px-4 py-3">
              <div class="flex items-center gap-2">
                <button
                  class="flex items-center gap-1.5 px-3 py-1 text-xs border border-border rounded-md hover:bg-muted disabled:opacity-50"
                  :disabled="busyId === s.id"
                  @click="openView(s)"
                >
                  <Eye class="h-3.5 w-3.5" />
                  {{ t('view') }}
                </button>
                <button
                  class="px-3 py-1 text-xs bg-green-600 text-white rounded-md hover:bg-green-700 disabled:opacity-50"
                  :disabled="busyId === s.id"
                  @click="approve(s)"
                >
                  {{ t('approve') }}
                </button>
                <button
                  class="px-3 py-1 text-xs border border-red-300 text-red-600 rounded-md hover:bg-red-50 disabled:opacity-50"
                  :disabled="busyId === s.id"
                  @click="openReject(s)"
                >
                  {{ t('reject') }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else class="border border-border rounded-lg p-8 text-center text-sm text-muted-foreground">
      {{ t('noPendingSubmissions') }}
    </div>

    <!-- Reject dialog — reason is mandatory -->
    <div v-if="rejectTarget" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="rejectTarget = null">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <h3 class="font-bold">{{ t('rejectSubmissionTitle') }}</h3>
        <p class="text-sm text-muted-foreground">
          {{ t('rejectSubmissionHint') }} <span class="font-mono">{{ rejectTarget.processKey }}</span>
        </p>
        <textarea
          v-model="rejectReason"
          class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          rows="3"
          :placeholder="t('rejectReasonPlaceholder')"
        />
        <div class="flex justify-end gap-2">
          <button class="px-3 py-1.5 text-sm border border-border rounded-md" @click="rejectTarget = null">{{ t('cancel') }}</button>
          <button
            class="px-3 py-1.5 text-sm bg-red-600 text-white rounded-md hover:bg-red-700 disabled:opacity-50"
            :disabled="busyId === rejectTarget.id || !rejectReason.trim()"
            @click="confirmReject"
          >
            {{ t('reject') }}
          </button>
        </div>
      </div>
    </div>

    <!-- WO-ACL-10 criterion 13: model preview from the queue (BpmnViewer, same as the definition card) -->
    <div v-if="viewTarget" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="closeView">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-4xl p-6 space-y-4">
        <div class="flex items-start justify-between gap-4">
          <div>
            <h3 class="font-bold">{{ viewTarget.name || viewTarget.processKey }}</h3>
            <p class="text-xs text-muted-foreground font-mono">{{ viewTarget.processKey }}</p>
          </div>
          <button class="text-xs text-muted-foreground hover:text-foreground" @click="closeView">{{ t('close') }}</button>
        </div>
        <div v-if="viewLoading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
        <div v-else-if="viewError" class="text-sm text-red-500">{{ viewError }}</div>
        <BpmnViewer v-else :xml="viewXml" />
      </div>
    </div>
  </div>
</template>
