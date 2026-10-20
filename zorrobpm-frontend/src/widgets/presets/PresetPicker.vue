<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ChevronDown } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Combobox,
  ComboboxAnchor,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
  ComboboxTrigger,
} from '@/components/ui/combobox'
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
import { useToast } from '@/composables/useToast'

/**
 * WO-UI-34: одна форма без вкладок. Сверху строка «Шаблон»: shadcn Select
 * (при >8 шаблонов — shadcn Combobox с поиском); первый пункт — «Без шаблона
 * — ввести вручную» (по умолчанию). Ниже ОДИН VariablesEditor: выбор
 * шаблона заполняет тот же редактор (строки правятся, как ручной ввод).
 * Замена уже введённых строк — без тихой потери: тихий тост
 * «Шаблон применён · Отменить» (undo возвращает прежние строки).
 * WO-UI-38: выбор «Без шаблона» — НЕ потеря данных (строки остаются ручным
 * вводом), поэтому тоста/«Отменить» для этого сценария нет; имя шаблона и
 * его видимость показываются РАЗДЕЛЬНО (« · »), а не склеенными.
 * При выключенном флаге (available=false) список «Шаблон» не показывается
 * вообще — только редактор (WO п.3).
 * Контракт для потребителей (8 страниц) сохранён: getVariables/missingAsk/
 * canApply/hasErrors/available/mode, события change/templateSaved.
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
const toast = useToast()

const vars = ref<PresetVariable[]>([])
const presets = ref<VariablePreset[]>([])
/** '' = «Без шаблона» (ручной ввод); иначе id выбранного шаблона. */
const selectedId = ref<string>('')
/** WO-UI-34: запрос поиска Combobox. Пункт «Без шаблона» — режим, не
 *  шаблон: во время поиска (когда ищут ИМЯ шаблона) он скрыт, чтобы не
 *  мешать фильтру; пустой запрос — пункт на месте, как по умолчанию. */
const searchQuery = ref('')
const loading = ref(false)
const loadError = ref<string | null>(null)
const available = ref(true)
const showSaveDialog = ref(false)

/** WO-UI-34: >8 шаблонов — Combobox с поиском, иначе plain Select. */
const useCombobox = computed(() => presets.value.length > 8)

/**
 * reka-ui не принимает пустую строку как value SelectItem/ComboboxItem
 * (нормализует в null → краш). Для пункта «Без шаблона» — sentinel:
 * наружу (selectedId/getVariables) уходит '' как и раньше.
 */
const NONE_VALUE = '__none__'

/** Контракт: 'manual' | 'template' (эквивалент прежней mode). */
const mode = computed(() => (selectedId.value ? 'template' : 'manual'))

// WO-VT-3 (п.6): черновая пустая строка {name:'', value:''} — не «спросить».
const missingAsk = computed(() => missingAskVariables(vars.value).filter((n) => n.trim() !== ''))

/** WO-VT-1: невалидные строки блокируют применение. Черновые пустые — не ошибки. */
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
const hasErrors = computed(() => rowsInvalid(vars.value))
const canApply = computed(() => vars.value.length > 0 && missingAsk.value.length === 0 && !hasErrors.value)

function visibilityLabel(visibility: string): string {
  return visibility === 'PROCESS' ? t('presetVisibilityProcess') : t('presetVisibilityPrivate')
}

/**
 * WO-UI-38: подпись шаблона — ДВА фрагмента (имя и видимость), а не один
 * склеенный текст. reka-ui `SelectValue` рендерит `textContent` выбранного
 * пункта (node_modules/reka-ui/dist/Select/SelectValue.js), поэтому раньше в
 * закрытом поле было «Start ApprovalУчастники процесса» — без разделителя.
 * Теперь фрагменты разделены « · », видимость — мелким приглушённым шрифтом
 * (как в ElementPresetsPanel), имя — обрезается с многоточием, полный текст —
 * по наведению (title на триггере).
 */
