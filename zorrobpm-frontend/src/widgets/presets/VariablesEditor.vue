<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { X } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogScrollContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
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
import { formatJsonValue, minifyJsonValue } from '@/shared/lib/jsonFormat'
import { useToast } from '@/composables/useToast'

/**
 * WO-VT-3: редактор переменных — «спецификация» карточками вместо жёсткой
 * 4-колоночной строки. Карточка: строка 1 — имя (flex-1, моно) + тип (фикс)
 * + крестик × (удаление в один клик, WO-UI-27 доп.1–2); строка 2 —
 * редактор значения на всю ширину по типу (STRING — авто-рост 1→6,
 * LONG/DOUBLE — текст с inputmode + проверка на blur, чтобы плейсхолдеры
 * {{…}} было видно, BOOLEAN — switch, UUID — кнопка внутри поля, JSON —
 * мини-редактор с автоформатом); строка 3 — поведение (спросить/пустая
 * строка) и ошибка с aria-describedby. Раскладка — container queries
 * (@container), не окно. Значения рендерятся текстом (XSS-safe: только
 * интерполяция, без v-html).
 *
 * WO-UI-27 доп.3: все контролы — shadcn-vue примитивы (Button/Input/Label/
 * Textarea/Switch/Select/Dialog), голых <button>/<input>/<select>/<textarea>
 * и самодельных fixed-оверлеев нет (тест-страж shadcn-guard.ui27.test.ts).
 */
const props = defineProps<{
  modelValue: PresetVariable[]
  disabled?: boolean
  /** id-префикс для связей ask-ссылок (фокус на поле из причины блокировки). */
  idPrefix?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: PresetVariable[]): void
  /** Ctrl+Enter в JSON-фулскрине: применено, просим родителя сохранить. */
  (e: 'requestSave'): void
}>()

const { t } = useI18n()
const toast = useToast()

type Mode = 'table' | 'raw'
const mode = ref<Mode>('table')
const rawText = ref('')
const rawError = ref<string | null>(null)

// WO-VT-3 §5: компакт при >8 строках — поиск, счётчик, свернуть/развернуть.
const query = ref('')
const collapsed = ref(false)
// Ленивый рендер: >50 строк показываем окно по 50 (без новой зависимости).
const shownCount = ref(50)

