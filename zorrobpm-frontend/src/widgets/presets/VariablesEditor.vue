<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
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
 * WO-VT-3: редактор переменных — «спецификация» карточками вместо жёсткой
 * 4-колоночной строки. Карточка: строка 1 — имя (flex-1, моно) + тип (фикс)
 * + меню ⋯ (Дублировать / Удалить с inline-подтверждением); строка 2 —
 * редактор значения на всю ширину по типу (STRING — авто-рост 1→6,
 * LONG/DOUBLE — текст с inputmode + проверка на blur, чтобы плейсхолдеры
 * {{…}} было видно, BOOLEAN — switch, UUID — кнопка внутри поля, JSON —
 * мини-редактор); строка 3 — поведение (спросить/пустая строка) и ошибка
 * с aria-describedby. Раскладка — container queries (@container), не окно.
 * Значения рендерятся текстом (XSS-safe: только интерполяция, без v-html).
 */
const props = defineProps<{
  modelValue: PresetVariable[]
  disabled?: boolean
  /** id-префикс для связей ask-ссылок (фокус на поле из причины блокировки). */
  idPrefix?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: PresetVariable[]): void
}>()

const { t } = useI18n()

type Mode = 'table' | 'raw'
const mode = ref<Mode>('table')
const rawText = ref('')
const rawError = ref<string | null>(null)

// WO-VT-3 §5: компакт при >8 строках — поиск, счётчик, свернуть/развернуть.
const query = ref('')
const collapsed = ref(false)
const confirmDelete = ref<number | null>(null)
const menuOpen = ref<number | null>(null)
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
  confirmDelete.value = null
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
  confirmDelete.value = null
  emit('update:modelValue', [...props.modelValue, { name: '', type: 'STRING', value: '' }])
}

function duplicateRow(i: number) {
  if (props.modelValue.length >= MAX_PRESET_VARIABLES) return
  menuOpen.value = null
  const copy = { ...props.modelValue[i] }
  const next = [...props.modelValue.slice(0, i + 1), copy, ...props.modelValue.slice(i + 1)]
  emit('update:modelValue', next)
  // WO-VT-3 HOLD r1 (RT-3): фокус назад на кнопку ⋯ (меню размонтировано —
  // фокус иначе падает в body и клавиатурный пользователь теряет место).
  focusRowMenu(i)
}

function focusRowMenu(i: number) {
  void nextTick().then(() => {
    const btns = document.querySelectorAll('[data-testid="ve-row-menu"]')
    ;(btns[i] as HTMLElement | undefined)?.focus()
  })
}

/**
 * WO-VT-3 HOLD r1 (RT-4): стрелочная навигация в меню строки (ArrowUp/Down —
 * по пунктам с зацикливанием, Home/End — к краям), как обещают
 * role=menu/menuitem. Хендлер делегирован на контейнер меню.
 */
function onRowMenuKeydown(e: KeyboardEvent, i: number) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End' && e.key !== 'Escape') return
  const menu = (e.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="menuitem"]')
  if (!menu.length) return
  if (e.key === 'Escape') {
    menuOpen.value = null
    focusRowMenu(i)
    return
  }
  e.preventDefault()
  const items = [...menu]
  const idx = items.indexOf(document.activeElement as HTMLButtonElement)
  if (e.key === 'Home' || (e.key === 'ArrowUp' && (idx < 0 || idx === 0))) {
    ;(e.key === 'Home' ? items[0] : items[items.length - 1]).focus()
  } else if (e.key === 'End' || (e.key === 'ArrowDown' && idx === items.length - 1)) {
    ;(e.key === 'End' ? items[items.length - 1] : items[0]).focus()
  } else if (e.key === 'ArrowDown') {
    items[idx + 1].focus()
  } else if (e.key === 'ArrowUp') {
    items[idx - 1].focus()
  }
}

