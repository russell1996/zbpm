<script setup lang="ts">
import { computed, nextTick, ref, toRef, watch } from 'vue'
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
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Dialog,
  DialogContent,
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
import { validatePresetRow, duplicateVariableNames } from '@/shared/lib/presetVariables'
import { errorMessage } from '@/shared/lib/utils'
import { useToast } from '@/composables/useToast'
import { onCtrlEnter } from '@/composables/usePresetModal'

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

// WO-UI-27 доп.3: shadcn-Dialog даёт focus-trap + Esc + scroll-lock + aria
// из коробки (вместо usePresetModal). Ctrl+Enter = сохранить (RT-2) —
// onDialogKeydown ниже. Esc внутри JSON-фулскрина перехватывает сам
// фулскрин (RT-1): DialogContent слушает escape только вне [data-preset-fs].
function requestClose() {
  emit('close')
}

/**
 * WO-UI-27 + сохранение RT-1: Esc в shadcn-Dialog. DialogContent эмитит
 * escape-key-down; если фокус внутри JSON-фулскрина ([data-preset-fs]) —
 * это Esc-возврат черновика (глушим, диалог не закрываем), иначе — закрыть.
 */
function onDialogEscape(e: Event) {
  const ae = document.activeElement as HTMLElement | null
  if (ae?.closest?.('[data-preset-fs]')) {
    e.preventDefault()
  } else {
    requestClose()
  }
}

// WO-VT-3 HOLD r1 (RT-2): доступ к commitFullscreen() редактора.
const variablesEditor = ref<InstanceType<typeof VariablesEditor> | null>(null)

function onDialogKeydown(e: KeyboardEvent) {
  onCtrlEnter(e, () => {
    void (async () => {
      // WO-VT-3 HOLD r1 (RT-2): если открыт JSON-фулскрин — сначала применяем
      // набранное в variables (иначе save уйдёт со старым значением и правки
      // молча выброшены). Невалидный JSON — стоим (фулскрин открыт с ошибкой).
      if (variablesEditor.value && !variablesEditor.value.commitFullscreen()) return
      // patchRow идёт через emit дочернего редактора — ждём, пока v-model
      // синхронизируется (один nextTick гоняемо недостаточен: emit → prop →
      // ref родителя требует полного цикла обновления).
      await nextTick()
      await nextTick()
      await nextTick()
      if (!formError.value && !saving.value) void save()
    })()
  })
}

