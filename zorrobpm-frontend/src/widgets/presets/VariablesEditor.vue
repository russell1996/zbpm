<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { PresetVariable } from '@/types/presets'
import {
  validatePresetRow,
  isAskAtLaunch,
  duplicateVariableNames,
  expandPlaceholders,
  variablesToRawJson,
  rawJsonToVariables,
  MAX_PRESET_VARIABLES,
} from '@/shared/lib/presetVariables'

/**
 * WO-VT-1 (фронт): общий редактор переменных. Таблица «имя / тип / значение»
 * с редактором по типу (LONG/DOUBLE — число, BOOLEAN — переключатель, UUID —
 * поле + генератор, JSON — textarea с валидацией и форматированием, STRING —
 * текст + «пустая строка допустима»), переключатель «Таблица | Сырой JSON»,
 * подсветка «спросить при запуске». Значения рендерятся текстом (XSS-safe:
 * только интерполяция, никакого v-html).
 */
const props = defineProps<{
  modelValue: PresetVariable[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: PresetVariable[]): void
}>()

const { t } = useI18n()

type Mode = 'table' | 'raw'
const mode = ref<Mode>('table')
const rawText = ref('')
const rawError = ref<string | null>(null)

const TYPES: PresetVariable['type'][] = ['STRING', 'UUID', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON']

const dupes = computed(() => new Set(duplicateVariableNames(props.modelValue)))

function rowError(i: number): string | null {
  const row = props.modelValue[i]
  const err = validatePresetRow({
    name: row.name,
    type: row.type,
    value: row.value,
    allowEmptyString: row.allowEmptyString,
  })
  if (!err) return null
  if (dupes.value.has(row.name.trim()) && row.name.trim()) return t('presetDuplicateName')
  return err.value ?? err.name ?? null
}

function patchRow(i: number, patch: Partial<PresetVariable>) {
  const next = props.modelValue.map((r, j) => (j === i ? { ...r, ...patch } : r))
  emit('update:modelValue', next)
}

function changeType(i: number, type: PresetVariable['type']) {
  const row = props.modelValue[i]
  // Смена типа сбрасывает значение и флаг: старый формат/флаг к новому типу
  // может не подходить (allowEmptyString — только STRING).
  const next: PresetVariable = { name: row.name, type, value: '', allowEmptyString: null }
  if (type === 'BOOLEAN' && row.value !== 'true' && row.value !== 'false') next.value = 'false'
  patchRow(i, next)
}

function addRow() {
  if (props.modelValue.length >= MAX_PRESET_VARIABLES) return
  emit('update:modelValue', [...props.modelValue, { name: '', type: 'STRING', value: '' }])
}

function removeRow(i: number) {
  emit('update:modelValue', props.modelValue.filter((_, j) => j !== i))
}

function generateUuid(i: number) {
  patchRow(i, { value: expandPlaceholders('{{uuid}}') })
}

// WO-ACL-11 criterion 11: сравнение-операнд 'true' внутри {{ }} флагит
// сканер непереведённых строк — метка тумблера вычисляется в script.
function booleanLabel(row: PresetVariable): string {
  return row.value === 'true' ? t('presetBooleanTrue') : t('presetBooleanFalse')
}

function formatJson(i: number) {  const row = props.modelValue[i]
  try {
    patchRow(i, { value: JSON.stringify(JSON.parse(row.value), null, 2) })
  } catch {
    // Ошибка уже показана валидатором строки — молча не глотаем, ничего не меняем.
  }
}

function switchToRaw() {
  rawText.value = variablesToRawJson(props.modelValue)
  rawError.value = null
  mode.value = 'raw'
}

function applyRaw() {
  const parsed = rawJsonToVariables(rawText.value)
  if (parsed.error) {
    rawError.value = parsed.error
    return
  }
  rawError.value = null
  emit('update:modelValue', parsed.variables ?? [])
  mode.value = 'table'
}
</script>

<template>
  <div class="space-y-3">
    <div class="flex items-center gap-1" role="tablist" :aria-label="t('presetEditorMode')">
      <button
        type="button"
        role="tab"
        :aria-selected="mode === 'table'"
        class="px-3 py-1 text-xs rounded-md border"
        :class="mode === 'table' ? 'bg-primary text-primary-foreground border-primary' : 'border-border hover:bg-muted'"
        @click="mode = 'table'"
      >
        {{ t('presetTableMode') }}
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="mode === 'raw'"
        class="px-3 py-1 text-xs rounded-md border"
        :class="mode === 'raw' ? 'bg-primary text-primary-foreground border-primary' : 'border-border hover:bg-muted'"
        @click="switchToRaw"
      >
        {{ t('presetRawMode') }}
      </button>
    </div>

    <div v-if="mode === 'table'" class="space-y-2">
      <div
        v-for="(row, i) in modelValue"
        :key="i"
        class="grid grid-cols-[1fr_6.5rem_1fr_auto] gap-2 items-start rounded-md border p-2"
        :class="isAskAtLaunch(row) && row.name.trim() ? 'border-amber-400 bg-amber-50/50 dark:bg-amber-950/20' : 'border-border'"
      >
        <div>
          <label :for="`pv-name-${i}`" class="sr-only">{{ t('presetVarName') }}</label>
          <input
            :id="`pv-name-${i}`"
            :value="row.name"
            :placeholder="t('presetVarName')"
            :disabled="disabled"
            class="w-full px-2 py-1 border border-input rounded text-sm font-mono"
            @input="patchRow(i, { name: ($event.target as HTMLInputElement).value })"
          />
        </div>
        <div>
          <label :for="`pv-type-${i}`" class="sr-only">{{ t('presetVarType') }}</label>
          <select
            :id="`pv-type-${i}`"
            :value="row.type"
            :disabled="disabled"
            class="w-full px-2 py-1 border border-input rounded text-sm font-mono"
            @change="changeType(i, ($event.target as HTMLSelectElement).value as PresetVariable['type'])"
          >
            <option v-for="tp in TYPES" :key="tp" :value="tp">{{ tp }}</option>
          </select>
        </div>
        <div class="space-y-1">
          <template v-if="row.type === 'BOOLEAN'">
            <label class="inline-flex items-center gap-2 text-sm">
              <input
                :id="`pv-value-${i}`"
                type="checkbox"
                :checked="row.value === 'true'"
                :disabled="disabled"
                class="h-4 w-4"
                @change="patchRow(i, { value: ($event.target as HTMLInputElement).checked ? 'true' : 'false' })"
              />
              <!-- WO-ACL-11 criterion 11: литералов 'true'/'false' в шаблоне нет. -->
              <span class="font-mono">{{ booleanLabel(row) }}</span>
            </label>
          </template>
          <template v-else-if="row.type === 'JSON'">
            <label :for="`pv-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</label>
            <textarea
              :id="`pv-value-${i}`"
              :value="row.value"
              :placeholder="t('presetJsonPlaceholder')"
              :disabled="disabled"
              rows="2"
              class="w-full px-2 py-1 border border-input rounded text-sm font-mono"
              :aria-invalid="rowError(i) ? 'true' : 'false'"
              @input="patchRow(i, { value: ($event.target as HTMLTextAreaElement).value })"
            />
            <button
              type="button"
              class="text-xs text-primary hover:underline"
              :disabled="disabled"
              @click="formatJson(i)"
            >
              {{ t('presetFormatJson') }}
            </button>
          </template>
          <template v-else-if="row.type === 'UUID'">
            <div class="flex gap-1">
              <label :for="`pv-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</label>
              <input
                :id="`pv-value-${i}`"
                :value="row.value"
                :placeholder="t('presetUuidPlaceholder')"
                :disabled="disabled"
                class="flex-1 min-w-0 px-2 py-1 border border-input rounded text-sm font-mono"
                :aria-invalid="rowError(i) ? 'true' : 'false'"
                @input="patchRow(i, { value: ($event.target as HTMLInputElement).value })"
              />
              <button
                type="button"
                class="px-2 py-1 text-xs border border-border rounded hover:bg-muted shrink-0"
                :disabled="disabled"
                @click="generateUuid(i)"
              >
                {{ t('presetGenerateUuid') }}
              </button>
            </div>
          </template>
          <template v-else>
            <label :for="`pv-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</label>
            <input
              :id="`pv-value-${i}`"
              :value="row.value"
              :type="row.type === 'LONG' || row.type === 'DOUBLE' ? 'number' : 'text'"
              :placeholder="t('presetValuePlaceholder')"
              :disabled="disabled"
              class="w-full px-2 py-1 border border-input rounded text-sm font-mono"
              :aria-invalid="rowError(i) ? 'true' : 'false'"
              @input="patchRow(i, { value: ($event.target as HTMLInputElement).value })"
            />
          </template>
          <label v-if="row.type === 'STRING'" class="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <input
              :id="`pv-empty-${i}`"
              type="checkbox"
              :checked="row.allowEmptyString === true"
              :disabled="disabled"
              class="h-3.5 w-3.5"
              @change="patchRow(i, { allowEmptyString: ($event.target as HTMLInputElement).checked })"
            />
            {{ t('presetAllowEmptyString') }}
          </label>
          <p v-if="isAskAtLaunch(row) && row.name.trim()" class="text-xs text-amber-600 dark:text-amber-400">
            {{ t('presetAskAtLaunch') }}
          </p>
          <p v-if="rowError(i)" role="alert" class="text-xs text-red-500">{{ rowError(i) }}</p>
        </div>
        <button
          type="button"
          class="px-2 py-1 text-xs text-red-500 hover:underline"
          :disabled="disabled"
          :aria-label="t('presetRemoveRow')"
          @click="removeRow(i)"
        >
          {{ t('remove') }}
        </button>
      </div>
      <p v-if="!modelValue.length" class="text-sm text-muted-foreground">{{ t('presetNoVariables') }}</p>
      <button
        type="button"
        class="w-full px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors disabled:opacity-50"
        :disabled="disabled || modelValue.length >= MAX_PRESET_VARIABLES"
        @click="addRow"
      >
        + {{ t('presetAddVariable') }}
      </button>
    </div>

    <div v-else class="space-y-2">
      <label for="pv-raw" class="sr-only">{{ t('presetRawMode') }}</label>
      <textarea
        id="pv-raw"
        v-model="rawText"
        rows="8"
        spellcheck="false"
        :disabled="disabled"
        class="w-full px-2 py-1 border border-input rounded text-sm font-mono"
      />
      <p v-if="rawError" role="alert" class="text-xs text-red-500">{{ rawError }}</p>
      <div class="flex justify-end gap-2">
        <button
          type="button"
          class="px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted"
          @click="mode = 'table'"
        >
          {{ t('cancel') }}
        </button>
        <button
          type="button"
          class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90"
          :disabled="disabled"
          @click="applyRaw"
        >
          {{ t('presetApplyRaw') }}
        </button>
      </div>
    </div>
  </div>
</template>
