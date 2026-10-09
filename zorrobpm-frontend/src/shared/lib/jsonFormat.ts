/**
 * WO-UI-27 п.2 — токенный JSON-форматтер без потерь.
 *
 * НЕ JSON.parse→stringify: parse необратимо округляет числа > 2^53
 * (`12345678901234567890` → `12345678901234568000`), переписывает `1.0`→`1`
 * и `1e3`→`1000`. Здесь лексер идёт по ТЕКСТУ: строки/числа/литералы
 * копируются побайтово, меняются только пробелы вне строк. Поэтому
 * `minify(format(x)) === x` дословно для любого валидного входа.
 *
 * WO-UI-27 раунд 2 (Б-1): лексер резал атомы по классу символов
 * (`[a-zA-Z0-9.+-eE]+`) и сборка принимала любой атом значением без проверки
 * конца и единственности top-level — `tru`, `01`, `1.`, `-.5`, `0x10`, хвосты
 * вида `{} {}` считались валидными. Здесь — строгая валидация по RFC 8259
 * между лексером и сборкой: атомы только `true|false|null`, число по
 * грамматике JSON, ровно ОДНО top-level значение без хвоста, строки без
 * сырых управляющих символов и с корректными escape. Ошибка — с позицией
 * строка:столбец. Числа/строки по-прежнему копируются побайтово.
 */

export interface JsonFormatResult {
  text: string | null
  error: string | null
}

export interface JsonErrorPos {
  line: number
  column: number
  message: string
}

interface Token {
  kind: 'brace-open' | 'brace-close' | 'bracket-open' | 'bracket-close' | 'colon' | 'comma' | 'atom'
  text: string
  /** Позиция начала токена (WO-UI-27 раунд 2: для ошибки строка:столбец). */
  line: number
  col: number
}

function isDigit(ch: string): boolean {
  return ch >= '0' && ch <= '9'
}

function isAtomChar(ch: string): boolean {
  return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || isDigit(ch) || ch === '.' || ch === '-' || ch === '+' || ch === 'e' || ch === 'E'
}

/** Лексер: режет текст на структурные токены и атомы (строки/числа/литералы — как есть). */
function lex(src: string): { tokens: Token[]; error: JsonErrorPos | null } {
  const tokens: Token[] = []
  let i = 0
  let line = 1
  let col = 1

  const err = (message: string): { tokens: Token[]; error: JsonErrorPos } => ({
    tokens,
    error: { line, column: col, message: `строка ${line}:${col}: ${message}` },
  })

  while (i < src.length) {
    const ch = src[i]
    if (ch === '\n') {
      line += 1
      col = 1
      i += 1
      continue
    }
    if (ch === ' ' || ch === '\t' || ch === '\r') {
      col += 1
      i += 1
      continue
    }
    if (ch === '{') {
      tokens.push({ kind: 'brace-open', text: ch, line, col })
    } else if (ch === '}') {
      tokens.push({ kind: 'brace-close', text: ch, line, col })
    } else if (ch === '[') {
      tokens.push({ kind: 'bracket-open', text: ch, line, col })
    } else if (ch === ']') {
      tokens.push({ kind: 'bracket-close', text: ch, line, col })
    } else if (ch === ':') {
      tokens.push({ kind: 'colon', text: ch, line, col })
    } else if (ch === ',') {
      tokens.push({ kind: 'comma', text: ch, line, col })
    } else if (ch === '"') {
      // Строка — побайтово до закрывающей кавычки с учётом экранов.
      let j = i + 1
      let closed = false
      while (j < src.length) {
        const c = src[j]
        if (c === '\\') {
          j += 2
          continue
        }
        if (c === '"') {
          closed = true
          break
        }
        if (c === '\n') {
          return err('перенос строки внутри строки')
        }
        j += 1
      }
      if (!closed) return err('незакрытая строка')
      const tokLine = line
      const tokCol = col
      tokens.push({ kind: 'atom', text: src.slice(i, j + 1), line: tokLine, col: tokCol })
      // Колонки внутри строки не считаем посимвольно точно — ошибка дальше
      // всё равно привяжется к позиции токена; двигаем грубо.
      const consumed = src.slice(i, j + 1)
      col += consumed.length
      i = j + 1
      continue
    } else if (ch === '-' || isDigit(ch) || ch === 't' || ch === 'f' || ch === 'n') {
      // Число или литерал true/false/null — до первого не-атомного символа.
      // Строгая проверка значения — позже в validateTokens (RFC 8259);
      // здесь режем сырьём, чтобы ошибка указывала на весь атом целиком.
      let j = i
      while (j < src.length && isAtomChar(src[j])) j += 1
      tokens.push({ kind: 'atom', text: src.slice(i, j), line, col })
      col += j - i
      i = j
      continue
    } else {
      return err(`неожиданный символ '${ch}'`)
    }
    col += 1
    i += 1
  }
  return { tokens, error: null }
}

