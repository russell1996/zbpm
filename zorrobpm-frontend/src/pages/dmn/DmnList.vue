<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getDecisions } from '@/services/mock/dmnService'
import type { DmnDecision } from '@/services/mock/dmnService'
import { AlertCircle, RefreshCw } from 'lucide-vue-next'

const router = useRouter()
const decisions = ref<DmnDecision[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    decisions.value = await getDecisions()
  } catch {
    // ignore
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">DMN Decisions</h1>
      <button
        class="flex items-center gap-2 px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors"
        :disabled="loading"
        @click="load"
      >
        <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': loading }" />
        Refresh
      </button>
    </div>

    <div v-if="loading" class="text-sm text-muted-foreground">Loading...</div>

    <div v-else class="border border-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead class="bg-muted">
          <tr>
            <th class="px-4 py-3 text-left font-medium">ID</th>
            <th class="px-4 py-3 text-left font-medium">Name</th>
            <th class="px-4 py-3 text-left font-medium">Version</th>
            <th class="px-4 py-3 text-left font-medium">Hit Policy</th>
            <th class="px-4 py-3 text-left font-medium">Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="d in decisions"
            :key="d.id"
            class="border-t border-border hover:bg-muted/50 cursor-pointer"
            @click="router.push(`/dmn/${d.id}`)"
          >
            <td class="px-4 py-3 font-mono">{{ d.id }}</td>
            <td class="px-4 py-3 font-medium">{{ d.name }}</td>
            <td class="px-4 py-3">v{{ d.version }}</td>
            <td class="px-4 py-3">
              <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-muted">{{ d.hitPolicy }}</span>
            </td>
            <td class="px-4 py-3">
              <button class="text-sm text-primary hover:underline" @click.stop="router.push(`/dmn/${d.id}`)">View</button>
            </td>
          </tr>
          <tr v-if="!decisions.length">
            <td colspan="5" class="px-4 py-8 text-center text-muted-foreground">No DMN decisions found</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="border border-border rounded-lg p-4 bg-card">
      <div class="flex items-center gap-2 text-sm text-muted-foreground">
        <AlertCircle class="h-4 w-4" />
        <span>DMN data is mocked. Backend API <code>GET /dmn/decisions</code> not yet available.</span>
      </div>
    </div>
  </div>
</template>
