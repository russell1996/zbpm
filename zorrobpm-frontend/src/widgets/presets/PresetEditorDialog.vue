<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VariablesEditor from './VariablesEditor.vue'
import type {
  PresetTargetKind,
  PresetVariable,
  PresetVisibility,
  VariablePreset,
  PresetHistoryEntry,
} from '@/types/presets'
import {
  createPreset,
  updatePreset,
  deletePreset,
  getPreset,
  getPresetHistory,
  setPresetFavorite,
  changePresetVisibility,
  isPresetConflict,
  presetErrorCode,
} from '@/services/presetService'
import { validatePresetRow, duplicateVariableNames } from '@/shared/lib/presetVariables'
import { errorMessage } from '@/shared/lib/utils'
import { useToast } from '@/composables/useToast'

/**
 * WO-VT-1 (фронт, §1-бис п.2-бис): создание/правка шаблона. Правка — через PUT
 * с version; конфликт версии показывает понятное сообщение и предлагает
 * перезагрузить актуальную версию. Здесь же: избранное, смена видимости,
 * удаление с подтверждением, история изменений, дублирование (именем).
 */
const props = defineProps<{
  open: boolean
  processKey: string
  targetKind: PresetTargetKind
  targetRef?: string | null
  initialVariables?: PresetVariable[]
  presetId?: string | null
  duplicateName?: string | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved', preset: VariablePreset): void
  (e: 'deleted', id: string): void
}>()

const { t } = useI18n()
const toast = useToast()

const name = ref('')
const description = ref('')
const visibility = ref<PresetVisibility>('PRIVATE')
const variables = ref<PresetVariable[]>([])
const version = ref(0)
const favorite = ref(false)
const saving = ref(false)
const saveError = ref<string | null>(null)
const conflict = ref(false)
const loading = ref(false)
const confirmDelete = ref(false)
const view = ref<'edit' | 'history'>('edit')
const history = ref<PresetHistoryEntry[]>([])
const historyLoading = ref(false)

const isEdit = computed(() => !!props.presetId)
const titleKey = computed(() => {
  if (!isEdit.value) return 'presetCreateTitle'
  return view.value === 'history' ? 'presetHistoryTitle' : 'presetEditTitle'
})

// WO-ACL-11 criterion 11: литерала 'PRIVATE' в шаблоне нет — метка в script.
const shareLabel = computed(() =>
  visibility.value === 'PRIVATE' ? t('presetShareWithProcess') : t('presetMakePrivate'))

const formError = computed((): string | null => {
  if (!name.value.trim()) return t('presetNameRequired')
  const rows = variables.value
  if (rows.length > 100) return t('presetTooManyVariables')
  if (duplicateVariableNames(rows).length) return t('presetDuplicateName')
  for (const r of rows) {
    // Черновые пустые строки пропускаются при сохранении (как в редакторе).
    if (!r.name.trim() && r.value === '') continue
    const err = validatePresetRow({
      name: r.name,
      type: r.type,
      value: r.value,
      allowEmptyString: r.allowEmptyString,
    })
    if (err) return err.value ?? err.name ?? t('presetRowInvalid')
  }
  return null
})

function cleanVariables(): PresetVariable[] {
  // Черновые пустые строки в шаблон не сохраняются.
  return variables.value
    .filter((r) => r.name.trim() || r.value !== '')
    .map((r) => ({
      name: r.name.trim(),
      type: r.type,
      value: r.value,
      ...(r.allowEmptyString === true ? { allowEmptyString: true as const } : {}),
    }))
}

async function loadForEdit() {
  if (!props.presetId) return
  loading.value = true
  try {
    const p = await getPreset(props.presetId)
    name.value = props.duplicateName ?? p.name
    description.value = p.description ?? ''
    visibility.value = p.visibility
    variables.value = (p.variables ?? []).map((x) => ({ ...x }))
    version.value = p.version
    favorite.value = p.favorite
  } catch (e) {
    saveError.value = errorMessage(e, t('loadError'))
  } finally {
    loading.value = false
  }
}

function resetForCreate() {
  name.value = props.duplicateName ?? ''
  description.value = ''
  visibility.value = 'PRIVATE'
  variables.value = (props.initialVariables ?? []).map((x) => ({ ...x }))
  version.value = 0
  favorite.value = false
  saveError.value = null
  conflict.value = false
  confirmDelete.value = false
  view.value = 'edit'
}

