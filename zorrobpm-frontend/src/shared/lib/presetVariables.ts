import type { BpmnNode } from '@/types/api'
import type { PresetTargetKind, PresetVariable } from '@/types/presets'

/**
 * WO-VT-1 (фронт): чистая логика шаблонов переменных. Зеркалит серверный
 * VariablePresetValidator один в один (те же правила, те же плейсхолдеры):
 * LONG парсится, DOUBLE конечный, BOOLEAN true/false, UUID формат, JSON
 * валидный, STRING любая; пустое значение допустимо для ЛЮБОГО типа
 * («спросить при запуске», формат не проверяется); `allowEmptyString` только
 * для STRING; плейсхолдеры `{{…}}` — только из закрытого набора, с
 * обязательной парой скобок; тип проверяется после подстановки на клиенте.
 */

/** Закрытый набор плейсхолдеров — как VariablePresetValidator.PLACEHOLDERS. */
export const PRESET_PLACEHOLDERS = ['uuid', 'now', 'random:long', 'seq'] as const

export type PresetPlaceholder = (typeof PRESET_PLACEHOLDERS)[number]

export const MAX_PRESET_VARIABLES = 100
export const MAX_PRESET_VALUE_BYTES = 256 * 1024

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

function utf8Length(s: string): number {
  return new TextEncoder().encode(s).length
}

/** Значение содержит маркеры плейсхолдера — как containsPlaceholder на сервере. */
export function containsPlaceholder(value: string): boolean {
  return value.includes('{{') || value.includes('}}')
}

/**
 * Строгая проверка синтаксиса плейсхолдеров — как checkPlaceholders:
 * каждая `{{…}}` обязана закрыться, имя внутри — из закрытого набора.
 * Возвращает текст ошибки или null (корректно либо плейсхолдеров нет).
 */
export function checkPlaceholders(value: string): string | null {
  let pos = 0
  for (;;) {
    const open = value.indexOf('{{', pos)
    const close = value.indexOf('}}', pos)
    if (open < 0 && close < 0) return null
    if (close >= 0 && (open < 0 || close < open)) {
      return "unbalanced placeholder braces ('}}' without '{{')}}"
    }
    const end = value.indexOf('}}', open + 2)
    if (end < 0) {
      return "unbalanced placeholder braces ('{{' without '}}')"
    }
    const inner = value.substring(open + 2, end).trim()
    if (!(PRESET_PLACEHOLDERS as readonly string[]).includes(inner)) {
      return `unknown placeholder '{{${inner}}}' (allowed: ${[...PRESET_PLACEHOLDERS]
        .sort()
        .map((s) => `{{${s}}}`)
        .join(', ')})`
    }
    pos = end + 2
  }
}

/**
 * Проверка значения по типу — как checkTypedValue: пустое для ЛЮБОГО типа
 * допустимо (не проверяется — это «спросить при запуске»).
 */
export function checkTypedValue(type: PresetVariable['type'], value: string): string | null {
  if (value === '') return null
  switch (type) {
    case 'LONG':
      return /^-?\d+$/.test(value.trim()) && isSafeLong(value.trim())
        ? null
        : `not a LONG: '${abbrev(value)}'`
    case 'DOUBLE': {
      const d = Number(value)
      return value.trim() !== '' && Number.isFinite(d) ? null : `not a DOUBLE: '${abbrev(value)}'`
    }
    case 'BOOLEAN':
      return value === 'true' || value === 'false'
        ? null
        : `not a BOOLEAN (true/false): '${abbrev(value)}'`
    case 'UUID':
      return UUID_RE.test(value.trim()) ? null : `not a UUID: '${abbrev(value)}'`
    case 'JSON':
      try {
        JSON.parse(value)
        return null
      } catch {
        return 'not valid JSON'
      }
    case 'STRING':
      return null
  }
}

function isSafeLong(trimmed: string): boolean {
  try {
    const v = BigInt(trimmed)
    return v >= -(2n ** 63n) && v <= 2n ** 63n - 1n
  } catch {
    return false
  }
}

function abbrev(value: string): string {
  return value.length > 60 ? value.substring(0, 60) + '…' : value
}

export interface PresetRowError {
  name?: string
  value?: string
}

/**
 * Проверка одной строки редактора. Возвращает null, если строка корректна.
 * Пустое имя при пустом значении — «пустая строка», не ошибка (редактор её
 * пропускает при сохранении, как черновик).
 */
export function validatePresetRow(row: {
  name: string
  type: PresetVariable['type']
  value: string
  allowEmptyString?: boolean | null
}): PresetRowError | null {
  const errors: PresetRowError = {}
  const name = row.name.trim()
  if (!name && row.value === '') return null
  if (!name) {
    errors.name = 'name is required'
  }
  if (row.allowEmptyString === true && row.type !== 'STRING') {
    errors.value = 'allowEmptyString is only for STRING'
    return errors
  }
  if (utf8Length(row.value) > MAX_PRESET_VALUE_BYTES) {
    errors.value = 'value exceeds 256 KB'
    return errors
  }
  if (containsPlaceholder(row.value)) {
    const ph = checkPlaceholders(row.value)
    if (ph) errors.value = ph
  } else {
    const typed = checkTypedValue(row.type, row.value)
    if (typed) errors.value = typed
  }
  return errors.name || errors.value ? errors : null
}

/**
 * WO-VT-1 п.4 / §1-бис п.2-тер: «пусто = спросить при запуске».
 * Для STRING с allowEmptyString=true пустая строка — настоящее значение.
 */