function presetNameLabel(p: VariablePreset): string {
  return `${p.name}${p.favorite ? t('presetFavoriteStar') : ''}`
}

function presetVisibilityText(p: VariablePreset): string {
  return visibilityLabel(p.visibility)
}

/** Полный текст подписи (разделитель « · ») — для title и для Combobox. */
function presetFullLabel(p: VariablePreset): string {
  return `${presetNameLabel(p)} · ${presetVisibilityText(p)}`
}

/** Выбранный шаблон из списка — источник подписи закрытого значения. */
const selectedPreset = computed<VariablePreset | null>(
  () => presets.value.find((p) => p.id === selectedId.value) ?? null,
)

const editorRef = useTemplateRef<InstanceType<typeof VariablesEditor> | null>('editor')

/** WO-VT-3 (критерий 6): ссылка на поле из причины — кликом фокус на поле. */
function focusAskField(name: string) {
  const idx = vars.value.findIndex((v) => v.name === name)
  if (idx < 0) return
  editorRef.value?.focusField(idx)
  // В компакте поле может быть скрыто фильтром/сворачиванием — ищем по id.
  void nextTick().then(() => {
    const el = document.querySelector(`#pv-name-${idx}, #pv-value-${idx}`) as HTMLElement | null
    el?.focus()
  })
}

function deepCloneRows(rows: PresetVariable[]): PresetVariable[] {
  return rows.map((v) => ({ ...v }))
}

/**
 * WO-UI-34 п.2: замена непустых строк никогда не молча теряет их — тихий
 * тост с действием «Отменить» (undo возвращает прежние строки и выбор).
 */
function offerUndo(snapshot: { vars: PresetVariable[]; selectedId: string }, message: string) {
  toast.info(message, {
    duration: 6000,
    action: {
      label: t('presetUndo'),
      onClick: () => {
        vars.value = snapshot.vars
        selectedId.value = snapshot.selectedId
        emit('change', vars.value)
      },
    },
  })
}

/** Смена пункта «Шаблон»: '' — ручной ввод, id — применить шаблон.
 *  reka AcceptableValue = string|number|bigint|Record|null; у нас — только
 *  строки (sentinel и preset id), остальное нормализуется в id-строку. */
function onSelect(value: unknown) {
  const raw = String(value ?? '')
  const id = raw === NONE_VALUE ? '' : raw
  if (id === selectedId.value) return
  if (id === '') {
    // WO-UI-38: «Без шаблона» — это НЕ потеря данных. Пользователь сам
    // выбрал ручной ввод, поэтому строки остаются (как ручные) и тост
    // «Шаблон снят · Отменить» не показывается: шум без причины.
    selectedId.value = ''
    emit('change', vars.value)
    return
  }
  const prev = selectedId.value
  loading.value = true
  loadError.value = null
  void (async () => {
    try {
      const full = await getPreset(id)
      if (vars.value.length) {
        offerUndo({ vars: deepCloneRows(vars.value), selectedId: prev }, t('presetTemplateApplied'))
      }
      vars.value = (full.variables ?? []).map((v) => ({ ...v }))
      selectedId.value = id
      emit('change', vars.value)
    } catch (e) {
      loadError.value = errorMessage(e, t('loadError'))
    } finally {
      loading.value = false
    }
  })()
}

function onVarsChange(v: PresetVariable[]) {
  vars.value = v
  emit('change', v)
}