/** Число по грамматике RFC 8259 §6: -?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)? */
const JSON_NUMBER_RE = /^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$/

function isValidJsonNumber(text: string): boolean {
  return JSON_NUMBER_RE.test(text)
}

/**
 * WO-UI-27 раунд 2 (Б-1): содержимое строки (включая кавычки) по RFC 8259 §7:
 * никаких сырых управляющих символов (< 0x20 — в т.ч. табуляция), escape
 * только из набора `" \ / b f n r t uXXXX` (u — ровно 4 hex-цифры).
 * Возвращает текст причины или null, если строка корректна.
 */
function stringContentError(text: string): string | null {
  for (let i = 1; i < text.length - 1; i++) {
    const ch = text[i]
    if (ch === '\\') {
      const n = text[i + 1]
      if (n === undefined) return 'плохой escape в строке'
      if (n === 'u') {
        if (!/^[0-9a-fA-F]{4}$/.test(text.slice(i + 2, i + 6))) return 'плохой \\u-escape в строке'
        i += 5
      } else if ('"\\/bfnrt'.includes(n)) {
        i += 1
      } else {
        return `плохой escape '\\${n}' в строке`
      }
    } else if (ch < ' ') {
      return 'управляющий символ в строке без escape'
    }
  }
  return null
}

/**
 * WO-UI-27 раунд 2 (Б-1): строгая валидация потока токенов по RFC 8259.
 * Проверяет: атомы — только `true|false|null` или число по грамматике
 * (режет `tru`, `nul`, `[truely]`, `01`, `1.`, `-.5`, `0x10`); ключи объекта —
 * только строки; ровно ОДНО top-level значение без хвоста (режет `{} {}`,
 * `[1] [2]`); запятые/двоеточия/скобки — на своих местах. Пустой поток —
 * не ошибка здесь (о нём сообщает build). Числа и строки НЕ переписываются —
 * только проверка, побайтовость формата не страдает.
 */