const TYPES: PresetVariable['type'][] = ['STRING', 'UUID', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON']

const idp = computed(() => props.idPrefix ?? 'pv')
const compact = computed(() => props.modelValue.length > 8)
const errorCount = computed(() => props.modelValue.filter((_, i) => rowError(i)).length)
const filteredIndexes = computed(() => {
  const q = query.value.trim().toLowerCase()
  const all = props.modelValue.map((_, i) => i)
  if (!q) return all
  return all.filter((i) => props.modelValue[i].name.toLowerCase().includes(q))
})
const visibleIndexes = computed(() => filteredIndexes.value.slice(0, shownCount.value))

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

function valuePreview(row: PresetVariable): string {
  if (isAskAtLaunch(row)) return '—'
  const v = row.value.length > 48 ? row.value.slice(0, 48) + '…' : row.value
  return v || '—'
}

/** Строка 3 карточки нужна: поведение (STRING/спросить) или ошибка. */
function rowHasMeta(i: number): boolean {
  const row = props.modelValue[i]
  return row.type === 'STRING' || (isAskAtLaunch(row) && !!row.name.trim()) || rowError(i) !== null
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

/**
 * WO-UI-27 доп.1–2 (критерии 7–9): крестик удаляет СРАЗУ, без меню и без
 * confirm. Защита — тихий undo-тост (~5 с, без красного): восстановление на то
 * же место со всеми значениями. Пустую черновую строку — молча, без тоста.
 * Фокус после удаления — на крестик соседней строки или «Добавить переменную».
 */
function removeRow(i: number) {
  const removed = props.modelValue[i]
  const isDraft = !removed.name.trim() && removed.value === ''
  const next = props.modelValue.filter((_, j) => j !== i)
  emit('update:modelValue', next)
  if (!isDraft) {
    toast.success(t('presetRowDeleted', { name: removed.name || '—' }), {
      duration: 5000,
      action: {
        label: t('presetUndo'),
        onClick: () => {
          const restored = [...props.modelValue]
          restored.splice(Math.min(i, restored.length), 0, removed)
          emit('update:modelValue', restored)
        },
      },
    })
  }
  void nextTick().then(() => {
    const dels = document.querySelectorAll('[data-testid="ve-row-delete"]')
    const target = (dels[Math.min(i, dels.length - 1)] ?? document.querySelector('[data-testid="ve-add-row"]')) as HTMLElement | undefined
    target?.focus()
  })
}

function generateUuid(i: number) {
  patchRow(i, { value: expandPlaceholders('{{uuid}}') })
}

// WO-ACL-11 criterion 11: сравнение-операнд 'true' внутри {{ }} флагит
// сканер непереведённых строк — метка тумблера вычисляется в script.
function booleanLabel(row: PresetVariable): string {
  return row.value === 'true' ? t('presetBooleanTrue') : t('presetBooleanFalse')
}

// --- JSON-миниредактор (WO-VT-3 §2 + WO-UI-27 пп.1–2): токенный форматтер ---
const jsonFs = ref<number | null>(null)
const jsonFsText = ref('')
const jsonFsError = ref<string | null>(null)
// WO-VT-3 HOLD r1 (RT-1): для какой строки живёт черновик фулскрина.
// Esc-возврат черновик не сбрасывает; сброс — только после «Применить».
const jsonFsDraftFor = ref<number | null>(null)

/**
 * WO-UI-27 п.1: показанное в строке — автоформат валидного JSON (токенный,
 * без потерь). Кэш «сырое → показанное» на строку: набор пользователя не
 * переформатируется под руками (только при смене значения снаружи —
 * mount/prop/template), явное действие не затирается.
 */
const jsonShown = ref<Record<number, { raw: string; shown: string }>>({})

function shownJson(i: number): string {
  const row = props.modelValue[i]
  if (!row || row.type !== 'JSON') return row?.value ?? ''
  const cached = jsonShown.value[i]
  if (cached && cached.raw === row.value) return cached.shown
  const formatted = formatJsonValue(row.value)
  const shown = formatted.error ? row.value : (formatted.text ?? row.value)
  jsonShown.value[i] = { raw: row.value, shown }
  return shown
}

function jsonLineCount(i: number): number {
  return shownJson(i).split('\n').length
}

/** Валиден ли JSON строки (для блокировки minify/фулскрина с причиной). */
function jsonValid(i: number): boolean {
  const v = props.modelValue[i]?.value ?? ''
  return formatJsonValue(v).error === null
}

/**
 * WO-UI-27 п.3б: номера строк. Гаттер — те же font-mono/text-sm/line-height
 * 1.4, что у textarea (иначе строки разъезжаются), скролл синхронизируется.
 */
function gutterNumbers(n: number): string {
  return Array.from({ length: n }, (_, k) => String(k + 1)).join('\n')
}

function syncGutter(i: number, e: Event) {
  const g = document.querySelector(`#${idp.value}-gutter-${i}`)
  if (g) g.scrollTop = (e.target as HTMLTextAreaElement).scrollTop
}

function syncFsGutter(e: Event) {
  const g = document.querySelector(`#${idp.value}-jsonfs-gutter`)
  if (g) g.scrollTop = (e.target as HTMLTextAreaElement).scrollTop
}

function autoGrow(el: HTMLTextAreaElement | null) {
  if (!el) return
  el.style.height = 'auto'
  // min 8 строк через CSS min-height; потолок — 40vh, дальше скролл внутри.
  const max = Math.floor(window.innerHeight * 0.4)
  el.style.height = Math.min(el.scrollHeight, max) + 'px'
}

function onJsonInput(i: number, text: string) {
  // shadcn-Textarea эмитит СТРОКУ (не элемент): хранить набранное как есть.
  patchRow(i, { value: text })
  // Набранное — как есть (кэш shown обновится на следующий shownJson).
  jsonShown.value[i] = { raw: text, shown: text }
  autoGrow(document.querySelector(`#${idp.value}-value-${i}`) as HTMLTextAreaElement | null)
}

function onJsonBlur(i: number) {
  // Уход с поля нормализует показ (валидный → красиво), значение то же.
  const row = props.modelValue[i]
  const formatted = formatJsonValue(row.value)
  if (!formatted.error && formatted.text !== null) {
    jsonShown.value[i] = { raw: row.value, shown: formatted.text }
    autoGrow(document.querySelector(`#${idp.value}-value-${i}`) as HTMLTextAreaElement)
  }
}

function formatJson(i: number) {
  const row = props.modelValue[i]
  const out = formatJsonValue(row.value)
  if (out.error || out.text === null) return
  patchRow(i, { value: minifyJsonValue(out.text).text ?? out.text })
  // Показ — отформатированный вариант (эквивалентен побайтово после minify).
  jsonShown.value[i] = { raw: out.text, shown: out.text }
  void nextTick().then(() => {
    autoGrow(document.querySelector(`#${idp.value}-value-${i}`) as HTMLTextAreaElement)
  })
}

function toggleJsonLines(i: number) {
  const row = props.modelValue[i]
  const out = row.value.includes('\n') ? minifyJsonValue(row.value) : formatJsonValue(row.value)
  if (out.error || out.text === null) return
  patchRow(i, { value: out.text })
  jsonShown.value[i] = { raw: out.text, shown: out.text }
}

function openJsonFullscreen(i: number) {
  // WO-VT-3 HOLD r1 (RT-1): повторное открытие той же строки после
  // Esc-возврата НЕ затирает черновик (бриф §2: «без потери»). Новая строка
  // или применённый черновик — загружаем из показа (уже красиво).
  if (i !== jsonFsDraftFor.value) {
    jsonFsText.value = shownJson(i)
    jsonFsDraftFor.value = i
  }
  jsonFsError.value = null
  jsonFs.value = i
  // WO-VT-3 HOLD r1 (RT-1): фокус сразу в фулскрин — Esc-возврат и Ctrl+Enter
  // работают без предварительного клика.
  void nextTick().then(() => {
    ;(document.querySelector(`#${idp.value}-jsonfs`) as HTMLElement | null)?.focus()
  })
}

/**
 * WO-VT-3 HOLD r1 (RT-1): Esc-возврат из фулскрина — закрывается ТОЛЬКО
 * фулскрин, черновик jsonFsText цел (следующее открытие видит набранное),
 * фокус возвращается в мини-редактор строки. Диалог при этом не трогаем:
 * ловушку usePresetModal от Esc внутри фулскрина отводит guard по
 * [data-preset-fs] (бриф §2: «Esc-возврат»).
 */
function closeJsonFullscreen() {
  if (jsonFs.value === null) return
  const i = jsonFs.value
  jsonFs.value = null
  void nextTick().then(() => {
    ;(document.querySelector(`#${idp.value}-value-${i}`) as HTMLElement | null)?.focus()
  })
}

/** Esc внутри shadcn-Dialog фулскрина — тот же Esc-возврат (не закрытие). */
function onFsEscape(e: Event) {
  e.preventDefault()
  closeJsonFullscreen()
}

/** Ctrl+Enter в фулскрине — применить + попросить сохранить (RT-2).
 * Фулскрин — shadcn-портал в document.body: keydown НЕ всплывает в диалог
 * родителя, поэтому вместо всплытия — явный emit (родитель решает, что
 * значит «сохранить»: PresetEditorDialog — save, остальные игнорируют). */
function onFsKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    applyJsonFullscreen()
    // jsonFs === null значит «применено», иначе — ошибка, стоим.
    if (jsonFs.value === null) emit('requestSave')
  }
}

