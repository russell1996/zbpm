<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useTaskStore } from '@/stores/task'
import { useToast } from '@/composables/useToast'
import { useBreadcrumbLabel } from '@/composables/useBreadcrumbLabel'
import type { ProcessVariable } from '@/types/api'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'

const route = useRoute()
const router = useRouter()
const store = useTaskStore()
const toast = useToast()
const { t } = useI18n()
const { formatDateTime } = useDateFormat()

// WO-ACL-15 criterion 17: the service-task crumb shows the task title; a task
// without a name/type falls back to a short id so the crumb never shows an
// empty string. Filled via the shared useBreadcrumbLabel(), cleared on unmount
// (criterion 18).
useBreadcrumbLabel(() => {
  const task = store.currentServiceTask
  if (!task) return null
  const title = task.name || task.code
  return title || `${t('serviceTask')} ${task.id.slice(0, 8)}`
})

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
    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentServiceTask">
      <div>
        <h1 class="text-2xl font-bold">{{ t('serviceTask') }}</h1>
        <CopyableId :value="store.currentServiceTask.id" />
      </div>

      <div class="flex items-center gap-4 text-sm">
        <StatusBadge :status="store.currentServiceTask.completedAt ? 'COMPLETED' : 'CREATED'" />
      </div>

      <div class="grid grid-cols-2 gap-4 text-sm">
        <div><span class="text-muted-foreground">{{ t('name') }}:</span> {{ store.currentServiceTask.name || store.currentServiceTask.code || '—' }}</div>
        <div><span class="text-muted-foreground">{{ t('jobTypeLabel') }}:</span> {{ store.currentServiceTask.job }}</div>
        <div>
          <span class="text-muted-foreground">{{ t('process') }}:</span>
          <CopyableId :value="store.currentServiceTask.processInstanceId" />
        </div>
        <div><span class="text-muted-foreground">{{ t('created') }}:</span> {{ formatDateTime(store.currentServiceTask.createdAt) }}</div>
        <div v-if="store.currentServiceTask.completedAt" class="col-span-2">
          <span class="text-muted-foreground">{{ t('completedAtLabel') }}:</span> {{ formatDateTime(store.currentServiceTask.completedAt) }}
        </div>
      </div>

      <div class="border border-border rounded-lg p-4 bg-card">
        <h2 class="text-lg font-bold mb-4">{{ t('variables') }}</h2>
        <div class="space-y-3">
          <div v-for="(v, i) in editableVars" :key="v.name" class="flex items-center gap-3">
            <label class="text-sm font-mono w-32">{{ v.name }}</label>
            <span class="text-xs text-muted-foreground">({{ v.type }})</span>
            <input
              v-model="editableVars[i].value"
              class="flex-1 px-2 py-1 border border-input rounded text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div v-if="!editableVars.length" class="text-sm text-muted-foreground">{{ t('noVariables') }}</div>
        </div>
      </div>

      <div v-if="!store.currentServiceTask.completedAt" class="flex justify-end">
        <button
          class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:opacity-90 transition-opacity text-sm"
          @click="complete"
        >
          {{ t('completeTask') }}
        </button>
      </div>
    </template>
  </div>
</template>