function validateTokens(tokens: Token[]): JsonErrorPos | null {
  const fail = (t: Token, message: string): JsonErrorPos => ({
    line: t.line,
    column: t.col,
    message: `строка ${t.line}:${t.col}: ${message}`,
  })
  if (!tokens.length) return null

  const stack: Array<'object' | 'array'> = []
  // Состояние верхнего контейнера: need-key (после `{`/`,` в объекте),
  // need-colon (после ключа), need-value (после `:`),
  // need-value-or-end (после `[`/`,` в массиве),
  // need-comma-or-end (после завершённого значения).
  const states: string[] = []
  let topDone = false

  for (const t of tokens) {
    if (topDone) return fail(t, 'лишний текст после значения')
    const depth = stack.length
    const curType = depth ? stack[depth - 1] : null
    const curState = depth ? states[depth - 1] : null
    switch (t.kind) {
      case 'brace-open':
      case 'bracket-open': {
        const want = t.kind === 'brace-open' ? 'object' : 'array'
        const ok =
          !curType ||
          (curType === 'object' && curState === 'need-value') ||
          (curType === 'array' && curState === 'need-value-or-end')
        if (!ok) return fail(t, `'${t.text}' не ожидается здесь`)
        stack.push(want)
        states.push(want === 'object' ? 'need-key' : 'need-value-or-end')
        break
      }
      case 'brace-close': {
        if (curType !== 'object' || (curState !== 'need-key' && curState !== 'need-comma-or-end')) {
          return fail(t, `лишняя '}'`)
        }
        stack.pop()
        states.pop()
        if (!stack.length) topDone = true
        else states[states.length - 1] = 'need-comma-or-end'
        break
      }
      case 'bracket-close': {
        if (curType !== 'array' || (curState !== 'need-value-or-end' && curState !== 'need-comma-or-end')) {
          return fail(t, `лишняя ']'`)
        }
        stack.pop()
        states.pop()
        if (!stack.length) topDone = true
        else states[states.length - 1] = 'need-comma-or-end'
        break
      }
      case 'colon': {
        if (curType !== 'object' || curState !== 'need-colon') return fail(t, `':' без ключа`)
        states[states.length - 1] = 'need-value'
        break
      }
      case 'comma': {
        const ok =
          (curType === 'object' && curState === 'need-comma-or-end') ||
          (curType === 'array' && curState === 'need-comma-or-end')
        if (!ok) return fail(t, `',' без значения`)
        states[states.length - 1] = curType === 'object' ? 'need-key' : 'need-value-or-end'
        break
      }
      case 'atom': {
        const isStr = t.text[0] === '"'
        if (isStr) {
          const se = stringContentError(t.text)
          if (se) return fail(t, se)
        } else if (t.text !== 'true' && t.text !== 'false' && t.text !== 'null' && !isValidJsonNumber(t.text)) {
          return fail(t, `невалидный литерал '${t.text}'`)
        }
        if (!curType) {
          topDone = true
          break
        }
        if (curType === 'object') {
          if (curState === 'need-key') {
            if (!isStr) return fail(t, 'ключ объекта должен быть строкой')
            states[states.length - 1] = 'need-colon'
          } else if (curState === 'need-value') {
            states[states.length - 1] = 'need-comma-or-end'
          } else {
            return fail(t, 'значение не ожидается здесь')
          }
        } else {
          if (curState !== 'need-value-or-end') return fail(t, 'значение не ожидается здесь')
          states[states.length - 1] = 'need-comma-or-end'
        }
        break
      }
    }
  }
  if (stack.length || !topDone) {
    const last = tokens[tokens.length - 1]
    return fail(last, 'незакрытая скобка')
  }
  return null
}

