<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getMessageSubscriptions } from '@/services/messageService'
import type { MessageSubscription } from '@/types/api'
import { Mail, CheckCircle, RefreshCw } from 'lucide-vue-next'
import { exportToCsv } from '@/shared/lib/export'
import { Download } from 'lucide-vue-next'

const messages = ref<MessageSubscription[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await getMessageSubscriptions({ pageIndex: 0, pageSize: 100 })
    messages.value = result.data
    total.value = result.totalElements
  } catch {
    // ignore
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
    status: m.consumed ? 'Consumed' : 'Pending',
    createdAt: m.createdAt,
  })), 'message-subscriptions.csv')
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">Message Subscriptions</h1>
      <div class="flex items-center gap-2">
        <button
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          :disabled="loading"
          @click="load"
        >
          <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': loading }" />
          Refresh
        </button>
        <button
          v-if="messages.length"
          class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
          @click="exportData"
        >
          <Download class="h-4 w-4" />
          Export CSV
        </button>
      </div>
    </div>

    <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">ID</th>
            <th class="px-4 py-3 text-left font-medium">Message Name</th>
            <th class="px-4 py-3 text-left font-medium">Process Instance</th>
            <th class="px-4 py-3 text-left font-medium">Status</th>
            <th class="px-4 py-3 text-left font-medium">Created</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="msg in messages" :key="msg.id" class="border-t border-border">
            <td class="px-4 py-3 font-mono text-xs">{{ msg.id }}</td>
            <td class="px-4 py-3">
              <span class="inline-flex items-center gap-1.5">
                <Mail class="h-3.5 w-3.5 text-muted-foreground" />
                {{ msg.messageName }}
              </span>
            </td>
            <td class="px-4 py-3 font-mono text-xs">{{ msg.processInstanceId ? msg.processInstanceId.slice(0, 8) + '...' : '—' }}</td>
            <td class="px-4 py-3">
              <span :class="['inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium', msg.consumed ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800']">
                <CheckCircle v-if="msg.consumed" class="h-3 w-3" />
                {{ msg.consumed ? 'Consumed' : 'Pending' }}
              </span>
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ new Date(msg.createdAt).toLocaleString() }}</td>
          </tr>
          <tr v-if="!messages.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">No message subscriptions found</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="total" class="text-sm text-muted-foreground">{{ total }} total</div>
  </div>
</template>
