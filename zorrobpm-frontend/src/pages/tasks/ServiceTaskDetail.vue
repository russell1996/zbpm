<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDateFormat } from '@/composables/useDateFormat'
import { useTaskStore } from '@/stores/task'
import { useToast } from '@/composables/useToast'
import { useBreadcrumbLabel } from '@/composables/useBreadcrumbLabel'
import type { ProcessVariable } from '@/types/api'
import CopyableId from '@/widgets/shared/CopyableId.vue'
import StatusBadge from '@/widgets/shared/StatusBadge.vue'
import { useProcessStore } from '@/stores/process'
import PresetPicker from '@/widgets/presets/PresetPicker.vue'
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
// WO-VT-1: завершить / ошибка / throw error — из шаблона ЭТОГО элемента.
const processStore = useProcessStore()
const pickerRef = ref<InstanceType<typeof PresetPicker> | null>(null)
const askMissing = ref<string[]>([])
const pickerInvalid = ref(false)
const presetKey = ref('')
const presetRef = ref<string | null>(null)
type ServiceTaskAction = 'complete' | 'fail' | 'throw'
const action = ref<ServiceTaskAction>('complete')
const failMessage = ref('')
const errorCode = ref('')

function pickerVariables(): ProcessVariable[] {
  return (pickerRef.value?.getVariables() ?? []).map((v) => ({
    name: v.name,
    type: v.type,
    value: v.value,
  }))
}

/**
 * WO-VT-1 раунд 2 (Б-1): единый источник переменных для экшенов. Когда пикер
 * смонтирован — переменные из него (ручной ввод или шаблон с развёрнутыми
 * плейсхолдерами); когда пикера нет (presetKey пуст — fetchDefinition упал /
 * нет processDefinitionId) — из legacy-редактора editableVars, который в этом
 * случае и рендерится. До раунда 2 экшены слали только pickerVariables() = []
 * без пикера — пользователь правил переменные, завершение уходило пустым.
 */
function actionVariables(): ProcessVariable[] {
  if (pickerRef.value) return pickerVariables()
  return editableVars.value.map((v) => ({
    name: v.name,
    type: v.type as ProcessVariable['type'],
    value: v.value,
  }))
}

function onPickerChange() {
  askMissing.value = pickerRef.value?.missingAsk ?? []
  pickerInvalid.value = pickerRef.value?.hasErrors ?? false
}

async function complete() {
  await store.completeServiceTask(route.params.id as string, actionVariables())
  if (!store.error) {
    toast.success('Service task completed')
    router.push('/service-tasks')
  } else {
    toast.error(store.error)
  }
}

/** WO-VT-1: «ошибка» service task с переменными из шаблона элемента. */
async function fail() {
  await store.failServiceTask(route.params.id as string, failMessage.value, actionVariables())
  if (!store.error) {
    toast.success(t('serviceTaskFailed'))
    router.push('/service-tasks')
  } else {
    toast.error(store.error)
  }
}

/** WO-VT-1: throw error с переменными из шаблона элемента. */
async function throwError() {
  if (!errorCode.value.trim()) return
  await store.throwServiceTaskError(route.params.id as string, errorCode.value.trim(), actionVariables())
  if (!store.error) {
    toast.success(t('serviceTaskErrorThrown'))
    router.push('/service-tasks')
  } else {
    toast.error(store.error)
  }
}

const actionDisabled = computed(() =>
  askMissing.value.length > 0 || pickerInvalid.value ||
  (action.value === 'throw' && !errorCode.value.trim()),
)

// WO-ACL-11 criterion 11: строковых литералов в шаблоне нет — метка действия
// и диспетчер живут в script (сканер непереведённых строк флагит литералы
// внутри {{ }}).
const actionLabel = computed(() =>
  action.value === 'complete' ? t('completeTask')
  : action.value === 'fail' ? t('serviceTaskFail')
  : t('serviceTaskThrowError'))

function runAction() {
  if (action.value === 'complete') return complete()
  if (action.value === 'fail') return fail()
  return throwError()
}

onMounted(async () => {
  await store.fetchServiceTask(route.params.id as string)
  editableVars.value = store.currentTaskVariables.map((v) => ({
    name: v.name,
    type: v.type,
    value: v.value,
  }))
  const task = store.currentServiceTask
  if (task?.processDefinitionId) {
    try {
      await processStore.fetchDefinition(task.processDefinitionId)
      presetKey.value = processStore.currentDefinition?.key ?? ''
      presetRef.value = task.code
    } catch {
      // Шаблоны недоступны — останется legacy-редактор ниже.
    }
  }
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

      <div class="border border-border rounded-lg p-4 bg-card space-y-3">
        <h2 class="text-lg font-bold mb-4">{{ t('variables') }}</h2>
        <!-- WO-VT-1: переменные завершения — из шаблона элемента (или ручной ввод) -->
        <PresetPicker
          v-if="presetKey"
          ref="pickerRef"
          :process-key="presetKey"
          target-kind="SERVICE_TASK"
          :target-ref="presetRef"
          @change="onPickerChange"
        />
        <div v-else class="space-y-3">
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
        <!-- WO-VT-1: действие над переменными: завершить / ошибка / throw error -->
        <div v-if="!store.currentServiceTask.completedAt" class="pt-2 border-t border-border space-y-3">
          <div class="flex items-center gap-1" role="tablist" :aria-label="t('serviceTaskAction')">
            <button
              type="button"
              role="tab"
              :aria-selected="action === 'complete'"
              class="px-3 py-1 text-xs rounded-md border"
              :class="action === 'complete' ? 'bg-primary text-primary-foreground border-primary' : 'border-border hover:bg-muted'"
              @click="action = 'complete'"
            >
              {{ t('completeTask') }}
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="action === 'fail'"
              class="px-3 py-1 text-xs rounded-md border"
              :class="action === 'fail' ? 'bg-primary text-primary-foreground border-primary' : 'border-border hover:bg-muted'"
              @click="action = 'fail'"
            >
              {{ t('serviceTaskFail') }}
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="action === 'throw'"
              class="px-3 py-1 text-xs rounded-md border"
              :class="action === 'throw' ? 'bg-primary text-primary-foreground border-primary' : 'border-border hover:bg-muted'"
              @click="action = 'throw'"
            >
              {{ t('serviceTaskThrowError') }}
            </button>
          </div>
          <div v-if="action === 'fail'">
            <label for="st-fail-message" class="block text-xs font-medium mb-1">{{ t('serviceTaskFailMessage') }}</label>
            <input
              id="st-fail-message"
              v-model="failMessage"
              class="w-full px-2 py-1.5 border border-input rounded text-sm"
            />
          </div>
          <div v-if="action === 'throw'">
            <label for="st-error-code" class="block text-xs font-medium mb-1">{{ t('serviceTaskErrorCode') }}</label>
            <input
              id="st-error-code"
              v-model="errorCode"
              class="w-full px-2 py-1.5 border border-input rounded text-sm font-mono"
            />
          </div>
          <div class="flex justify-end">
            <Button
              size="sm"
              class="h-8 px-3 text-xs disabled:opacity-50"
              :disabled="actionDisabled"
              :title="askMissing.length ? t('presetFillAskFields', { fields: askMissing.join(', ') }) : ''"
              @click="runAction"
            >
              {{ actionLabel }}
            </Button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
