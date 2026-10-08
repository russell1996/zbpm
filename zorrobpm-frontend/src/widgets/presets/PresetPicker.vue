<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import TabsBar from '@/widgets/shared/TabsBar.vue'
import VariablesEditor from './VariablesEditor.vue'
import PresetEditorDialog from './PresetEditorDialog.vue'
import type { PresetTargetKind, PresetVariable, VariablePreset } from '@/types/presets'
import {
  listPresets,
  getPreset,
  isPresetsDisabled,
} from '@/services/presetService'
import {
  applyPresetVariables,
  missingAskVariables,
  validatePresetRow,
  duplicateVariableNames,
} from '@/shared/lib/presetVariables'
import { errorMessage } from '@/shared/lib/utils'

/**
 * WO-VT-1 (фронт, §1-бис п.2): переключатель «Ручной ввод | Из шаблона».
 * Ручной — VariablesEditor; из шаблона — список шаблонов места (key/kind/ref),
 * предпросмотр с правкой копии, плейсхолдеры разворачиваются при «Применить»,
 * пустые «спросить при запуске» блокируют применение, пока не заполнены
 * (значение из формы в шаблон НЕ сохраняется само — только кнопкой).
 * При выключенном флаге блок прячется целиком (п.6 WO).
 */
const props = defineProps<{
  processKey: string
  targetKind: PresetTargetKind
  targetRef?: string | null
}>()

const emit = defineEmits<{
  (e: 'change', variables: PresetVariable[]): void
  (e: 'templateSaved', preset: VariablePreset): void
}>()

const { t } = useI18n()

type Mode = 'manual' | 'template'
const mode = ref<Mode>('manual')
const manualVars = ref<PresetVariable[]>([])
const templateVars = ref<PresetVariable[]>([])
const presets = ref<VariablePreset[]>([])
const selectedId = ref<string>('')
const loading = ref(false)
const loadError = ref<string | null>(null)
const available = ref(true)
const showSaveDialog = ref(false)

const modeTabs = computed(() => [
  { id: 'manual', label: t('presetManualMode') },
  { id: 'template', label: t('presetTemplateMode') },
])

function visibilityLabel(visibility: string): string {
  return visibility === 'PROCESS' ? t('presetVisibilityProcess') : t('presetVisibilityPrivate')
}

const activeVars = computed(() => (mode.value === 'manual' ? manualVars.value : templateVars.value))
const missingAsk = computed(() => missingAskVariables(activeVars.value))

/**
 * WO-VT-1: невалидные строки блокируют применение (раньше плохой JSON не
 * давал сохранить инлайн-редактор). Черновые пустые строки — не ошибки.
 */
function rowsInvalid(rows: PresetVariable[]): boolean {
  if (duplicateVariableNames(rows).length) return true
  return rows.some((r) => {
    if (!r.name.trim() && r.value === '') return false
    return validatePresetRow({
      name: r.name,
      type: r.type,
      value: r.value,
      allowEmptyString: r.allowEmptyString,
    }) !== null
  })
}
const hasErrors = computed(() =>
  mode.value === 'manual' ? rowsInvalid(manualVars.value) : rowsInvalid(templateVars.value))
const canApply = computed(() => activeVars.value.length > 0 && missingAsk.value.length === 0 && !hasErrors.value)

function currentForSave(): PresetVariable[] {
  return activeVars.value.map((v) => ({ ...v }))
}

async function loadPresets() {
  loading.value = true
  loadError.value = null
  try {
    presets.value = await listPresets({
      key: props.processKey,
      kind: props.targetKind,
      ...(props.targetRef ? { ref: props.targetRef } : {}),
    })
  } catch (e) {
    if (isPresetsDisabled(e)) {
      available.value = false
      return
    }
    loadError.value = errorMessage(e, t('loadError'))
  } finally {
    loading.value = false
  }
}

