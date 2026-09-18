<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getMessageSubscriptions } from '@/services/messageService'
import type { MessageSubscription } from '@/types/api'
import { Mail, RefreshCw } from 'lucide-vue-next'
import { exportToCsv } from '@/shared/lib/export'
import { Download } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useToast } from '@/composables/useToast'
import { useDateFormat } from '@/composables/useDateFormat'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const { t } = useI18n()
const toast = useToast()
const { formatDateTime } = useDateFormat()
const messages = ref<MessageSubscription[]>([])
const total = ref(0)
const loading = ref(false)
const router = useRouter()

/** WO-ACL-11 criterion 6: no message-subscription detail page — the row opens
 * its process instance; the full instance id is visible/copyable (criterion 10). */
function openInstance(msg: MessageSubscription) {
  if (msg.processInstanceId) router.push(`/processes/instances/${msg.processInstanceId}`)
}

async function load() {
  loading.value = true
  try {
    const result = await getMessageSubscriptions({ pageIndex: 0, pageSize: 100 })
    messages.value = result.data
    total.value = result.totalElements
  } catch {
    toast.error(t('loadError'))
  } finally {
    loading.value = false
  }
}

onMounted(load)

function exportData() {
  exportToCsv(messages.value.map((m) => ({
    id: m.id,
    processInstanceId: m.processInstanceId || '',
    messageName: m.messageName,
    correlationKey: m.correlationKey || '',
    status: m.consumed ? t('consumed') : t('pending'),
    createdAt: m.createdAt,
  })), 'message-subscriptions.csv')
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ t('messageSubscriptions') }}</h1>
      <div class="flex items-center gap-2">
        <button
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          :disabled="loading"
          @click="load"
        >
          <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': loading }" />
          {{ t('refresh') }}
        </button>
        <button
          v-if="messages.length"
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          @click="exportData"
        >
          <Download class="h-4 w-4" />
          {{ t('export') }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">{{ t('id') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('messageName') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('processInstance') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('status') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('created') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="msg in messages"
            :key="msg.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            :tabindex="msg.processInstanceId ? 0 : -1"
            @click="openInstance(msg)"
            @keydown.enter="openInstance(msg)"
          >
            <td class="px-4 py-3"><CopyableId :value="msg.id" /></td>
            <td class="px-4 py-3">
              <span class="inline-flex items-center gap-1.5">
                <Mail class="h-3.5 w-3.5 text-muted-foreground" />
                {{ msg.messageName }}
              </span>
            </td>
            <td class="px-4 py-3"><CopyableId v-if="msg.processInstanceId" :value="msg.processInstanceId" /><span v-else class="text-muted-foreground">—</span></td>
            <td class="px-4 py-3">
              <!-- WO-ACL-14 criteria 6-8: the icon lives INSIDE StatusBadge (with-icon) -->
              <StatusBadge :status="msg.consumed ? 'CONSUMED' : 'WAITING'" with-icon />
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDateTime(msg.createdAt) }}</td>
          </tr>
          <tr v-if="!messages.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">{{ t('noMessages') }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="total" class="text-sm text-muted-foreground">{{ total }} {{ t('total') }}</div>
  </div>
</template>
