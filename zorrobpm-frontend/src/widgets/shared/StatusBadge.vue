<script setup lang="ts">
/**
 * WO-ACL-11 criterion 13: ONE badge component for every status shown in the UI.
 * Every label goes through t(); the tailwind classes live here, not in pages.
 *
 * WO-ACL-14 criteria 6-8: the status icon lives INSIDE the badge, never beside
 * it. Pages pass `with-icon` and the icon name is decided here by the status —
 * TimerList/MessageList used to wrap the badge with a CheckCircle/Clock outside,
 * which rendered as "a checkmark, and next to it a separate green pill".
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CheckCircle, Clock } from 'lucide-vue-next'

const props = defineProps<{
  /** lifecycle/domain status; legacy payloads may omit it — completedAt covers that */
  status?: string | null
  completedAt?: string | null
  /** render the status icon inside the badge (name decided here by status) */
  withIcon?: boolean
}>()

const { t } = useI18n()

const CONFIG: Record<string, { key: string; cls: string }> = {
  // process instances
  RUNNING: { key: 'running', cls: 'bg-blue-100 text-blue-800' },
  // task/activity lifecycle
  CREATED: { key: 'statusActive', cls: 'bg-yellow-100 text-yellow-800' },
  IN_PROGRESS: { key: 'statusInProgress', cls: 'bg-yellow-100 text-yellow-800' },
  COMPLETED: { key: 'statusCompleted', cls: 'bg-green-100 text-green-800' },
  CANCELLED: { key: 'statusCancelled', cls: 'bg-gray-100 text-gray-700' },
  ERROR: { key: 'statusIncident', cls: 'bg-red-100 text-red-800' },
  // incidents
  OPEN: { key: 'open', cls: 'bg-red-100 text-red-800' },
  RESOLVED: { key: 'resolved', cls: 'bg-green-100 text-green-800' },
  // timers / message subscriptions (waiting = pending)
  FIRED: { key: 'fired', cls: 'bg-green-100 text-green-800' },
  CONSUMED: { key: 'consumed', cls: 'bg-green-100 text-green-800' },
  WAITING: { key: 'pending', cls: 'bg-yellow-100 text-yellow-800' },
  // process submissions (backend status is PENDING → "under review", not "waiting")
  APPROVED: { key: 'statusApproved', cls: 'bg-green-100 text-green-800' },
  REJECTED: { key: 'statusRejected', cls: 'bg-red-100 text-red-800' },
  PENDING: { key: 'statusPending', cls: 'bg-yellow-100 text-yellow-800' },
  // users
  ACTIVE: { key: 'active', cls: 'bg-green-100 text-green-800' },
  INACTIVE: { key: 'inactive', cls: 'bg-red-100 text-red-800' },
}

// WO-ACL-14 criterion 6: which icon a status gets is decided HERE, by the status.
const ICONS: Record<string, typeof CheckCircle> = {
  FIRED: CheckCircle,
  CONSUMED: CheckCircle,
  WAITING: Clock,
}

const resolvedStatus = computed(() => props.status || (props.completedAt ? 'COMPLETED' : 'CREATED'))
const cfg = computed(() => CONFIG[resolvedStatus.value])
const label = computed(() => (cfg.value ? t(cfg.value.key) : resolvedStatus.value))
const cls = computed(() => cfg.value?.cls ?? 'bg-gray-100 text-gray-700')
const iconComp = computed(() => (props.withIcon ? ICONS[resolvedStatus.value] ?? null : null))
</script>

<template>
  <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium" :class="cls">
    <component :is="iconComp" v-if="iconComp" class="h-3 w-3 shrink-0" />
    {{ label }}
  </span>
</template>