/** Переменные, готовые к отправке: плейсхолдеры развёрнуты. */
function getVariables(): PresetVariable[] {
  return applyPresetVariables(vars.value)
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

function onTemplateSaved(preset: VariablePreset) {
  showSaveDialog.value = false
  emit('templateSaved', preset)
  void loadPresets().then(() => {
    if (!presets.value.some((p) => p.id === preset.id)) {
      presets.value = [...presets.value, preset]
    }
    selectedId.value = preset.id
    // Сохранённый шаблон = копия текущих строк из диалога: подставляем его
    // переменные без повторного запроса.
    vars.value = (preset.variables ?? []).map((v) => ({ ...v }))
    emit('change', vars.value)
  })
}

// WO-UI-34: список «Шаблон» всегда в форме — грузим при монтировании.
onMounted(() => {
  if (available.value) void loadPresets()
})

// WO-VT-3 (A-NEW-2): список привязан к ref. При смене ref перезагружаем
// СПИСОК; выбранный шаблон сбрасываем, только если его нет в новом списке.
watch(
  () => [props.processKey, props.targetKind, props.targetRef],
  async () => {
    if (!available.value) return
    const prevSelected = selectedId.value
    await loadPresets()
    if (prevSelected && !presets.value.some((p) => p.id === prevSelected)) {
      selectedId.value = ''
      vars.value = []
      emit('change', [])
    }
  },
)

defineExpose({ getVariables, missingAsk, canApply, hasErrors, available, mode })
</script>

<template>
  <!-- WO-UI-34: строка «Шаблон» + один редактор. available=false — без
       списка, только редактор (WO п.3). -->
  <div class="space-y-3">
    <div v-if="available && loading && !presets.length" class="flex items-center gap-2">
      <Label for="preset-select" class="shrink-0 text-sm">{{ t('presetTemplateLabel') }}</Label>
      <span class="text-sm text-muted-foreground">{{ t('loading') }}</span>
    </div>
    <template v-else-if="available">
      <div class="flex flex-wrap items-center gap-x-2 gap-y-1" data-testid="preset-template-row">
        <Label for="preset-select" class="shrink-0 text-sm">{{ t('presetTemplateLabel') }}</Label>
        <Select
          v-if="!useCombobox"
          :model-value="selectedId || NONE_VALUE"
          @update:model-value="onSelect($event)"
        >
          <!-- WO-UI-38: закрытое значение — имя и видимость РАЗДЕЛЬНО (« · »).
               Свой слот вместо reka-авто-текста (SelectValue рендерит
               textContent пункта — было «Start ApprovalУчастники процесса»).
               title на триггере — полный текст по наведению. -->
          <SelectTrigger
            id="preset-select"
            data-testid="preset-select"
            class="h-9 min-w-56 flex-1 text-sm"
            :title="selectedPreset ? presetFullLabel(selectedPreset) : t('presetNoTemplate')"
          >
            <SelectValue :placeholder="t('presetNoTemplate')">
              <span
                v-if="selectedPreset"
                class="flex min-w-0 items-center gap-1.5"
                data-testid="preset-selected-label"
              >
                <span class="truncate">{{ presetNameLabel(selectedPreset) }}</span>
                <span aria-hidden="true" class="shrink-0 text-muted-foreground"> · </span>
                <span class="shrink-0 text-[11px] text-muted-foreground">
                  {{ presetVisibilityText(selectedPreset) }}
                </span>
              </span>
              <span v-else class="truncate">{{ t('presetNoTemplate') }}</span>
            </SelectValue>
          </SelectTrigger>
          <SelectContent>
            <SelectItem :value="NONE_VALUE" data-testid="preset-opt-manual">{{ t('presetNoTemplate') }}</SelectItem>
            <SelectItem
              v-for="p in presets"
              :key="p.id"
              :value="p.id"
              :data-testid="`preset-opt-${p.id}`"
              class="gap-2"
            >
              <span class="flex min-w-0 items-center gap-1.5">
                <span class="truncate">{{ presetNameLabel(p) }}</span>
                <span aria-hidden="true" class="shrink-0 text-muted-foreground"> · </span>
                <span class="shrink-0 text-[11px] text-muted-foreground">
                  {{ presetVisibilityText(p) }}
                </span>
              </span>
            </SelectItem>
          </SelectContent>
        </Select>
        <Combobox
          v-else
          :model-value="selectedId || NONE_VALUE"
          @update:model-value="onSelect($event)"
        >
          <ComboboxAnchor>
            <ComboboxTrigger
              id="preset-select"
              data-testid="preset-select"
              class="flex h-9 min-w-56 flex-1 items-center justify-between gap-2 rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
              :title="selectedPreset ? presetFullLabel(selectedPreset) : t('presetNoTemplate')"
            >
              <!-- WO-UI-38: то же разделение, что и в Select. -->
              <span
                v-if="selectedPreset"
                class="flex min-w-0 items-center gap-1.5"
                data-testid="preset-selected-label"
              >
                <span class="truncate">{{ presetNameLabel(selectedPreset) }}</span>
                <span aria-hidden="true" class="shrink-0 text-muted-foreground"> · </span>
                <span class="shrink-0 text-[11px] text-muted-foreground">
                  {{ presetVisibilityText(selectedPreset) }}
                </span>
              </span>
              <span v-else class="truncate">{{ t('presetNoTemplate') }}</span>
              <ChevronDown class="h-4 w-4 shrink-0 opacity-50" />
            </ComboboxTrigger>
          </ComboboxAnchor>
          <ComboboxList class="w-(--reka-combobox-trigger-width) min-w-56">
            <ComboboxInput
              class="mb-1 w-full rounded-md border border-input bg-transparent px-2 py-1.5 text-sm outline-none placeholder:text-muted-foreground"
              :placeholder="t('presetSearchTemplates')"
              @input="searchQuery = ($event.target as HTMLInputElement).value"
            />
            <!-- «Без шаблона» — режим, не шаблон: при поиске по имени скрыт. -->
            <ComboboxItem v-if="!searchQuery" :value="NONE_VALUE" data-testid="preset-opt-manual">{{ t('presetNoTemplate') }}</ComboboxItem>
            <ComboboxItem
              v-for="p in presets"
              :key="p.id"
              :value="p.id"
              :data-testid="`preset-opt-${p.id}`"
            >
              <span class="flex min-w-0 flex-1 items-center gap-1.5">
                <span class="truncate">{{ presetNameLabel(p) }}</span>
                <span aria-hidden="true" class="shrink-0 text-muted-foreground"> · </span>
                <span class="shrink-0 text-[11px] text-muted-foreground">
                  {{ presetVisibilityText(p) }}
                </span>
              </span>
            </ComboboxItem>
            <ComboboxEmpty class="px-2 py-1.5 text-sm text-muted-foreground">{{ t('presetNoTemplates') }}</ComboboxEmpty>
          </ComboboxList>
        </Combobox>
        <p v-if="loadError" class="w-full text-sm text-red-500">{{ loadError }}</p>
        <p v-else-if="!presets.length" class="w-full text-sm text-muted-foreground">{{ t('presetNoTemplates') }}</p>
      </div>
    </template>
    <VariablesEditor ref="editor" :model-value="vars" @update:model-value="onVarsChange" />
    <div class="flex flex-wrap items-center gap-2">
      <Button
        v-if="available"
        type="button"
        variant="outline"
        size="sm"
        class="text-xs"
        :disabled="!vars.length"
        @click="showSaveDialog = true"
      >
        {{ t('presetSaveAsTemplate') }}
      </Button>
      <p v-if="missingAsk.length" class="text-xs text-amber-600 dark:text-amber-400">
        {{ t('presetFillAskFieldsPrefix') }}
        <template v-for="(f, fi) in missingAsk" :key="`m-${f}`">
          <Button
            type="button"
            variant="link"
            size="sm"
            class="h-auto p-0 font-mono text-xs"
            :title="t('presetGoToField', { field: f })"
            @click="focusAskField(f)"
          >
            {{ f }}</Button><span v-if="fi < missingAsk.length - 1">, </span>
        </template>
      </p>
    </div>
    <PresetEditorDialog
      v-if="available"
      :open="showSaveDialog"
      :process-key="processKey"
      :target-kind="targetKind"
      :target-ref="targetRef ?? null"
      :initial-variables="vars.map((v) => ({ ...v }))"
      @close="showSaveDialog = false"
      @saved="onTemplateSaved"
    />
  </div>
</template>