watch(
  () => props.open,
  (open) => {
    if (!open) return
    resetForCreate()
    if (props.presetId && !props.duplicateName) void loadForEdit()
  },
  { immediate: true },
)

async function save() {
  if (formError.value || saving.value) return
  saving.value = true
  saveError.value = null
  conflict.value = false
  try {
    let saved: VariablePreset
    if (isEdit.value && !props.duplicateName) {
      saved = await updatePreset(props.presetId!, {
        name: name.value.trim(),
        description: description.value || null,
        variables: cleanVariables(),
        visibility: visibility.value,
        version: version.value,
      })
    } else {
      saved = await createPreset({
        processDefinitionKey: props.processKey,
        targetKind: props.targetKind,
        targetRef: props.targetRef ?? null,
        name: name.value.trim(),
        description: description.value || null,
        variables: cleanVariables(),
        visibility: visibility.value,
      })
    }
    toast.success(t('presetSaved'))
    emit('saved', saved)
  } catch (e) {
    if (isPresetConflict(e)) {
      // 409: дубль имени либо stale-версия. Различаем по коду бэкенда.
      const code = presetErrorCode(e)
      if (code === 'PRESET_CONFLICT' && isEdit.value) {
        conflict.value = true
        saveError.value = t('presetVersionConflict')
      } else {
        saveError.value = t('presetNameConflict')
      }
    } else {
      saveError.value = errorMessage(e, t('presetSaveFailed'))
    }
  } finally {
    saving.value = false
  }
}

async function reloadLatest() {
  if (!props.presetId) return
  conflict.value = false
  saveError.value = null
  await loadForEdit()
}

async function remove() {
  if (!props.presetId) return
  if (!confirmDelete.value) {
    confirmDelete.value = true
    return
  }
  try {
    await deletePreset(props.presetId)
    toast.success(t('presetDeleted'))
    emit('deleted', props.presetId)
    emit('close')
  } catch (e) {
    saveError.value = errorMessage(e, t('presetDeleteFailed'))
  }
}

async function toggleFavorite() {
  if (!props.presetId) return
  try {
    await setPresetFavorite(props.presetId, !favorite.value)
    favorite.value = !favorite.value
  } catch (e) {
    toast.error(errorMessage(e, t('presetFavoriteFailed')))
  }
}

async function toggleVisibility() {
  if (!props.presetId) return
  const next: PresetVisibility = visibility.value === 'PRIVATE' ? 'PROCESS' : 'PRIVATE'
  try {
    const saved = await changePresetVisibility(props.presetId, next)
    visibility.value = saved.visibility
    version.value = saved.version
    toast.success(t('presetSaved'))
  } catch (e) {
    if (isPresetConflict(e)) {
      conflict.value = true
      saveError.value = t('presetVersionConflict')
    } else {
      saveError.value = errorMessage(e, t('presetSaveFailed'))
    }
  }
}

async function openHistory() {
  if (!props.presetId) return
  view.value = 'history'
  historyLoading.value = true
  try {
    history.value = await getPresetHistory(props.presetId)
  } catch (e) {
    saveError.value = errorMessage(e, t('loadError'))
  } finally {
    historyLoading.value = false
  }
}

