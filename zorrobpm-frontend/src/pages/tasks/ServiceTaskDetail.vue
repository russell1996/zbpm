<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useToast } from '@/composables/useToast'
import type { ProcessVariable } from '@/types/api'
import CopyableId from '@/widgets/shared/CopyableId.vue'

const route = useRoute()
const router = useRouter()
const store = useTaskStore()
const toast = useToast()

const editableVars = ref<{ name: string; type: string; value: string }[]>([])

async function complete() {
  const variables: ProcessVariable[] = editableVars.value.map((v) => ({
    name: v.name,
    type: v.type as ProcessVariable['type'],
    value: v.value,
  }))
  await store.completeServiceTask(route.params.id as string, variables)
  if (!store.error) {
    toast.success('Service task completed')
    router.push('/service-tasks')
  } else {
    toast.error(store.error)
  }
}

onMounted(async () => {
  await store.fetchServiceTask(route.params.id as string)
  editableVars.value = store.currentTaskVariables.map((v) => ({
    name: v.name,
    type: v.type,
    value: v.value,
  }))
})
</script>

<template>
  <div class="space-y-6">
    <div v-if="store.loading" class="text-sm text-muted-foreground">Loading...</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentServiceTask">
      <div>
        <h1 class="text-2xl font-bold">Service Task</h1>
        <CopyableId :value="store.currentServiceTask.id" />
      </div>

      <div class="grid grid-cols-2 gap-4 text-sm">
        <div><span class="text-muted-foreground">Name:</span> {{ store.currentServiceTask.name || store.currentServiceTask.code || '—' }}</div>
        <div><span class="text-muted-foreground">Job Type:</span> {{ store.currentServiceTask.job }}</div>
        <div><span class="text-muted-foreground">Process:</span> <span class="font-mono">{{ store.currentServiceTask.processInstanceId.slice(0, 8) }}...</span></div>
        <div><span class="text-muted-foreground">Created:</span> {{ new Date(store.currentServiceTask.createdAt).toLocaleString() }}</div>
      </div>

      <div class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-4">Variables</h2>
        <div class="space-y-3">
          <div v-for="(v, i) in editableVars" :key="v.name" class="flex items-center gap-3">
            <label class="text-sm font-mono w-32">{{ v.name }}</label>
            <span class="text-xs text-muted-foreground">({{ v.type }})</span>
            <input
              v-model="editableVars[i].value"
              class="flex-1 px-2 py-1 border border-input rounded text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div v-if="!editableVars.length" class="text-sm text-muted-foreground">No variables</div>
        </div>
      </div>

      <div class="flex justify-end">
        <button
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
          @click="complete"
        >
          Complete Task
        </button>
      </div>
    </template>
  </div>
</template>