function applyJsonFullscreen() {
  if (jsonFs.value === null) return
  const out = formatJsonValue(jsonFsText.value)
  if (out.error || out.text === null) {
    jsonFsError.value = out.error ?? 'not valid JSON'
    return
  }
  patchRow(jsonFs.value, { value: minifyJsonValue(out.text).text ?? out.text })
  jsonFs.value = null
  // Черновик применён — следующее открытие берёт свежее из props.
  jsonFsDraftFor.value = null
}

/**
 * WO-VT-3 HOLD r1 (RT-2): применить содержимое открытого фулскрина перед
 * внешним save (Ctrl+Enter из диалога). Возвращает false, если JSON невалиден
 * (фулскрин остаётся открыт с ошибкой — сохранять нельзя, иначе набранное
 * молча выброшено).
 */
function commitFullscreen(): boolean {
  if (jsonFs.value === null) return true
  applyJsonFullscreen()
  return jsonFs.value === null
}

/** Tab в JSON-редакторе — отступ, не уход фокуса (бриф §3). */
function onJsonKeydown(i: number, e: KeyboardEvent) {
  if (e.key !== 'Tab') return
  if (e.shiftKey) return // Shift+Tab — штатный выход, не трогаем.
  e.preventDefault()
  const el = e.target as HTMLTextAreaElement
  const start = el.selectionStart ?? el.value.length
  const end = el.selectionEnd ?? el.value.length
  const next = el.value.slice(0, start) + '  ' + el.value.slice(end)
  patchRow(i, { value: next })
  jsonShown.value[i] = { raw: next, shown: next }
  void nextTick().then(() => {
    el.selectionStart = el.selectionEnd = start + 2
  })
}

