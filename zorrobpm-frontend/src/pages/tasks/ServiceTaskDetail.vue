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
import { ArrowLeft } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'

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
  <div class="space-y-4">
    <div v-if="store.loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
    <div v-else-if="store.error" class="text-sm text-red-500">{{ store.error }}</div>

    <template v-else-if="store.currentServiceTask">
      <!-- WO-UI-14: compact header -->
      <div class="flex items-center gap-2">
        <RouterLink :to="{ name: 'service-tasks' }" class="inline-flex items-center justify-center h-9 w-9 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors shrink-0 self-center">
          <ArrowLeft class="h-5 w-5" />
        </RouterLink>
        <div class="flex flex-col gap-0.5 flex-1 min-w-0">
          <!-- Row 1: ← Сервисные задачи · ID ········································ [Complete] -->
          <div class="flex items-center gap-2">
            <span class="text-sm text-muted-foreground whitespace-nowrap">{{ t('serviceTasks') }}</span>
            <span class="text-foreground/80 text-xs">·</span>
            <span class="text-sm text-muted-foreground/80"><CopyableId :value="store.currentServiceTask.id" /></span>
            <span class="flex-1" />
            <Button v-if="!store.currentServiceTask.completedAt" size="sm" class="h-8 px-3 text-xs" @click="complete">
              {{ t('completeTask') }}
            </Button>
          </div>
          <!-- Row 2: Name · Status · JobType — center-aligned -->
          <div class="flex items-center gap-2 flex-wrap -mt-1">
            <span class="text-base font-semibold truncate">{{ store.currentServiceTask.name || store.currentServiceTask.code || t('serviceTask') }}</span>
            <Badge
              variant="secondary"
              class="shrink-0 text-[11px] px-1.5 py-px rounded-full"
              :class="store.currentServiceTask.completedAt
                ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'"
            >
              {{ store.currentServiceTask.completedAt ? t('completed') : t('created') }}
            </Badge>
            <span class="text-muted-foreground/60 text-xs font-mono">{{ store.currentServiceTask.job }}</span>
          </div>
        </div>
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
    </template>
  </div>
</template>