/** Структурная проверка + сборка. `pretty=true` — с отступами, иначе в одну строку. */
function build(tokens: Token[], pretty: boolean): { text: string | null; error: JsonErrorPos | null } {
  if (!tokens.length) {
    return { text: null, error: { line: 1, column: 1, message: 'строка 1:1: пустой ввод — не JSON' } }
  }
  const stack: string[] = []
  let out = ''
  let indent = 0
  // Позиция для ошибки: считаем по токенам грубо (строка = число \n в out + 1).
  const pos = (): JsonErrorPos => {
    const line = out.split('\n').length
    const lastNl = out.lastIndexOf('\n')
    const column = out.length - lastNl
    return { line, column, message: `строка ${line}:${column}: обрыв JSON` }
  }

  const nl = (extra = 0): string => (pretty ? '\n' + '  '.repeat(indent + extra) : '')
  let prev: Token | null = null
  // Контекст: после `{`/`[` смотрим, пустой ли контейнер (сразу `}`/`]`).
  for (let k = 0; k < tokens.length; k++) {
    const t = tokens[k]
    switch (t.kind) {
      case 'brace-open':
      case 'bracket-open': {
        const opener = t.kind === 'brace-open' ? '{' : '['
        // Значение после `:` — без переноса (`"k": {`), иначе с новой строки.
        if (prev && (prev.kind === 'colon' || prev.kind === 'comma' || !prev)) {
          out += (prev && prev.kind === 'colon' ? (pretty ? ' ' : '') : '') + opener
        } else if (!prev) {
          out += opener
        } else {
          // Массив как значение массива: `[..., [...]` — после запятой уже перенос.
          out += opener
        }
        if (pretty) {
          // Заглядываем: пустой контейнер — без переноса (`{}`, `[]`).
          const next = tokens[k + 1]
          const closes =
            (t.kind === 'brace-open' && next?.kind === 'brace-close') ||
            (t.kind === 'bracket-open' && next?.kind === 'bracket-close')
          if (!closes) {
            indent += 1
            out += '\n' + '  '.repeat(indent)
          }
        }
        stack.push(t.kind)
        break
      }
      case 'brace-close':
      case 'bracket-close': {
        const want = t.kind === 'brace-close' ? 'brace-open' : 'bracket-open'
        const got = stack.pop()
        if (got !== want) {
          const p = pos()
          return { text: null, error: { ...p, message: `${p.message} — лишняя '${t.text}'` } }
        }
        // `{"k": }` / `[1, ]` — значение после ':' или ',' отсутствует.
        if (prev && (prev.kind === 'colon' || prev.kind === 'comma')) {
          const p = pos()
          return { text: null, error: { ...p, message: `${p.message} — нет значения` } }
        }
        if (pretty) {
          // Пустой контейнер уже закрыт компактно; иначе — перенос + dedent.
          const wasEmpty = prev && ((prev.kind === 'brace-open' && t.kind === 'brace-close') || (prev.kind === 'bracket-open' && t.kind === 'bracket-close'))
          if (!wasEmpty) {
            indent = Math.max(0, indent - 1)
            out += '\n' + '  '.repeat(indent)
          }
        }
        out += t.text
        break
      }
      case 'colon': {
        if (!prev || (prev.kind !== 'atom')) {
          const p = pos()
          return { text: null, error: { ...p, message: `${p.message} — ':' без ключа` } }
        }
        if (!pretty) out += ':'
        // В pretty-режиме пробел после ':' добавляется потребителем
        // (значение/скобка), здесь — только маркер.
        if (pretty) out += ':'
        break
      }
      case 'comma': {
        if (!prev || prev.kind === 'comma' || prev.kind === 'colon' || prev.kind === 'brace-open' || prev.kind === 'bracket-open') {
          const p = pos()
          return { text: null, error: { ...p, message: `${p.message} — ',' без значения` } }
        }
        out += ','
        if (pretty) out += '\n' + '  '.repeat(indent)
        break
      }
      case 'atom': {
        // Два атома подряд без разделителя — обрыв (`{1 2}`, `{"a" "b"}`).
        if (prev && prev.kind === 'atom') {
          const p = pos()
          return { text: null, error: { ...p, message: `${p.message} — нет разделителя` } }
        }
        // Пробел после ':' в pretty-режиме.
        if (pretty && prev && prev.kind === 'colon') out += ' '
        out += t.text
        break
      }
    }
    prev = t
  }
  if (stack.length) {
    const p = pos()
    return { text: null, error: { ...p, message: `${p.message} — незакрытая скобка` } }
  }
  if (prev && (prev.kind === 'comma' || prev.kind === 'colon')) {
    const p = pos()
    return { text: null, error: { ...p, message: `${p.message} — висячий разделитель` } }
  }
  return { text: out, error: null }
}

/**
 * Отформатировать JSON с отступом 2 пробела. Числа/строки/литералы — побайтово
 * из исходника; меняется только оформление. Невалидный ввод → { text: null,
 * error } (текст пользователя не трогаем — решает вызывающий).
 */
export function formatJsonValue(src: string): JsonFormatResult {
  const { tokens, error } = lex(src)
  if (error) return { text: null, error: error.message }
  const verr = validateTokens(tokens)
  if (verr) return { text: null, error: verr.message }
  const built = build(tokens, true)
  if (built.error) return { text: null, error: built.error.message }
  return { text: built.text ?? '', error: null }
}

/** Свернуть JSON в одну строку без изменения токенов. */
export function minifyJsonValue(src: string): JsonFormatResult {
  const { tokens, error } = lex(src)
  if (error) return { text: null, error: error.message }
  const verr = validateTokens(tokens)
  if (verr) return { text: null, error: verr.message }
  const built = build(tokens, false)
  if (built.error) return { text: null, error: built.error.message }
  return { text: built.text ?? '', error: null }
}

/** Позиция первой ошибки (для подсветки «строка:столбец»), null если валиден. */
export function jsonErrorAt(src: string): JsonErrorPos | null {
  const { tokens, error } = lex(src)
  if (error) return error
  const verr = validateTokens(tokens)
  if (verr) return verr
  const built = build(tokens, true)
  return built.error
}