function historyActionLabel(action: string): string {
  const key = `presetHistoryAction_${action}`
  const v = t(key)
  // Нет ключа для нового действия сервера — показываем сырое (честно);
  // localeKeys-сканер это место покрывает через presetHistoryAction_*.
  return v === key ? action : v
}

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
  // WO-VT-3 Дополнение №2 п.4: «Отменить» в тосте — пересоздаём удалённое
  // теми же данными (данные уже загружены в форму правки).
  const backup = {
    processDefinitionKey: props.processKey,
    targetKind: props.targetKind,
    targetRef: props.targetRef ?? null,
    name: name.value.trim(),
    description: description.value || null,
    visibility: visibility.value,
    variables: cleanVariables(),
  }
  const deletedId = props.presetId
  try {
    await deletePreset(deletedId)
    toast.success(t('presetDeleted'), {
      action: {
        label: t('presetUndo'),
        onClick: () => {
          void createPreset(backup)
            .then((saved) => emit('saved', saved))
            .catch((e: unknown) => toast.error(errorMessage(e, t('presetSaveFailed'))))
        },
      },
    })
    emit('deleted', deletedId)
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
  <!-- WO-UI-27 доп.3: shadcn-Dialog (trap/Esc/scroll-lock/aria из коробки).
       WO-UI-27 п.3: ширина min(94vw,1280px) вместо 960px. Esc внутри
       JSON-фулскрина — Esc-возврат (RT-1): DialogContent ниже глушит
       escape-key-down, когда фокус в [data-preset-fs]. -->
  <Dialog :open="open" @update:open="(v) => { if (!v) requestClose() }">
    <DialogContent
      class="flex max-h-[90dvh] flex-col gap-0 overflow-hidden p-0"
      style="width: min(94vw, 1280px); max-width: min(94vw, 1280px);"
      :aria-label="t(titleKey)"
      @keydown="onDialogKeydown"
      @escape-key-down="onDialogEscape"
      @pointer-down-outside="(e) => e.preventDefault()"
      @interact-outside="(e) => e.preventDefault()"
    >
      <DialogHeader class="flex-row items-center justify-between gap-2 space-y-0 border-b border-border px-4 py-3">
        <DialogTitle class="truncate text-lg font-bold">{{ t(titleKey) }}</DialogTitle>
        <div class="flex shrink-0 items-center gap-2">
          <Button
            v-if="isEdit && !duplicateName"
            variant="ghost"
            size="icon"
            class="text-xl leading-none"
            :aria-label="t('presetFavorite')"
            :aria-pressed="favorite ? 'true' : 'false'"
            :title="t('presetFavorite')"
            @click="toggleFavorite"
          >
            {{ favorite ? '★' : '☆' }}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            class="text-sm text-muted-foreground"
            @click="requestClose"
          >
            {{ t('close') }}
          </Button>
        </div>
      </DialogHeader>

      <div class="min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
      <div v-if="loading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>

      <template v-else-if="view === 'history'">
        <div v-if="historyLoading" class="text-sm text-muted-foreground">{{ t('loading') }}</div>
        <div v-else-if="!history.length" class="text-sm text-muted-foreground">{{ t('presetHistoryEmpty') }}</div>
        <ol v-else class="space-y-3">
          <li v-for="h in history" :key="h.id" class="border border-border rounded-md p-3 space-y-2">
            <div class="flex items-center gap-2 text-xs text-muted-foreground flex-wrap">
              <span class="font-semibold text-foreground">{{ historyActionLabel(h.action) }}</span>
              <span class="font-mono">{{ h.at }}</span>
            </div>
            <div class="grid grid-cols-1 min-[560px]:grid-cols-2 gap-2 text-xs">
              <div class="min-w-0">
                <div class="font-semibold mb-1">{{ t('presetHistoryBefore') }}</div>
                <pre class="font-mono bg-muted rounded p-2 overflow-auto whitespace-pre-wrap break-all max-h-48">{{ historyJson(h.variablesBefore) }}</pre>
              </div>
              <div class="min-w-0">
                <div class="font-semibold mb-1">{{ t('presetHistoryAfter') }}</div>
                <pre class="font-mono bg-muted rounded p-2 overflow-auto whitespace-pre-wrap break-all max-h-48">{{ historyJson(h.variablesAfter) }}</pre>
              </div>
            </div>
          </li>
        </ol>
        <div class="flex justify-start">
          <Button
            type="button"
            variant="outline"
            size="sm"
            @click="view = 'edit'"
          >
            {{ t('presetBackToEdit') }}
          </Button>
        </div>
      </template>

      <template v-else>
        <div class="grid grid-cols-1 gap-3 min-[560px]:grid-cols-2">
          <div class="min-w-0">
            <Label for="preset-name" class="mb-1 block">{{ t('presetName') }}</Label>
            <Input
              id="preset-name"
              v-model="name"
              class="h-9 w-full text-sm"
              :maxlength="255"
            />
          </div>
          <div class="min-w-0">
            <Label for="preset-visibility" class="mb-1 block">{{ t('presetVisibility') }}</Label>
            <Select v-model="visibility">
              <SelectTrigger id="preset-visibility" class="h-9 w-full text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="PRIVATE">{{ t('presetVisibilityPrivate') }}</SelectItem>
                <SelectItem value="PROCESS">{{ t('presetVisibilityProcess') }}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
        <div>
          <Label for="preset-desc" class="mb-1 block">{{ t('presetDescription') }}</Label>
          <Input
            id="preset-desc"
            v-model="description"
            class="h-9 w-full text-sm"
          />
        </div>
        <div class="font-mono text-xs text-muted-foreground">
          {{ targetKind }}{{ targetRef ? ` · ${targetRef}` : '' }}
        </div>

        <VariablesEditor ref="variablesEditor" v-model="variables" id-prefix="pe" />

        <div v-if="conflict" class="space-y-2 rounded-md border border-amber-400 bg-amber-50/50 p-3 dark:bg-amber-950/20">
          <p role="alert" class="text-sm text-amber-700 dark:text-amber-300">{{ t('presetVersionConflict') }}</p>
          <Button
            type="button"
            variant="outline"
            size="sm"
            class="text-xs"
            @click="reloadLatest"
          >
            {{ t('presetReloadLatest') }}
          </Button>
        </div>
        <p v-if="saveError && !conflict" role="alert" class="text-sm text-red-500">{{ saveError }}</p>
        <p v-else-if="formError" class="text-xs text-muted-foreground">{{ formError }}</p>
      </template>
      </div>

      <DialogFooter class="flex-row flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-3">
          <div class="flex flex-wrap gap-2">
            <Button
              v-if="isEdit && !duplicateName"
              type="button"
              variant="link"
              size="sm"
              class="px-0 text-xs text-red-500"
              @click="remove"
            >
              {{ confirmDelete ? t('presetConfirmDelete') : t('presetDelete') }}
            </Button>
            <Button
              v-if="isEdit && !duplicateName && view !== 'history'"
              type="button"
              variant="link"
              size="sm"
              class="px-0 text-xs"
              @click="openHistory"
            >
              {{ t('presetHistory') }}
            </Button>
          </div>
          <div class="flex flex-wrap justify-end gap-2">
            <Button
              v-if="isEdit && !duplicateName"
              type="button"
              variant="outline"
              size="sm"
              class="text-xs"
              @click="toggleVisibility"
            >
              {{ shareLabel }}
            </Button>
            <Button
              type="button"
              variant="outline"
              @click="requestClose"
            >
              {{ t('cancel') }}
            </Button>
            <Button
              type="button"
              :disabled="!!formError || saving"
              @click="save"
            >
              {{ saving ? t('loading') : t('save') }}
            </Button>
          </div>
        </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
