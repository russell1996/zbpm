<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getDecisions } from '@/services/dmnService'
import type { DmnDecision } from '@/services/dmnService'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'
import { RefreshCw } from 'lucide-vue-next'

const router = useRouter()
const { t } = useI18n()
const toast = useToast()
const decisions = ref<DmnDecision[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    decisions.value = await getDecisions()
  } catch {
    toast.error(t('loadError'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ t('dmnDecisions') }}</h1>
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

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">{{ t('id') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('name') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('version') }}</th>
            <th class="px-4 py-3 text-left font-medium">{{ t('hitPolicy') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="d in decisions"
            :key="d.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            tabindex="0"
            @click="router.push(`/dmn/${d.id}`)"
            @keydown.enter="router.push(`/dmn/${d.id}`)"
          >
            <td class="px-4 py-3 font-mono">{{ d.id }}</td>
            <td class="px-4 py-3 font-medium">{{ d.name }}</td>
            <td class="px-4 py-3">v{{ d.version }}</td>
            <td class="px-4 py-3">
              <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted">{{ d.hitPolicy }}</span>
            </td>
          </tr>
          <tr v-if="!decisions.length">
            <td colspan="4" class="px-4 py-8 text-center text-muted-foreground">{{ t('noDecisions') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