async function onSelectPreset() {
  if (!selectedId.value) {
    templateVars.value = []
    emit('change', [])
    return
  }
  loading.value = true
  try {
    const full = await getPreset(selectedId.value)
    templateVars.value = (full.variables ?? []).map((v) => ({ ...v }))
    emit('change', templateVars.value)
  } catch (e) {
    loadError.value = errorMessage(e, t('loadError'))
  } finally {
    loading.value = false
  }
}

function onTemplateVarsChange(v: PresetVariable[]) {
  templateVars.value = v
  emit('change', v)
}

function onManualVarsChange(v: PresetVariable[]) {
  manualVars.value = v
  emit('change', v)
}

/** Переменные, готовые к отправке: плейсхолдеры развёрнуты. */
function getVariables(): PresetVariable[] {
  return applyPresetVariables(activeVars.value)
}

function onTemplateSaved(preset: VariablePreset) {
  showSaveDialog.value = false
  emit('templateSaved', preset)
  void loadPresets().then(() => {
    selectedId.value = preset.id
    void onSelectPreset()
  })
}

watch(mode, (m) => {
  if (m === 'template' && available.value) void loadPresets()
})

defineExpose({ getVariables, missingAsk, canApply, hasErrors, available, mode })
</script>

<template>
  <div v-if="available" class="space-y-3">
    <TabsBar
      :tabs="modeTabs"
      :active-id="mode"
      @update:active-id="mode = $event as 'manual' | 'template'"
    />
    <div v-if="mode === 'manual'">
      <VariablesEditor :model-value="manualVars" @update:model-value="onManualVarsChange" />
      <div class="mt-2 flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-border rounded-md hover:bg-muted disabled:opacity-50"
          :disabled="!manualVars.length"
          @click="showSaveDialog = true"
        >
          {{ t('presetSaveAsTemplate') }}
        </button>
        <p v-if="missingAsk.length" class="text-xs text-amber-600 dark:text-amber-400">
          {{ t('presetFillAskFields', { fields: missingAsk.join(', ') }) }}
        </p>
      </div>
    </div>
    <div v-else class="space-y-3">
      <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
      <p v-else-if="loadError" class="text-sm text-red-500">{{ loadError }}</p>
      <template v-else>
        <label for="preset-select" class="sr-only">{{ t('presetTemplateMode') }}</label>
        <select
          id="preset-select"
          v-model="selectedId"
          class="w-full px-2 py-1.5 border border-input rounded text-sm"
          @change="onSelectPreset"
        >
          <option value="">{{ t('presetChooseTemplate') }}</option>
          <option v-for="p in presets" :key="p.id" :value="p.id">
            {{ p.name }}{{ p.favorite ? t('presetFavoriteStar') : '' }} ({{ visibilityLabel(p.visibility) }})
          </option>
        </select>
        <p v-if="!presets.length" class="text-sm text-muted-foreground">{{ t('presetNoTemplates') }}</p>
        <VariablesEditor
          v-if="selectedId"
          :model-value="templateVars"
          @update:model-value="onTemplateVarsChange"
        />
        <div v-if="selectedId" class="flex flex-wrap items-center gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-border rounded-md hover:bg-muted disabled:opacity-50"
            :disabled="!templateVars.length"
            @click="showSaveDialog = true"
          >
            {{ t('presetSaveAsTemplate') }}
          </button>
          <p v-if="missingAsk.length" class="text-xs text-amber-600 dark:text-amber-400">
            {{ t('presetFillAskFields', { fields: missingAsk.join(', ') }) }}
          </p>
        </div>
      </template>
    </div>
    <PresetEditorDialog
      :open="showSaveDialog"
      :process-key="processKey"
      :target-kind="targetKind"
      :target-ref="targetRef ?? null"
      :initial-variables="currentForSave()"
      @close="showSaveDialog = false"
      @saved="onTemplateSaved"
    />
  </div>
</template>
