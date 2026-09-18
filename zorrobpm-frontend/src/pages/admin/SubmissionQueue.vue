<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useToast } from '@/composables/useToast'
import { getPendingSubmissions, approveSubmission, rejectSubmission, getSubmissionBpmn, type ProcessSubmission } from '@/services/submissionService'
import { translatedError } from '@/shared/lib/utils'
import BpmnViewer from '@/widgets/bpmn/BpmnViewer.vue'
import { RefreshCw } from 'lucide-vue-next'

const { t } = useI18n()
const { formatDateTime } = useDateFormat()
const toast = useToast()

/**
 * WO-ACL-15 criterion 9: the queue is a HISTORY view — the tab switches the
 * status filter; PENDING stays the default (the admin's working mode).
 */
const statusOptions = [
  { value: 'PENDING', label: 'submissionStatusPending' },
  { value: 'APPROVED', label: 'submissionStatusApproved' },
  { value: 'REJECTED', label: 'submissionStatusRejected' },
  { value: 'ALL', label: 'submissionStatusAll' },
] as const
const statusFilter = ref<string>('PENDING')

const submissions = ref<ProcessSubmission[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const busyId = ref<string | null>(null)

/** Reject dialog: reason is mandatory (WO-ACL-3 — a rejection without an
 * explanation leaves the submitter guessing; ADR-8 п.9). */
const rejectTarget = ref<ProcessSubmission | null>(null)
const rejectReason = ref('')

/** WO-ACL-10 criterion 13: model preview from the queue — the reviewer sees
 *  the actual BPMN before approving (endpoint /process-submissions/{id}/bpmn).
 *  WO-ACL-15 criterion 11: works from ANY status, not only PENDING. */
const viewTarget = ref<ProcessSubmission | null>(null)
const viewXml = ref('')
const viewLoading = ref(false)
const viewError = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
  submissions.value = await getPendingSubmissions(statusFilter.value)
  } catch (e) {
    error.value = translatedError(e, t, t('failedToLoadSubmissions'))
  } finally {
    loading.value = false
  }
}

function switchStatus(value: string) {
  if (statusFilter.value === value) return
  statusFilter.value = value
  void load()
}

const emptyStateLabel = computed(() =>
  statusFilter.value === 'PENDING' ? t('noPendingSubmissions') : t('noSubmissionsInStatus'),
)

async function openView(s: ProcessSubmission) {
  viewTarget.value = s
  viewXml.value = ''
  viewError.value = null
  viewLoading.value = true
  try {
    viewXml.value = await getSubmissionBpmn(s.id)
  } catch (e) {
    viewError.value = translatedError(e, t, t('failedToLoadSubmissionModel'))
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
    error.value = translatedError(e, t, t('failedToApproveSubmission'))
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
    error.value = translatedError(e, t, t('failedToRejectSubmission'))
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

    <!-- WO-ACL-15 criterion 9: status tabs — PENDING by default, history on demand -->
    <div role="tablist" class="inline-flex border border-border rounded-md overflow-hidden">
      <button
        v-for="opt in statusOptions"
        :key="opt.value"
        role="tab"
        :aria-selected="statusFilter === opt.value"
        class="px-3 py-1.5 text-sm transition-colors"
        :class="statusFilter === opt.value ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'"
        @click="switchStatus(opt.value)"
      >
        {{ t(opt.label) }}
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
            <!-- WO-ACL-15 criterion 10: who decided, when, and (for rejections) why -->
            <th class="px-4 py-3 text-left font-medium">{{ t('decision') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="s in submissions"
            :key="s.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            tabindex="0"
            @click="openView(s)"
            @keydown.enter="openView(s)"
          >
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
              <div v-if="s.status === 'APPROVED'" class="text-green-600">
                {{ t('approvedLabel') }} · {{ s.reviewedByUsername || '—' }} · {{ s.reviewedAt ? formatDateTime(s.reviewedAt) : '—' }}
              </div>
              <div v-else-if="s.status === 'REJECTED'" class="text-red-600">
                <div>{{ t('rejectedLabel') }} · {{ s.reviewedByUsername || '—' }} · {{ s.reviewedAt ? formatDateTime(s.reviewedAt) : '—' }}</div>
                <div class="text-xs break-words">{{ s.rejectReason || t('noReason') }}</div>
              </div>
              <span v-else class="text-muted-foreground">—</span>
            </td>
            <td class="px-4 py-3">
              <div v-if="s.status === 'PENDING'" class="flex items-center gap-2">
                <button
                  class="px-3 py-1 text-xs bg-green-600 text-white rounded-md hover:bg-green-700 disabled:opacity-50"
                  :disabled="busyId === s.id"
                  @click.stop="approve(s)"
                >
                  {{ t('approve') }}
                </button>
                <button
                  class="px-3 py-1 text-xs border border-red-300 text-red-600 rounded-md hover:bg-red-50 disabled:opacity-50"
                  :disabled="busyId === s.id"
                  @click.stop="openReject(s)"
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
      {{ emptyStateLabel }}
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
        <!-- WO-ACL-11 criterion 37: BpmnViewer stretches by layout now (h-full),
             the modal preview gives it a viewport-based height (vh, not px). -->
        <div v-else class="h-[70vh]">
          <BpmnViewer :xml="viewXml" />
        </div>
      </div>
    </div>
  </div>
</template>