function historyJson(vars: PresetVariable[] | null): string {
  if (!vars) return '—'
  return JSON.stringify(vars, null, 2)
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
    @click.self="emit('close')"
  >
    <div
      class="bg-card rounded-lg shadow-lg w-full max-w-2xl p-6 space-y-4 max-h-[90vh] overflow-y-auto"
      role="dialog"
      aria-modal="true"
      :aria-label="t(titleKey)"
    >
      <div class="flex items-center justify-between">
        <h2 class="text-lg font-bold">{{ t(titleKey) }}</h2>
        <div class="flex items-center gap-2">
          <button
            v-if="isEdit && !duplicateName"
            type="button"
            class="text-xl leading-none px-1"
            :aria-label="t('presetFavorite')"
            :aria-pressed="favorite ? 'true' : 'false'"
            :title="t('presetFavorite')"
            @click="toggleFavorite"
          >
            {{ favorite ? '★' : '☆' }}
          </button>
          <button
            type="button"
            class="text-sm text-muted-foreground hover:text-foreground"
            @click="emit('close')"
          >
            {{ t('close') }}
          </button>
        </div>
      </div>

      <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

      <template v-else-if="view === 'history'">
        <div v-if="historyLoading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
        <div v-else-if="!history.length" class="text-sm text-muted-foreground">{{ t('presetHistoryEmpty') }}</div>
        <ol v-else class="space-y-3">
          <li v-for="h in history" :key="h.id" class="border border-border rounded-md p-3 space-y-2">
            <div class="flex items-center gap-2 text-xs text-muted-foreground flex-wrap">
              <span class="font-semibold text-foreground">{{ h.action }}</span>
              <span class="font-mono">{{ h.at }}</span>
            </div>
            <div class="grid grid-cols-2 gap-2 text-xs">
              <div>
                <div class="font-semibold mb-1">{{ t('presetHistoryBefore') }}</div>
                <pre class="font-mono bg-muted rounded p-2 overflow-x-auto whitespace-pre-wrap break-all">{{ historyJson(h.variablesBefore) }}</pre>
              </div>
              <div>
                <div class="font-semibold mb-1">{{ t('presetHistoryAfter') }}</div>
                <pre class="font-mono bg-muted rounded p-2 overflow-x-auto whitespace-pre-wrap break-all">{{ historyJson(h.variablesAfter) }}</pre>
              </div>
            </div>
          </li>
        </ol>
        <div class="flex justify-start">
          <button
            type="button"
            class="px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted"
            @click="view = 'edit'"
          >
            {{ t('presetBackToEdit') }}
          </button>
        </div>
      </template>

      <template v-else>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label for="preset-name" class="block text-xs font-medium mb-1">{{ t('presetName') }}</label>
            <input
              id="preset-name"
              v-model="name"
              class="w-full px-2 py-1.5 border border-input rounded text-sm"
              maxlength="255"
            />
          </div>
          <div>
            <label for="preset-visibility" class="block text-xs font-medium mb-1">{{ t('presetVisibility') }}</label>
            <select id="preset-visibility" v-model="visibility" class="w-full px-2 py-1.5 border border-input rounded text-sm">
              <option value="PRIVATE">{{ t('presetVisibilityPrivate') }}</option>
              <option value="PROCESS">{{ t('presetVisibilityProcess') }}</option>
            </select>
          </div>
        </div>
        <div>
          <label for="preset-desc" class="block text-xs font-medium mb-1">{{ t('presetDescription') }}</label>
          <input
            id="preset-desc"
            v-model="description"
            class="w-full px-2 py-1.5 border border-input rounded text-sm"
          />
        </div>
        <div class="text-xs text-muted-foreground font-mono">
          {{ targetKind }}{{ targetRef ? ` · ${targetRef}` : '' }}
        </div>

        <VariablesEditor v-model="variables" />

        <div v-if="conflict" class="border border-amber-400 rounded-md p-3 bg-amber-50/50 dark:bg-amber-950/20 space-y-2">
          <p role="alert" class="text-sm text-amber-700 dark:text-amber-300">{{ t('presetVersionConflict') }}</p>
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-border rounded-md hover:bg-muted"
            @click="reloadLatest"
          >
            {{ t('presetReloadLatest') }}
          </button>
        </div>
        <p v-if="saveError && !conflict" role="alert" class="text-sm text-red-500">{{ saveError }}</p>
        <p v-else-if="formError" class="text-xs text-muted-foreground">{{ formError }}</p>

        <div class="flex items-center justify-between pt-2">
          <div>
            <button
              v-if="isEdit && !duplicateName"
              type="button"
              class="px-3 py-1.5 text-xs text-red-500 hover:underline"
              @click="remove"
            >
              {{ confirmDelete ? t('presetConfirmDelete') : t('presetDelete') }}
            </button>
            <button
              v-if="isEdit && !duplicateName"
              type="button"
              class="px-3 py-1.5 text-xs text-primary hover:underline"
              @click="openHistory"
            >
              {{ t('presetHistory') }}
            </button>
          </div>
          <div class="flex justify-end gap-2">
            <button
              v-if="isEdit && !duplicateName"
              type="button"
              class="px-3 py-1.5 text-xs border border-border rounded-md hover:bg-muted"
              @click="toggleVisibility"
            >
              {{ shareLabel }}
            </button>
            <button
              type="button"
              class="px-4 py-1.5 text-sm border border-border rounded-md hover:bg-muted"
              @click="emit('close')"
            >
              {{ t('cancel') }}
            </button>
            <button
              type="button"
              class="px-4 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 disabled:opacity-50"
              :disabled="!!formError || saving"
              @click="save"
            >
              {{ saving ? t('loading') : t('save') }}
            </button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