function onModeKeydown(e: KeyboardEvent) {
  if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return
  e.preventDefault()
  mode.value = mode.value === 'table' ? 'raw' : 'table'
  if (mode.value === 'raw') switchToRaw()
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

function focusField(i: number) {
  const el = document.querySelector(`#${idp.value}-name-${i}, #${idp.value}-value-${i}`) as HTMLElement | null
  el?.focus()
}

// Кэш показа чистим за удалёнными строками (индексы едут — проще сбросить).
watch(
  () => props.modelValue.length,
  () => {
    jsonShown.value = {}
  },
)

defineExpose({ focusField, commitFullscreen, closeJsonFullscreen })
</script>

<template>
  <!-- WO-VT-3: раскладка по ширине КОНТЕЙНЕРА (@container), не окна. -->
  <div class="@container space-y-3">
    <!-- Тулбар редактора: слева поиск/счётчик (в компакте), справа второй
         уровень «Таблица | Сырой JSON» — маленький переключатель, не ряд табов. -->
    <div class="flex items-center gap-2 flex-wrap">
      <div v-if="compact" class="flex items-center gap-2 flex-1 min-w-0">
        <Label :for="`${idp}-search`" class="sr-only">{{ t('presetSearchVariables') }}</Label>
        <Input
          :id="`${idp}-search`"
          v-model="query"
          :placeholder="t('presetSearchVariables')"
          :disabled="disabled"
          class="flex-1 min-w-0 h-8"
        />
        <span class="text-xs text-muted-foreground whitespace-nowrap" data-testid="ve-counter">
          {{ t('presetVarCounter', { n: modelValue.length, m: errorCount }) }}
        </span>
        <Button
          type="button"
          variant="outline"
          size="sm"
          class="text-xs whitespace-nowrap"
          :disabled="disabled"
          @click="collapsed = !collapsed"
        >
          {{ collapsed ? t('presetExpandAll') : t('presetCollapseAll') }}
        </Button>
      </div>
      <span v-else class="flex-1" />
      <div class="flex items-center gap-1 ml-auto" role="tablist" :aria-label="t('presetEditorMode')" @keydown="onModeKeydown">
        <Button
          type="button"
          role="tab"
          :aria-selected="mode === 'table'"
          :title="t('presetTableMode')"
          :variant="mode === 'table' ? 'default' : 'outline'"
          size="sm"
          class="gap-1 text-xs"
          @click="mode = 'table'"
        >
          <span aria-hidden="true">▤</span>{{ t('presetTableMode') }}
        </Button>
        <Button
          type="button"
          role="tab"
          :aria-selected="mode === 'raw'"
          :title="t('presetRawMode')"
          :variant="mode === 'raw' ? 'default' : 'outline'"
          size="sm"
          class="gap-1 text-xs"
          @click="switchToRaw"
        >
          <span aria-hidden="true">{…}</span>{{ t('presetRawMode') }}
        </Button>
      </div>
    </div>

    <div v-if="mode === 'table'" class="space-y-2">
      <template v-for="i in visibleIndexes" :key="i">
        <!-- Компакт: свёрнутая строка «имя · тип · превью · статус». -->
        <Button
          v-if="compact && collapsed"
          variant="ghost"
          class="w-full justify-start gap-2 h-8 px-2"
          :class="rowError(i) ? 'border border-red-400' : ''"
          @click="collapsed = false; query = modelValue[i].name"
        >
          <span class="font-mono text-sm truncate flex-1 min-w-0" :title="modelValue[i].name">{{ modelValue[i].name || '—' }}</span>
          <span class="text-[11px] font-mono text-muted-foreground shrink-0">{{ modelValue[i].type }}</span>
          <span class="text-xs text-muted-foreground truncate max-w-40 shrink-0">{{ valuePreview(modelValue[i]) }}</span>
          <span v-if="isAskAtLaunch(modelValue[i]) && modelValue[i].name.trim()" class="text-[11px] text-amber-600 dark:text-amber-400 shrink-0">◷</span>
          <span v-if="rowError(i)" class="text-[11px] text-red-500 shrink-0">●</span>
        </Button>
        <!-- Карточка переменной: идентичность → значение → поведение. -->
        <div
          v-else
          class="rounded-md border p-2 space-y-2"
          :class="isAskAtLaunch(modelValue[i]) && modelValue[i].name.trim() ? 'border-amber-400 bg-amber-50/50 dark:bg-amber-950/20' : 'border-border'"
          data-testid="ve-card"
        >
          <!-- Строка 1: имя + тип + крестик × (WO-UI-27 доп.1–2: удаление
               в один клик, без меню ⋯ и confirm; undo — тихим тостом). -->
          <div class="flex items-center gap-2">
            <Label :for="`${idp}-name-${i}`" class="sr-only">{{ t('presetVarName') }}</Label>
            <Input
              :id="`${idp}-name-${i}`"
              :model-value="modelValue[i].name"
              :placeholder="t('presetVarName')"
              :disabled="disabled"
              :title="modelValue[i].name.length > 40 ? modelValue[i].name : ''"
              class="flex-1 min-w-0 font-mono h-8"
              @update:model-value="patchRow(i, { name: String($event) })"
            />
            <Label :for="`${idp}-type-${i}`" class="sr-only">{{ t('presetVarType') }}</Label>
            <Select
              :model-value="modelValue[i].type"
              :disabled="disabled"
              @update:model-value="changeType(i, String($event) as PresetVariable['type'])"
            >
              <SelectTrigger :id="`${idp}-type-${i}`" :data-testid="`ve-type-${i}`" class="w-24 shrink-0 font-mono h-8 text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="tp in TYPES" :key="tp" :value="tp" :data-testid="`ve-type-opt-${tp}`">
                  {{ tp }}
                </SelectItem>
              </SelectContent>
            </Select>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              data-testid="ve-row-delete"
              class="shrink-0 h-8 w-8 text-lg leading-none text-muted-foreground hover:text-red-600 hover:bg-red-50 dark:hover:bg-red-950/30 focus-visible:text-red-600"
              :disabled="disabled"
              :aria-label="t('presetDeleteRowAria', { name: modelValue[i].name || '—' })"
              :title="t('presetDeleteRowAria', { name: modelValue[i].name || '—' })"
              @click="removeRow(i)"
            >
              <X class="h-4 w-4" aria-hidden="true" />
            </Button>
          </div>
          <!-- Строка 2: значение на всю ширину, редактор под тип. -->
          <div>
            <template v-if="modelValue[i].type === 'BOOLEAN'">
              <div class="inline-flex items-center gap-2 h-8">
                <Switch
                  :id="`${idp}-value-${i}`"
                  :checked="modelValue[i].value === 'true'"
                  :disabled="disabled"
                  :aria-label="t('presetVarValue')"
                  @update:checked="patchRow(i, { value: $event ? 'true' : 'false' })"
                />
                <span class="font-mono text-sm">{{ booleanLabel(modelValue[i]) }}</span>
              </div>
            </template>
            <template v-else-if="modelValue[i].type === 'JSON'">
              <Label :for="`${idp}-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</Label>
              <div
                class="rounded border"
                :class="rowError(i) ? 'border-red-400' : 'border-input'"
                :data-valid="rowError(i) ? 'invalid' : 'valid'"
              >
                <div class="flex items-center gap-1 px-1 py-0.5 border-b border-border flex-wrap">
                  <Button
                    type="button" variant="link" size="sm"
                    class="px-1.5 h-7 text-xs"
                    :disabled="disabled || !jsonValid(i)"
                    :title="!jsonValid(i) ? t('presetJsonInvalid') : ''"
                    @click="formatJson(i)"
                  >
                    {{ t('presetFormatJson') }}
                  </Button>
                  <Button
                    type="button" variant="link" size="sm"
                    class="px-1.5 h-7 text-xs"
                    :disabled="disabled || !jsonValid(i)"
                    @click="toggleJsonLines(i)"
                  >
                    {{ t('presetToggleJsonLines') }}
                  </Button>
                  <span class="flex-1" />
                  <span class="text-[11px] text-muted-foreground font-mono">
                    {{ t('presetJsonLines', { n: jsonLineCount(i) }) }}
                  </span>
                  <Button
                    type="button" variant="link" size="sm"
                    class="px-1.5 h-7 text-xs"
                    :disabled="disabled"
                    @click="openJsonFullscreen(i)"
                  >
                    {{ t('presetJsonFullscreen') }}
                  </Button>
                </div>
                <div class="flex items-stretch">
                  <div
                    :id="`${idp}-gutter-${i}`"
                    :data-testid="`ve-json-gutter-${i}`"
                    aria-hidden="true"
                    class="shrink-0 select-none overflow-hidden border-r border-border py-1 pl-1 pr-2 text-right font-mono text-sm leading-[1.4] whitespace-pre text-muted-foreground"
                  >{{ gutterNumbers(jsonLineCount(i)) }}</div>
                  <Textarea
                    :id="`${idp}-value-${i}`"
                    :model-value="shownJson(i)"
                    :placeholder="t('presetJsonPlaceholder')"
                    :disabled="disabled"
                    spellcheck="false"
                    :aria-invalid="rowError(i) ? 'true' : 'false'"
                    :aria-describedby="rowError(i) ? `${idp}-err-${i}` : undefined"
                    wrap="off"
                    class="w-full min-w-0 flex-1 px-2 py-1 font-mono leading-[1.4] bg-transparent border-0 focus-visible:ring-0 resize-y json-autogrow"
                    style="min-height: calc(8 * 1.4em + 8px); max-height: 40vh; field-sizing: content;"
                    @update:model-value="onJsonInput(i, String($event))"
                    @blur="onJsonBlur(i)"
                    @keydown="onJsonKeydown(i, $event)"
                    @scroll="syncGutter(i, $event)"
                  />
                </div>
              </div>
            </template>
            <template v-else-if="modelValue[i].type === 'UUID'">
              <Label :for="`${idp}-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</Label>
              <div class="relative">
                <Input
                  :id="`${idp}-value-${i}`"
                  :model-value="modelValue[i].value"
                  :placeholder="t('presetUuidPlaceholder')"
                  :disabled="disabled"
                  class="w-full pr-9 font-mono h-8"
                  :aria-invalid="rowError(i) ? 'true' : 'false'"
                  :aria-describedby="rowError(i) ? `${idp}-err-${i}` : undefined"
                  @update:model-value="patchRow(i, { value: String($event) })"
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  class="absolute right-1 top-1/2 -translate-y-1/2 w-7 h-7 text-base leading-none"
                  :disabled="disabled"
                  :title="t('presetGenerateUuid')"
                  :aria-label="t('presetGenerateUuid')"
                  @click="generateUuid(i)"
                >
                  ⟳
                </Button>
              </div>
            </template>
            <template v-else-if="modelValue[i].type === 'STRING'">
              <Label :for="`${idp}-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</Label>
              <!-- Авто-рост 1→6 строк: field-sizing в Chromium, rows=1 как база. -->
              <Textarea
                :id="`${idp}-value-${i}`"
                :model-value="modelValue[i].value"
                :placeholder="t('presetValuePlaceholder')"
                :disabled="disabled"
                :rows="1"
                class="w-full font-mono"
                style="field-sizing: content; min-height: 2rem; max-height: calc(6 * 1.4em + 8px);"
                :aria-invalid="rowError(i) ? 'true' : 'false'"
                :aria-describedby="rowError(i) ? `${idp}-err-${i}` : undefined"
                @update:model-value="patchRow(i, { value: String($event) })"
              />
            </template>
            <template v-else>
              <Label :for="`${idp}-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</Label>
              <!-- WO-VT-3 (A-NEW-1): type=text + inputmode вместо type=number:
                   number-инпут молча стирает плейсхолдеры {{…}} при показе.
                   Числовая проверка — живым валидатором строки. -->
              <Input
                :id="`${idp}-value-${i}`"
                :model-value="modelValue[i].value"
                type="text"
                :inputmode="modelValue[i].type === 'LONG' ? 'numeric' : 'decimal'"
                :placeholder="t('presetValuePlaceholder')"
                :disabled="disabled"
                class="w-full font-mono h-8"
                :aria-invalid="rowError(i) ? 'true' : 'false'"
                :aria-describedby="rowError(i) ? `${idp}-err-${i}` : undefined"
                @update:model-value="patchRow(i, { value: String($event) })"
              />
            </template>
          </div>
          <!-- Строка 3: поведение + ошибка (только если нужна). -->
          <div v-if="rowHasMeta(i)" class="space-y-1">
            <div class="flex items-center gap-2 flex-wrap">
              <span
                v-if="isAskAtLaunch(modelValue[i]) && modelValue[i].name.trim()"
                class="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded-full border border-amber-400 text-amber-700 dark:text-amber-300"
                :title="t('presetAskHint')"
              >
                ◷ {{ t('presetAskChip') }}
              </span>
              <Button
                v-if="modelValue[i].type === 'STRING'"
                type="button"
                variant="outline"
                size="sm"
                :aria-pressed="modelValue[i].allowEmptyString === true ? 'true' : 'false'"
                :disabled="disabled"
                class="gap-1 px-2 py-0.5 text-xs rounded-full h-7"
                :class="modelValue[i].allowEmptyString === true ? 'border-primary text-primary' : 'text-muted-foreground'"
                :title="t('presetEmptyStringHint')"
                @click="patchRow(i, { allowEmptyString: !(modelValue[i].allowEmptyString === true) })"
              >
                {{ t('presetEmptyStringChip') }}
              </Button>
            </div>
            <p v-if="rowError(i)" :id="`${idp}-err-${i}`" role="alert" class="text-xs text-red-500">{{ rowError(i) }}</p>
          </div>
        </div>
      </template>
      <p v-if="!modelValue.length" class="text-sm text-muted-foreground">{{ t('presetNoVariables') }}</p>
      <p v-else-if="compact && query && !filteredIndexes.length" class="text-sm text-muted-foreground">
        {{ t('presetNoVariablesMatch', { q: query }) }}
      </p>
      <Button
        v-if="compact && filteredIndexes.length > shownCount"
        type="button"
        variant="outline"
        class="w-full text-sm h-9"
        @click="shownCount += 50"
      >
        {{ t('presetShowMore', { n: filteredIndexes.length - shownCount }) }}
      </Button>
      <Button
        type="button"
        variant="outline"
        data-testid="ve-add-row"
        class="w-full text-sm transition-colors disabled:opacity-50 h-9"
        :disabled="disabled || modelValue.length >= MAX_PRESET_VARIABLES"
        @click="addRow"
      >
        + {{ t('presetAddVariable') }}
      </Button>
    </div>

    <div v-else class="space-y-2">
      <Label for="pv-raw" class="sr-only">{{ t('presetRawMode') }}</Label>
      <Textarea
        id="pv-raw"
        :model-value="rawText"
        :rows="8"
        spellcheck="false"
        :disabled="disabled"
        class="w-full font-mono"
        @update:model-value="rawText = String($event)"
      />
      <p v-if="rawError" role="alert" class="text-xs text-red-500">{{ rawError }}</p>
      <div class="flex justify-end gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          @click="mode = 'table'"
        >
          {{ t('cancel') }}
        </Button>
        <Button
          type="button"
          size="sm"
          :disabled="disabled"
          @click="applyRaw"
        >
          {{ t('presetApplyRaw') }}
        </Button>
      </div>
    </div>

    <!-- JSON на весь экран: shadcn Dialog (WO-UI-27 доп.3), Esc — возврат
         без потери (RT-1), Ctrl+Enter — применить (RT-2). -->
    <Dialog :open="jsonFs !== null" @update:open="(v) => { if (!v) closeJsonFullscreen() }">
      <DialogScrollContent
        class="flex flex-col gap-2 p-4 w-[min(96vw,1600px)] max-w-[95vw] h-[92vh]"
        @escape-key-down="onFsEscape"
        @pointer-down-outside="(e) => e.preventDefault()"
        @interact-outside="(e) => e.preventDefault()"
      >
        <div data-preset-fs class="flex min-h-0 flex-1 flex-col gap-2">
        <DialogHeader class="flex-row items-center gap-2 space-y-0">
          <DialogTitle class="font-mono text-sm truncate flex-1">{{ jsonFs !== null ? modelValue[jsonFs].name : '' }}</DialogTitle>
          <span class="text-xs text-muted-foreground font-mono">{{ t('presetJsonLines', { n: jsonFsText.split('\n').length }) }}</span>
        </DialogHeader>
        <DialogDescription class="sr-only">{{ t('presetJsonFullscreen') }}</DialogDescription>
        <Label :for="`${idp}-jsonfs`" class="sr-only">{{ t('presetVarValue') }}</Label>
        <div class="flex min-h-0 flex-1 items-stretch overflow-hidden rounded-md border border-input bg-background">
          <div
            :id="`${idp}-jsonfs-gutter`"
            data-testid="ve-jsonfs-gutter"
            aria-hidden="true"
            class="shrink-0 select-none overflow-hidden border-r border-border py-2 pl-2 pr-2 text-right font-mono text-sm leading-[1.4] whitespace-pre text-muted-foreground"
          >{{ gutterNumbers(jsonFsText.split('\n').length) }}</div>
          <Textarea
            :id="`${idp}-jsonfs`"
            :model-value="jsonFsText"
            spellcheck="false"
            wrap="off"
            class="flex-1 min-h-0 min-w-0 font-mono leading-[1.4] border-0 bg-transparent"
            @update:model-value="jsonFsText = String($event)"
            @keydown="onFsKeydown"
            @scroll="syncFsGutter($event)"
          />
        </div>
        <p v-if="jsonFsError" role="alert" class="text-xs text-red-500">{{ jsonFsError }}</p>
        <DialogFooter class="gap-2">
          <Button type="button" variant="outline" size="sm" @click="closeJsonFullscreen()">
            {{ t('cancel') }}
          </Button>
          <Button type="button" size="sm" @click="applyJsonFullscreen">
            {{ t('presetApplyRaw') }}
          </Button>
        </DialogFooter>
        </div>
      </DialogScrollContent>
    </Dialog>
  </div>
</template>
