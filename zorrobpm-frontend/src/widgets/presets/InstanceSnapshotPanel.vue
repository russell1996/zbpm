<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Dialog,
  DialogScrollContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useRouter } from 'vue-router'
import type { PresetTargetKind, PresetVariable, VariablePreset } from '@/types/presets'
import { createPreset } from '@/services/presetService'
import { errorMessage } from '@/shared/lib/utils'
import { useToast } from '@/composables/useToast'
import { onCtrlEnter } from '@/composables/usePresetModal'

/**
 * WO-VT-1 (фронт, VT-4): «снимок из инстанса» — текущие переменные реального
 * инстанса сохраняются как шаблон (самый быстрый способ получить тестовые
 * данные). Пользователь задаёт имя, вид и привязку; значения подставляются
 * из инстанса (activityId сбрасывается — шаблон хранит корневые переменные).
 *
 * WO-VT-3 раунд 2 (E-VT3-1, мокап Б): кнопка «Сохранить переменные как шаблон»
 * рядом с таблицей переменных инстанса; после сохранения — тост со ссылкой
 * «Открыть шаблон» на менеджер страницы определения.
 */
const props = defineProps<{
  processKey: string
  /** id определения — для ссылки «Открыть шаблон» на менеджер (п.Б). */
  processDefinitionId?: string | null
  instanceVariables: Array<{ name: string; type: string; value: string }>
}>()

const emit = defineEmits<{
  (e: 'saved', preset: VariablePreset): void
}>()

const { t } = useI18n()
const toast = useToast()
const router = useRouter()

const showDialog = ref(false)
const snapshotName = ref('')
const snapshotKind = ref<PresetTargetKind>('START')
const snapshotRef = ref('')
const saving = ref(false)
const saveError = ref<string | null>(null)

const kinds: PresetTargetKind[] = ['START', 'USER_TASK', 'SERVICE_TASK', 'MESSAGE', 'INCIDENT', 'DMN', 'ADHOC_JOB']

function kindLabel(kind: string): string {
  return t(`presetKind_${kind}`)
}

const snapshotVars = ref<PresetVariable[]>([])

function openDialog() {
  // WO-VT-3 HOLD r1 (RT-5): программное открытие при 0 переменных не имеет
  // смысла — стоим молча (кнопка и так disabled; сохранение всё равно
  // заблокировано save-guard'ом).
  if (!props.instanceVariables.length) return
  snapshotVars.value = props.instanceVariables.map((v) => ({
    name: v.name,
    type: v.type as PresetVariable['type'],
    value: v.value,
  }))
  showDialog.value = true
}

// WO-VT-3 раунд 2: программное открытие из меню «Действия» инстанса.
defineExpose({ openDialog })

const refRequired = computed(() => snapshotKind.value !== 'START')

// WO-UI-27 доп.3: shadcn-Dialog вместо usePresetModal (trap/Esc/scroll-lock
// из коробки).

function onSnapshotKeydown(e: KeyboardEvent) {
  onCtrlEnter(e, () => {
    void saveSnapshot()
  })
}

async function saveSnapshot() {
  if (!snapshotName.value.trim() || (refRequired.value && !snapshotRef.value.trim()) || saving.value) return
  saving.value = true
  saveError.value = null
  try {
    const saved = await createPreset({
      processDefinitionKey: props.processKey,
      targetKind: snapshotKind.value,
      targetRef: refRequired.value ? snapshotRef.value.trim() : null,
      name: snapshotName.value.trim(),
      variables: snapshotVars.value,
      visibility: 'PRIVATE',
    })
    // WO-VT-3 раунд 2 (п.Б): тост со ссылкой на менеджер страницы определения.
    toast.success(t('presetSaved'), {
      action: props.processDefinitionId
        ? {
            label: t('presetOpenTemplate'),
            onClick: () => {
              void router.push({ name: 'process-definition-detail', params: { id: props.processDefinitionId! } })
            },
          }
        : undefined,
    })
    showDialog.value = false
    snapshotName.value = ''
    snapshotRef.value = ''
    emit('saved', saved)
  } catch (e) {
    saveError.value = errorMessage(e, t('presetSaveFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="border border-border rounded-lg p-4 bg-card space-y-3">
    <div class="flex items-center justify-between gap-2 flex-wrap">
      <h3 class="text-sm font-bold">{{ t('presetSnapshotTitle') }}</h3>
      <Button
        type="button"
        size="sm"
        class="px-3 text-xs h-8"
        :disabled="!instanceVariables.length"
        @click="openDialog"
      >
        {{ t('presetSnapshotSave', { count: instanceVariables.length }) }}
      </Button>
    </div>
    <!-- WO-VT-3 Дополнение №2 п.2: строка «зачем это» (до решения по IA). -->
    <p class="text-xs text-muted-foreground">{{ t('presetWhySnapshot') }}</p>
    <Dialog :open="showDialog" @update:open="(v) => { if (!v) showDialog = false }">
      <DialogScrollContent
        class="flex flex-col gap-4 p-6 w-[min(94vw,560px)] max-w-[95vw]"
        :aria-label="t('presetSnapshotDialogTitle')"
      >
        <!-- WO-UI-27 BUG-2: @keydown внутрь (display:contents) — на
             DialogScrollContent слушатель теряется (корень-фрагмент). -->
        <div class="contents" @keydown="onSnapshotKeydown">
        <DialogHeader>
          <DialogTitle class="text-lg font-bold">{{ t('presetSnapshotDialogTitle') }}</DialogTitle>
          <DialogDescription class="sr-only">{{ t('presetSnapshotDialogTitle') }}</DialogDescription>
        </DialogHeader>
        <div>
          <Label for="snap-name" class="block text-xs font-medium mb-1">{{ t('presetName') }}</Label>
          <Input id="snap-name" v-model="snapshotName" class="w-full text-sm" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <Label for="snap-kind" class="block text-xs font-medium mb-1">{{ t('presetTargetKind') }}</Label>
            <Select v-model="snapshotKind">
              <SelectTrigger id="snap-kind" class="w-full text-sm font-mono">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="k in kinds" :key="k" :value="k">{{ kindLabel(k) }}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div v-if="refRequired">
            <Label for="snap-ref" class="block text-xs font-medium mb-1">{{ t('presetTargetRef') }}</Label>
            <Input id="snap-ref" v-model="snapshotRef" class="w-full text-sm font-mono" />
          </div>
        </div>
        <p class="text-xs text-muted-foreground font-mono">{{ t('presetSnapshotSave', { count: snapshotVars.length }) }}</p>
        <p v-if="saveError" role="alert" class="text-sm text-red-500">{{ saveError }}</p>
        <DialogFooter class="gap-2">
          <Button type="button" variant="outline" @click="showDialog = false">
            {{ t('cancel') }}
          </Button>
          <Button
            type="button"
            :disabled="!snapshotName.trim() || (refRequired && !snapshotRef.trim()) || saving"
            @click="saveSnapshot"
          >
            {{ saving ? t('loading') : t('save') }}
          </Button>
        </DialogFooter>
        </div>
      </DialogScrollContent>
    </Dialog>
  </div>
</template>
