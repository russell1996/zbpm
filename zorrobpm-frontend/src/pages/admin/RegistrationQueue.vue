<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useToast } from '@/composables/useToast'
import {
  getPendingRegistrations,
  approveRegistration,
  rejectRegistration,
  type PendingRegistration,
} from '@/services/registrationService'
import { translatedError } from '@/shared/lib/utils'
import { RefreshCw } from 'lucide-vue-next'

const { t } = useI18n()
const { formatDateTime } = useDateFormat()
const toast = useToast()

const registrations = ref<PendingRegistration[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const busyId = ref<string | null>(null)

const rejectTarget = ref<PendingRegistration | null>(null)
const rejectReason = ref('')

async function load() {
  loading.value = true
  error.value = null
  try {
    registrations.value = await getPendingRegistrations()
  } catch (e) {
    error.value = translatedError(e, t, t('failedToLoadRegistrations'))
  } finally {
    loading.value = false
  }
}

async function approve(r: PendingRegistration) {
  busyId.value = r.id
  error.value = null
  try {
    await approveRegistration(r.id)
    toast.success(t('registrationApproved'))
    await load()
  } catch (e) {
    error.value = translatedError(e, t, t('failedToApproveRegistration'))
  } finally {
    busyId.value = null
  }
}

function openReject(r: PendingRegistration) {
  rejectTarget.value = r
  rejectReason.value = ''
}

async function confirmReject() {
  if (!rejectTarget.value) return
  busyId.value = rejectTarget.value.id
  error.value = null
  try {
    await rejectRegistration(rejectTarget.value.id, rejectReason.value.trim() || undefined)
    toast.success(t('registrationRejected'))
    rejectTarget.value = null
    await load()
  } catch (e) {
    error.value = translatedError(e, t, t('failedToRejectRegistration'))
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
        <h1 class="text-2xl font-bold">{{ t('registrationQueue') }}</h1>
        <p class="text-sm text-muted-foreground">{{ t('registrationQueueHint') }}</p>
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
    <div v-else-if="error" class="text-sm text-red-500" data-testid="registration-queue-error">{{ error }}</div>

    <div v-else-if="registrations.length" class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">{{ t('username') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('email') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('fullName') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('createdAt') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in registrations"
            :key="r.id"
            class="border-t border-border hover:bg-muted/50"
            data-testid="registration-row"
          >
            <td class="px-4 py-3 font-medium">{{ r.username }}</td>
            <td class="px-4 py-3 font-mono text-xs">{{ r.email }}</td>
            <td class="px-4 py-3">{{ r.fullName || '—' }}</td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(r.createdAt) }}</td>
            <td class="px-4 py-3">
              <div class="flex items-center gap-2">
                <button
                  class="px-3 py-1 text-xs bg-green-600 text-white rounded-md hover:bg-green-700 disabled:opacity-50"
                  :disabled="busyId === r.id"
                  data-testid="registration-approve"
                  @click="approve(r)"
                >
                  {{ t('approve') }}
                </button>
                <button
                  class="px-3 py-1 text-xs border border-red-300 text-red-600 rounded-md hover:bg-red-50 disabled:opacity-50"
                  :disabled="busyId === r.id"
                  data-testid="registration-reject"
                  @click="openReject(r)"
                >
                  {{ t('reject') }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else class="border border-border rounded-lg p-8 text-center text-sm text-muted-foreground" data-testid="registration-empty">
      {{ t('noPendingRegistrations') }}
    </div>

    <div v-if="rejectTarget" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="rejectTarget = null">
      <div class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4">
        <h3 class="font-bold">{{ t('rejectRegistrationTitle') }}</h3>
        <p class="text-sm text-muted-foreground">
          {{ t('rejectRegistrationHint') }} <span class="font-mono">{{ rejectTarget.username }}</span>
        </p>
        <textarea
          v-model="rejectReason"
          class="w-full px-3 py-2 border border-input rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          rows="3"
          :placeholder="t('rejectReasonPlaceholder')"
          data-testid="reject-reason"
        />
        <div class="flex justify-end gap-2">
          <button class="px-3 py-1.5 text-sm border border-border rounded-md" @click="rejectTarget = null">{{ t('cancel') }}</button>
          <button
            class="px-3 py-1.5 text-sm bg-red-600 text-white rounded-md hover:bg-red-700 disabled:opacity-50"
            :disabled="busyId === rejectTarget.id"
            data-testid="reject-confirm"
            @click="confirmReject"
          >
            {{ t('reject') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