function removeRow(i: number) {
  if (confirmDelete.value !== i) {
    confirmDelete.value = i
    return
  }
  confirmDelete.value = null
  menuOpen.value = null
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

// --- JSON-миниредактор (WO-VT-3 §2): авто-рост до 40vh, тулбар, фулскрин ---
const jsonFs = ref<number | null>(null)
const jsonFsText = ref('')
const jsonFsError = ref<string | null>(null)
const jsonLineCount = ref(0)
// WO-VT-3 HOLD r1 (RT-1): для какой строки живёт черновик фулскрина.
// Esc-возврат черновик не сбрасывает; сброс — только после «Применить».
const jsonFsDraftFor = ref<number | null>(null)
let formatTimer: ReturnType<typeof setTimeout> | null = null

function autoGrow(el: HTMLTextAreaElement | null) {
  if (!el) return
  el.style.height = 'auto'
  // min 8 строк через CSS min-height; потолок — 40vh, дальше скролл внутри.
  const max = Math.floor(window.innerHeight * 0.4)
  el.style.height = Math.min(el.scrollHeight, max) + 'px'
}

function onJsonInput(i: number, el: HTMLTextAreaElement) {
  patchRow(i, { value: el.value })
  autoGrow(el)
  // WO-VT-3 §2: большой JSON (≥50 КБ) — без форматирования на каждое нажатие.
  if (formatTimer) clearTimeout(formatTimer)
  if (el.value.length >= 50 * 1024) {
    formatTimer = setTimeout(() => autoGrow(el), 300)
  }
}

function formatJson(i: number) {
  const row = props.modelValue[i]
  try {
    patchRow(i, { value: JSON.stringify(JSON.parse(row.value), null, 2) })
  } catch {
    // Ошибка уже показана валидатором строки — молча не глотаем, ничего не меняем.
  }
  void nextTick().then(() => {
    autoGrow(document.querySelector(`#${idp.value}-value-${i}`) as HTMLTextAreaElement)
  })
}

function toggleJsonLines(i: number) {
  const row = props.modelValue[i]
  try {
    const parsed = JSON.parse(row.value)
    const flat = JSON.stringify(parsed)
    const pretty = JSON.stringify(parsed, null, 2)
    patchRow(i, { value: row.value.includes('\n') ? flat : pretty })
  } catch {
    // Битый JSON не трогаем — ошибка видна валидатором.
  }
}

function openJsonFullscreen(i: number) {
  // WO-VT-3 HOLD r1 (RT-1): повторное открытие той же строки после
  // Esc-возврата НЕ затирает черновик (бриф §2: «без потери»). Новая строка
  // или применённый черновик — загружаем из props.
  if (i !== jsonFsDraftFor.value) {
    jsonFsText.value = props.modelValue[i].value
    jsonFsDraftFor.value = i
  }
  jsonFsError.value = null
  jsonLineCount.value = (jsonFsText.value || '').split('\n').length
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

function applyJsonFullscreen() {
  if (jsonFs.value === null) return
  try {
    JSON.parse(jsonFsText.value)
  } catch (e) {
    jsonFsError.value = e instanceof Error ? e.message : 'not valid JSON'
    return
  }
  patchRow(jsonFs.value, { value: jsonFsText.value })
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

defineExpose({ focusField, commitFullscreen, closeJsonFullscreen })
</script>

<template>
  <!-- WO-VT-3: раскладка по ширине КОНТЕЙНЕРА (@container), не окна. -->
  <div class="@container space-y-3">
    <!-- Тулбар редактора: слева поиск/счётчик (в компакте), справа второй
         уровень «Таблица | Сырой JSON» — маленький переключатель, не ряд табов. -->
    <div class="flex items-center gap-2 flex-wrap">
      <div v-if="compact" class="flex items-center gap-2 flex-1 min-w-0">
        <label :for="`${idp}-search`" class="sr-only">{{ t('presetSearchVariables') }}</label>
        <input
          :id="`${idp}-search`"
          v-model="query"
          :placeholder="t('presetSearchVariables')"
          :disabled="disabled"
          class="flex-1 min-w-0 px-2 py-1 border border-input rounded text-sm h-8"
        />
        <span class="text-xs text-muted-foreground whitespace-nowrap" data-testid="ve-counter">
          {{ t('presetVarCounter', { n: modelValue.length, m: errorCount }) }}
        </span>
        <button
          type="button"
          class="px-2 py-1 text-xs border border-border rounded hover:bg-muted h-8 whitespace-nowrap"
          :disabled="disabled"
          @click="collapsed = !collapsed"
        >
          {{ collapsed ? t('presetExpandAll') : t('presetCollapseAll') }}
        </button>
      </div>
      <span v-else class="flex-1" />
      <div class="flex items-center gap-1 ml-auto" role="tablist" :aria-label="t('presetEditorMode')" @keydown="onModeKeydown">
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'table'"
          :title="t('presetTableMode')"
          class="inline-flex items-center gap-1 px-2 py-1 text-xs rounded-md border h-8"
          :class="mode === 'table' ? 'bg-primary text-primary-foreground border-primary' : 'border-border hover:bg-muted'"
          @click="mode = 'table'"
        >
          <span aria-hidden="true">▤</span>{{ t('presetTableMode') }}
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'raw'"
          :title="t('presetRawMode')"
          class="inline-flex items-center gap-1 px-2 py-1 text-xs rounded-md border h-8"
          :class="mode === 'raw' ? 'bg-primary text-primary-foreground border-primary' : 'border-border hover:bg-muted'"
          @click="switchToRaw"
        >
          <span aria-hidden="true">{…}</span>{{ t('presetRawMode') }}
        </button>
      </div>
    </div>

    <div v-if="mode === 'table'" class="space-y-2">
      <template v-for="i in visibleIndexes" :key="i">
        <!-- Компакт: свёрнутая строка «имя · тип · превью · статус». -->
        <button
          v-if="compact && collapsed"
          type="button"
          class="w-full flex items-center gap-2 rounded-md border px-2 py-1 text-left h-8 border-border hover:bg-muted"
          :class="rowError(i) ? 'border-red-400' : ''"
          @click="collapsed = false; query = modelValue[i].name"
        >
          <span class="font-mono text-sm truncate flex-1 min-w-0" :title="modelValue[i].name">{{ modelValue[i].name || '—' }}</span>
          <span class="text-[11px] font-mono text-muted-foreground shrink-0">{{ modelValue[i].type }}</span>
          <span class="text-xs text-muted-foreground truncate max-w-40 shrink-0">{{ valuePreview(modelValue[i]) }}</span>
          <span v-if="isAskAtLaunch(modelValue[i]) && modelValue[i].name.trim()" class="text-[11px] text-amber-600 dark:text-amber-400 shrink-0">◷</span>
          <span v-if="rowError(i)" class="text-[11px] text-red-500 shrink-0">●</span>
        </button>
        <!-- Карточка переменной: идентичность → значение → поведение. -->
        <div
          v-else
          class="rounded-md border p-2 space-y-2"
          :class="isAskAtLaunch(modelValue[i]) && modelValue[i].name.trim() ? 'border-amber-400 bg-amber-50/50 dark:bg-amber-950/20' : 'border-border'"
          data-testid="ve-card"
        >
          <!-- Строка 1: имя + тип + меню ⋯ -->
          <div class="flex items-center gap-2">
            <label :for="`${idp}-name-${i}`" class="sr-only">{{ t('presetVarName') }}</label>
            <input
              :id="`${idp}-name-${i}`"
              :value="modelValue[i].name"
              :placeholder="t('presetVarName')"
              :disabled="disabled"
              :title="modelValue[i].name.length > 40 ? modelValue[i].name : ''"
              class="flex-1 min-w-0 px-2 py-1 border border-input rounded text-sm font-mono h-8"
              @input="patchRow(i, { name: ($event.target as HTMLInputElement).value })"
            />
            <label :for="`${idp}-type-${i}`" class="sr-only">{{ t('presetVarType') }}</label>
            <select
              :id="`${idp}-type-${i}`"
              :value="modelValue[i].type"
              :disabled="disabled"
              class="w-24 shrink-0 px-1 py-1 border border-input rounded text-sm font-mono h-8"
              @change="changeType(i, ($event.target as HTMLSelectElement).value as PresetVariable['type'])"
            >
              <option v-for="tp in TYPES" :key="tp" :value="tp">{{ tp }}</option>
            </select>
            <div class="relative shrink-0">
              <button
                type="button"
                data-testid="ve-row-menu"
                class="inline-flex items-center justify-center w-8 h-8 rounded hover:bg-muted text-lg leading-none"
                :disabled="disabled"
                :aria-label="t('presetRowMenu')"
                :aria-expanded="menuOpen === i ? 'true' : 'false'"
                :aria-haspopup="'menu'"
                @click="menuOpen = menuOpen === i ? null : i"
              >
                ⋯
              </button>
              <div
                v-if="menuOpen === i"
                role="menu"
                class="absolute right-0 top-9 z-20 min-w-36 rounded-md border border-border bg-card shadow-lg p-1"
                @keydown="onRowMenuKeydown($event, i)"
              >
                <button
                  type="button"
                  role="menuitem"
                  data-testid="ve-row-menu-duplicate"
                  class="w-full text-left px-2 py-1.5 text-xs rounded hover:bg-muted h-8"
                  @click="duplicateRow(i)"
                >
                  {{ t('presetDuplicateRow') }}
                </button>
                <button
                  type="button"
                  role="menuitem"
                  data-testid="ve-row-menu-delete"
                  class="w-full text-left px-2 py-1.5 text-xs rounded hover:bg-muted h-8"
                  :class="confirmDelete === i ? 'text-red-600 font-semibold' : 'text-red-500'"
                  @click="removeRow(i)"
                >
                  {{ confirmDelete === i ? t('presetConfirmDeleteRow') : t('remove') }}
                </button>
              </div>
            </div>
          </div>
          <!-- Строка 2: значение на всю ширину, редактор под тип. -->
          <div>
            <template v-if="modelValue[i].type === 'BOOLEAN'">
              <button
                :id="`${idp}-value-${i}`"
                type="button"
                role="switch"
                :aria-checked="modelValue[i].value === 'true' ? 'true' : 'false'"
                :disabled="disabled"
                class="inline-flex items-center gap-2 h-8"
                @click="patchRow(i, { value: modelValue[i].value === 'true' ? 'false' : 'true' })"
              >
                <span
                  class="inline-flex w-9 h-5 rounded-full p-0.5 transition-colors"
                  :class="modelValue[i].value === 'true' ? 'bg-primary justify-end' : 'bg-muted justify-start'"
                >
                  <span class="w-4 h-4 rounded-full bg-white shadow" />
                </span>
                <span class="font-mono text-sm">{{ booleanLabel(modelValue[i]) }}</span>
              </button>
            </template>
            <template v-else-if="modelValue[i].type === 'JSON'">
              <label :for="`${idp}-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</label>
              <div
                class="rounded border"
                :class="rowError(i) ? 'border-red-400' : 'border-input'"
                :data-valid="rowError(i) ? 'invalid' : 'valid'"
              >
                <div class="flex items-center gap-1 px-1 py-0.5 border-b border-border flex-wrap">
                  <button type="button" class="px-1.5 py-0.5 text-xs text-primary hover:underline h-7" :disabled="disabled" @click="formatJson(i)">
                    {{ t('presetFormatJson') }}
                  </button>
                  <button type="button" class="px-1.5 py-0.5 text-xs text-primary hover:underline h-7" :disabled="disabled" @click="toggleJsonLines(i)">
                    {{ t('presetToggleJsonLines') }}
                  </button>
                  <span class="flex-1" />
                  <span class="text-[11px] text-muted-foreground font-mono">
                    {{ t('presetJsonLines', { n: (modelValue[i].value || '').split('\n').length }) }}
                  </span>
                  <button type="button" class="px-1.5 py-0.5 text-xs text-primary hover:underline h-7" :disabled="disabled" @click="openJsonFullscreen(i)">
                    {{ t('presetJsonFullscreen') }}
                  </button>
                </div>
                <textarea
                  :id="`${idp}-value-${i}`"
                  :value="modelValue[i].value"
                  :placeholder="t('presetJsonPlaceholder')"
                  :disabled="disabled"
                  spellcheck="false"
                  :aria-invalid="rowError(i) ? 'true' : 'false'"
                  :aria-describedby="rowError(i) ? `${idp}-err-${i}` : undefined"
                  class="w-full px-2 py-1 text-sm font-mono bg-transparent outline-none resize-y json-autogrow"
                  style="min-height: calc(8 * 1.4em + 8px); max-height: 40vh; field-sizing: content;"
                  @input="onJsonInput(i, $event.target as HTMLTextAreaElement)"
                />
              </div>
            </template>
            <template v-else-if="modelValue[i].type === 'UUID'">
              <label :for="`${idp}-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</label>
              <div class="relative">
                <input
                  :id="`${idp}-value-${i}`"
                  :value="modelValue[i].value"
                  :placeholder="t('presetUuidPlaceholder')"
                  :disabled="disabled"
                  class="w-full pl-2 pr-9 py-1 border border-input rounded text-sm font-mono h-8"
                  :aria-invalid="rowError(i) ? 'true' : 'false'"
                  :aria-describedby="rowError(i) ? `${idp}-err-${i}` : undefined"
                  @input="patchRow(i, { value: ($event.target as HTMLInputElement).value })"
                />
                <button
                  type="button"
                  class="absolute right-1 top-1/2 -translate-y-1/2 inline-flex items-center justify-center w-7 h-7 rounded hover:bg-muted text-base leading-none"
                  :disabled="disabled"
                  :title="t('presetGenerateUuid')"
                  :aria-label="t('presetGenerateUuid')"
                  @click="generateUuid(i)"
                >
                  ⟳
                </button>
              </div>
            </template>
            <template v-else-if="modelValue[i].type === 'STRING'">
              <label :for="`${idp}-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</label>
              <!-- Авто-рост 1→6 строк: field-sizing в Chromium, rows=1 как база. -->
              <textarea
                :id="`${idp}-value-${i}`"
                :value="modelValue[i].value"
                :placeholder="t('presetValuePlaceholder')"
                :disabled="disabled"
                rows="1"
                class="w-full px-2 py-1 border border-input rounded text-sm font-mono"
                style="field-sizing: content; min-height: 2rem; max-height: calc(6 * 1.4em + 8px);"
                :aria-invalid="rowError(i) ? 'true' : 'false'"
                :aria-describedby="rowError(i) ? `${idp}-err-${i}` : undefined"
                @input="patchRow(i, { value: ($event.target as HTMLTextAreaElement).value })"
              />
            </template>
            <template v-else>
              <label :for="`${idp}-value-${i}`" class="sr-only">{{ t('presetVarValue') }}</label>
              <!-- WO-VT-3 (A-NEW-1): type=text + inputmode вместо type=number:
                   number-инпут молча стирает плейсхолдеры {{…}} при показе.
                   Числовая проверка — живым валидатором строки. -->
              <input
                :id="`${idp}-value-${i}`"
                :value="modelValue[i].value"
                type="text"
                :inputmode="modelValue[i].type === 'LONG' ? 'numeric' : 'decimal'"
                :placeholder="t('presetValuePlaceholder')"
                :disabled="disabled"
                class="w-full px-2 py-1 border border-input rounded text-sm font-mono h-8"
                :aria-invalid="rowError(i) ? 'true' : 'false'"
                :aria-describedby="rowError(i) ? `${idp}-err-${i}` : undefined"
                @input="patchRow(i, { value: ($event.target as HTMLInputElement).value })"
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
              <button
                v-if="modelValue[i].type === 'STRING'"
                type="button"
                :aria-pressed="modelValue[i].allowEmptyString === true ? 'true' : 'false'"
                :disabled="disabled"
                class="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded-full border h-7"
                :class="modelValue[i].allowEmptyString === true ? 'border-primary text-primary' : 'border-border text-muted-foreground hover:bg-muted'"
                :title="t('presetEmptyStringHint')"
                @click="patchRow(i, { allowEmptyString: !(modelValue[i].allowEmptyString === true) })"
              >
                {{ t('presetEmptyStringChip') }}
              </button>
            </div>
            <p v-if="rowError(i)" :id="`${idp}-err-${i}`" role="alert" class="text-xs text-red-500">{{ rowError(i) }}</p>
          </div>
          <div v-if="confirmDelete === i" data-testid="ve-delete-confirm" class="flex items-center gap-2 rounded border border-red-300 bg-red-50 px-2 py-1 dark:bg-red-950/30" role="alert">
            <span class="text-xs text-red-600 dark:text-red-300 flex-1">{{ t('presetDeleteRowConfirm') }}</span>
            <button
              type="button"
              class="px-2 py-0.5 text-xs rounded bg-red-500 text-white hover:opacity-90 h-7"
              @click="removeRow(i)"
            >
              {{ t('presetDeleteRowYes') }}
            </button>
            <button
              type="button"
              class="px-2 py-0.5 text-xs rounded border border-border hover:bg-muted h-7"
              @click="confirmDelete = null"
            >
              {{ t('cancel') }}
            </button>
          </div>
        </div>
      </template>
      <p v-if="!modelValue.length" class="text-sm text-muted-foreground">{{ t('presetNoVariables') }}</p>
      <p v-else-if="compact && query && !filteredIndexes.length" class="text-sm text-muted-foreground">
        {{ t('presetNoVariablesMatch', { q: query }) }}
      </p>
      <button
        v-if="compact && filteredIndexes.length > shownCount"
        type="button"
        class="w-full px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted h-9"
        @click="shownCount += 50"
      >
        {{ t('presetShowMore', { n: filteredIndexes.length - shownCount }) }}
      </button>
      <button
        type="button"
        class="w-full px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted transition-colors disabled:opacity-50 h-9"
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
          class="px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted h-8"
          @click="mode = 'table'"
        >
          {{ t('cancel') }}
        </button>
        <button
          type="button"
          class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 h-8"
          :disabled="disabled"
          @click="applyRaw"
        >
          {{ t('presetApplyRaw') }}
        </button>
      </div>
    </div>

    <!-- JSON на весь экран: поверх модалки, Esc — возврат без потери. -->
    <div
      v-if="jsonFs !== null"
      data-preset-fs
      class="fixed inset-0 z-[60] bg-black/60 flex items-center justify-center p-4"
      @click.self="closeJsonFullscreen()"
      @keydown.escape.stop="closeJsonFullscreen()"
    >
      <div class="bg-card rounded-lg shadow-lg w-full max-w-3xl max-h-[90vh] flex flex-col p-4 gap-2" role="dialog" aria-modal="true" :aria-label="t('presetJsonFullscreen')">
        <div class="flex items-center gap-2">
          <span class="font-mono text-sm truncate flex-1">{{ modelValue[jsonFs].name }}</span>
          <span class="text-xs text-muted-foreground font-mono">{{ t('presetJsonLines', { n: jsonFsText.split('\n').length }) }}</span>
          <button type="button" class="text-sm text-muted-foreground hover:text-foreground h-8 px-2" @click="jsonFs = null">
            {{ t('close') }} (Esc)
          </button>
        </div>
        <label :for="`${idp}-jsonfs`" class="sr-only">{{ t('presetVarValue') }}</label>
        <textarea
          :id="`${idp}-jsonfs`"
          v-model="jsonFsText"
          spellcheck="false"
          class="flex-1 min-h-64 w-full px-2 py-1 border rounded text-sm font-mono"
          :class="jsonFsError ? 'border-red-400' : 'border-input'"
        />
        <p v-if="jsonFsError" role="alert" class="text-xs text-red-500">{{ jsonFsError }}</p>
        <div class="flex justify-end gap-2">
          <button type="button" class="px-3 py-1.5 text-sm border border-border rounded-md hover:bg-muted h-8" @click="closeJsonFullscreen()">
            {{ t('cancel') }}
          </button>
          <button type="button" class="px-3 py-1.5 text-sm bg-primary text-primary-foreground rounded-md hover:opacity-90 h-8" @click="applyJsonFullscreen">
            {{ t('presetApplyRaw') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
