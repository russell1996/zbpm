<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import type { PresetTargetKind, PresetVariable, VariablePreset } from '@/types/presets'
import { createPreset } from '@/services/presetService'
import { errorMessage } from '@/shared/lib/utils'
import { useToast } from '@/composables/useToast'
import { onCtrlEnter, usePresetModal } from '@/composables/usePresetModal'

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

// WO-VT-3 раунд 2: диалог через общий usePresetModal (trap/Esc/scroll-lock).
const dialogRef = ref<HTMLElement | null>(null)
const showDialogRef = computed(() => showDialog.value)
usePresetModal(showDialogRef, dialogRef, () => {
  showDialog.value = false
})

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
      <button
        type="button"
        class="px-3 py-1.5 text-xs bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50 h-8"
        :disabled="!instanceVariables.length"
        @click="openDialog"
      >
        {{ t('presetSnapshotSave', { count: instanceVariables.length }) }}
      </button>
    </div>
    <!-- WO-VT-3 Дополнение №2 п.2: строка «зачем это» (до решения по IA). -->
    <p class="text-xs text-muted-foreground">{{ t('presetWhySnapshot') }}</p>
    <div
      v-if="showDialog"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4"
      @click.self="showDialog = false"
    >
      <div
        ref="dialogRef"
        class="bg-card rounded-lg shadow-lg w-full max-w-md p-6 space-y-4 max-h-[90dvh] overflow-y-auto"
        role="dialog"
        aria-modal="true"
        :aria-label="t('presetSnapshotDialogTitle')"
        @keydown="onSnapshotKeydown"
      >
        <h2 class="text-lg font-bold">{{ t('presetSnapshotDialogTitle') }}</h2>
        <div>
          <label for="snap-name" class="block text-xs font-medium mb-1">{{ t('presetName') }}</label>
          <input id="snap-name" v-model="snapshotName" class="w-full px-2 py-1.5 border border-input rounded text-sm" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label for="snap-kind" class="block text-xs font-medium mb-1">{{ t('presetTargetKind') }}</label>
            <select id="snap-kind" v-model="snapshotKind" class="w-full px-2 py-1.5 border border-input rounded text-sm font-mono">
              <option v-for="k in kinds" :key="k" :value="k">{{ kindLabel(k) }}</option>
            </select>
          </div>
          <div v-if="refRequired">
            <label for="snap-ref" class="block text-xs font-medium mb-1">{{ t('presetTargetRef') }}</label>
            <input id="snap-ref" v-model="snapshotRef" class="w-full px-2 py-1.5 border border-input rounded text-sm font-mono" />
          </div>
        </div>
        <p class="text-xs text-muted-foreground font-mono">{{ t('presetSnapshotSave', { count: snapshotVars.length }) }}</p>
        <p v-if="saveError" role="alert" class="text-sm text-red-500">{{ saveError }}</p>
        <div class="flex justify-end gap-2">
          <button type="button" class="px-4 py-1.5 text-sm border border-border rounded-md hover:bg-muted" @click="showDialog = false">
            {{ t('cancel') }}
          </button>
          <button
            type="button"
            class="px-4 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50"
            :disabled="!snapshotName.trim() || (refRequired && !snapshotRef.trim()) || saving"
            @click="saveSnapshot"
          >
            {{ saving ? t('loading') : t('save') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