export function isAskAtLaunch(v: Pick<PresetVariable, 'type' | 'value' | 'allowEmptyString'>): boolean {
  if (v.value !== '') return false
  if (v.type === 'STRING' && v.allowEmptyString === true) return false
  return true
}

/** Имена переменных, которые пользователь обязан заполнить сам. */
export function missingAskVariables(vars: PresetVariable[]): string[] {
  return vars.filter(isAskAtLaunch).map((v) => v.name)
}

/** Дубли имён в наборе (сервер требует уникальности). */
export function duplicateVariableNames(vars: Array<{ name: string }>): string[] {
  const seen = new Set<string>()
  const dupes = new Set<string>()
  for (const v of vars) {
    const n = v.name.trim()
    if (!n) continue
    if (seen.has(n)) dupes.add(n)
    seen.add(n)
  }
  return [...dupes]
}

let seqCounter = 1

/** Сброс счётчика `{{seq}}` (тесты; в проде монотонно растёт в сессии). */
export function resetPresetSeqCounter(): void {
  seqCounter = 1
}

function expandNow(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const off = -d.getTimezoneOffset()
  const sign = off >= 0 ? '+' : '-'
  const abs = Math.abs(off)
  const tz = `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}${tz}`
}

function randomLong(): string {
  return String(Math.floor(Math.random() * Number.MAX_SAFE_INTEGER))
}

function randomUuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

/**
 * Разворачивание плейсхолдеров при «Применить» (FEEL `{{…}}` во втором движке
 * не исполняем — разворачивает клиент, см. решение CTO по WO-ENG-32 вопрос 3).
 * Неизвестные/рваные скобки оставляются как есть: их уже отклонил валидатор
 * при сохранении шаблона, здесь — только подстановка известных.
 */
export function expandPlaceholders(value: string): string {
  return value.replace(/\{\{\s*(uuid|now|random:long|seq)\s*\}\}/g, (_, name: string) => {
    switch (name) {
      case 'uuid':
        return randomUuid()
      case 'now':
        return expandNow()
      case 'random:long':
        return randomLong()
      case 'seq':
        return String(seqCounter++)
      default:
        return _
    }
  })
}

/** Переменные, готовые к отправке: плейсхолдеры развёрнуты, типы — как есть. */
export function applyPresetVariables(vars: PresetVariable[]): PresetVariable[] {
  return vars.map((v) => ({ ...v, value: isAskAtLaunch(v) ? v.value : expandPlaceholders(v.value) }))
}

/** Таблица → сырой JSON (переключатель «Таблица | Сырой JSON»). */
export function variablesToRawJson(vars: PresetVariable[]): string {
  return JSON.stringify(
    vars.map((v) => ({
      name: v.name,
      type: v.type,
      value: v.value,
      ...(v.allowEmptyString === true ? { allowEmptyString: true } : {}),
    })),
    null,
    2,
  )
}

export interface RawJsonParse {
  variables?: PresetVariable[]
  error?: string
}

/** Сырой JSON → таблица. Принимает массив {name,type,value[,allowEmptyString]}. */
export function rawJsonToVariables(raw: string): RawJsonParse {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { error: 'not valid JSON' }
  }
  if (!Array.isArray(parsed)) return { error: 'must be a JSON array' }
  if (parsed.length > MAX_PRESET_VARIABLES) {
    return { error: `too many variables (max ${MAX_PRESET_VARIABLES}, got ${parsed.length})` }
  }
  const out: PresetVariable[] = []
  for (let i = 0; i < parsed.length; i++) {
    const row = parsed[i] as Record<string, unknown>
    if (!row || typeof row !== 'object') return { error: `variables[${i}] must be an object` }
    if (typeof row.name !== 'string' || !row.name.trim()) {
      return { error: `variables[${i}].name is required` }
    }
    if (typeof row.type !== 'string' || !['STRING', 'UUID', 'LONG', 'DOUBLE', 'BOOLEAN', 'JSON'].includes(row.type)) {
      return { error: `variables[${i}].type is required (STRING|UUID|LONG|DOUBLE|BOOLEAN|JSON)` }
    }
    if (typeof row.value !== 'string') return { error: `variables[${i}].value is required (string)` }
    out.push({
      name: row.name,
      type: row.type as PresetVariable['type'],
      value: row.value,
      ...(row.allowEmptyString === true ? { allowEmptyString: true } : {}),
    })
  }
  return { variables: out }
}

/**
 * Клик по элементу диаграммы → (kind, ref) шаблона. START у старт-события
 * привязывается без ref (сервер требует пустой ref для START); остальным —
 * ref = id элемента. Сообщения/DMN/incident/ad-hoc создаются из своих
 * страниц, не с диаграммы (там ref вводит пользователь).
 */
export function elementToPresetTarget(node: Pick<BpmnNode, 'id' | 'type'>): {
  kind: PresetTargetKind
  ref: string | null
} | null {
  switch (node.type) {
    case 'startEvent':
      return { kind: 'START', ref: null }
    case 'userTask':
      return { kind: 'USER_TASK', ref: node.id }
    case 'serviceTask':
      return { kind: 'SERVICE_TASK', ref: node.id }
    default:
      // Остальные элементы (шлюзы, события-сообщения, подпроцессы…) не имеют
      // своего targetKind на диаграмме: MESSAGE/DMN/INCIDENT/ADHOC_JOB
      // создаются из своих страниц, где ref вводит пользователь.
      return null
  }
}

/** Человекочитаемая метка вида (для списков/фильтров — ключи i18n снаружи). */
export function presetTargetKinds(): PresetTargetKind[] {
  return ['START', 'USER_TASK', 'SERVICE_TASK', 'MESSAGE', 'INCIDENT', 'DMN', 'ADHOC_JOB']
}
