// @vitest-environment node
/**
 * WO-UI-27 п.2 — токенный JSON-форматтер без потерь.
 *
 * НЕ JSON.parse→stringify: parse теряет точность чисел > 2^53, переписывает
 * `1.0`→`1`, `1e3`→`1000`. Форматтер — лексер по тексту (строки/числа/
 * структура побайтово), меняет только пробелы вне строк.
 * RED: модуля jsonFormat.ts нет — весь файл красный до реализации.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { formatJsonValue, minifyJsonValue, jsonErrorAt } from './jsonFormat'

const ownerDir = resolve(dirname(fileURLToPath(import.meta.url)), '../../test/fixtures')
const ownerJson: string = readFileSync(resolve(ownerDir, 'stages-owner.json'), 'utf-8')

describe('WO-UI-27 token-based JSON formatter', () => {
  it('owner fixture is a single line ≥3KB with cyrillic and >2^53 numbers', () => {
    expect(ownerJson.trim().split('\n')).toHaveLength(1)
    expect(new TextEncoder().encode(ownerJson).length).toBeGreaterThanOrEqual(3 * 1024)
    expect(ownerJson).toMatch(/[А-Яа-яЁё]/)
    expect(ownerJson).toContain('12345678901234567890')
  })

  it('formats the owner single-line JSON into >20 lines', () => {
    const out = formatJsonValue(ownerJson)
    expect(out.error).toBeNull()
    const lines = (out.text ?? '').split('\n')
    expect(lines.length).toBeGreaterThan(20)
  })

  it('round-trips owner JSON byte-identically (minify of formatted == source)', () => {
    const formatted = formatJsonValue(ownerJson).text ?? ''
    const back = minifyJsonValue(formatted)
    expect(back.error).toBeNull()
    expect(back.text).toBe(ownerJson.trim())
  })

  it('preserves big integers, 1.0, 1e3, -0 byte-for-byte', () => {
    for (const num of ['12345678901234567890', '1.0', '1e3', '-0', '9007199254740993']) {
      const src = `{"n":${num}}`
      const out = formatJsonValue(src)
      expect(out.error).toBeNull()
      expect(out.text).toContain(`"n": ${num}`)
      const back = minifyJsonValue(out.text ?? '')
      expect(back.text).toBe(src)
    }
  })

  it('preserves strings with escapes, unicode and quotes verbatim', () => {
    const src = '{"a":"line\\nbreak","b":"\\u0422\\u044b\\u0449\\u0435\\u043d\\u043a\\u043e","c":"say \\"hi\\"","d":"{}[]:, "}'
    const out = formatJsonValue(src)
    expect(out.error).toBeNull()
    expect(minifyJsonValue(out.text ?? '').text).toBe(src)
  })

  it('minify compacts pretty JSON to one line without touching tokens', () => {
    const pretty = '{\n  "n": 12345678901234567890,\n  "s": "a  b"\n}'
    const out = minifyJsonValue(pretty)
    expect(out.error).toBeNull()
    expect(out.text).toBe('{"n":12345678901234567890,"s":"a  b"}')
  })

  it('invalid JSON: error with line:col, text untouched', () => {
    const bad = '{\n  "a": 1,\n  "b": ,\n}'
    const out = formatJsonValue(bad)
    expect(out.text).toBeNull()
    expect(out.error).not.toBeNull()
    expect(out.error ?? '').toMatch(/строка 3|line 3/i)
    expect(out.error ?? '').toMatch(/:\d+/)
  })

  it('empty/blank input is an error, not a silent empty object', () => {
    expect(formatJsonValue('').error).not.toBeNull()
    expect(formatJsonValue('   \n  ').error).not.toBeNull()
  })

  it('mutation guard: parse/stringify would lose the big integer', () => {
    // Доказательство, что старый путь (JSON.parse→stringify) здесь красный:
    // parse уже необратимо округлил число — токенный форматтер обязан не так.
    const lossy = JSON.stringify(JSON.parse('{"n":12345678901234567890}'))
    expect(lossy).not.toContain('12345678901234567890')
    expect(formatJsonValue('{"n":12345678901234567890}').text).toContain('12345678901234567890')
  })
})

describe('WO-UI-27 JSON error position', () => {
  it('jsonErrorAt reports line:col of a parse failure', () => {
    const bad = '{\n"a": 1,\n"x": }'
    const err = jsonErrorAt(bad)
    expect(err).not.toBeNull()
    expect(err?.line).toBe(3)
    expect(typeof err?.column).toBe('number')
  })

  it('valid JSON has no error position', () => {
    expect(jsonErrorAt('{"a":[1,2]}')).toBeNull()
  })
})

/**
 * WO-UI-27 раунд 2 (Б-1): строгая валидация по RFC 8259. Красная команда r1
 * показала, что лексер принимал за валидные 9 классов невалидного JSON
 * (атом резался по классу символов, конец/единственность top-level не
 * проверялись). Каждый класс ниже — постоянный тест: `formatJsonValue`
 * обязан вернуть ошибку (как бросает строгий `JSON.parse`), ошибка — с
 * позицией строка:столбец, кнопки по `jsonValid()` гаснут.
 */
describe('WO-UI-27 strict RFC 8259 validation (round 2, B-1)', () => {
  const invalidNine = [
    '{} {}', // хвост после top-level значения
    '[1] [2]', // второе top-level значение
    '{"a": tru}', // оборванный литерал true
    'nul', // оборванный литерал null
    '[truely]', // литерал с хвостом
    '{"a": 01}', // ведущий ноль
    '{"a": 1.}', // точка без дробной части
    '{"a": -.5}', // дробь без целой части
    '{"a": 0x10}', // hex — не JSON
    '{"a": 1.}', // дубль-проверка точки в объекте
  ]
  it.each(invalidNine)('rejects %p with a line:col error (JSON.parse throws too)', (src) => {
    expect(() => JSON.parse(src)).toThrow()
    const out = formatJsonValue(src)
    expect(out.text).toBeNull()
    expect(out.error).not.toBeNull()
    expect(out.error ?? '').toMatch(/строка \d+:\d+/)
    expect(jsonErrorAt(src)).not.toBeNull()
    expect(minifyJsonValue(src).error).not.toBeNull()
  })

  it('each rejected class also reports a usable line:col position', () => {
    const bad = '{\n  "a": tru,\n  "b": 1\n}'
    const err = jsonErrorAt(bad)
    expect(err).not.toBeNull()
    expect(err?.line).toBe(2)
    expect(typeof err?.column).toBe('number')
  })

  const validCounterparts = [
    '{}',
    '[1]',
    '{"a": true}',
    'null',
    '[true]',
    '{"a": 0}',
    '{"a": 1.0}',
    '{"a": -0.5}',
    '{"a": 16}',
    '{"a": 1e3}',
    '{"n": 12345678901234567890}',
  ]
  it.each(validCounterparts)('accepts valid counterpart %p byte-preserving', (src) => {
    expect(() => JSON.parse(src)).not.toThrow()
    const out = formatJsonValue(src)
    expect(out.error).toBeNull()
    // Числа/строки — побайтово: minify(формата) == minify(входа) дословно.
    expect(minifyJsonValue(out.text ?? '').text).toBe(minifyJsonValue(src).text)
    // Токен числа цел: большое число не округлено.
    if (src.includes('12345678901234567890')) expect(out.text).toContain('12345678901234567890')
  })

  it('rejects bad string escapes and raw control chars like JSON.parse', () => {
    for (const src of ['{"a": "x\\q"}', '{"a": "x\\u12"}', '{"a": "x\\u12zz"}', '{"a": "a\tb"}']) {
      expect(() => JSON.parse(src)).toThrow()
      expect(formatJsonValue(src).error, src).not.toBeNull()
    }
    // Легальные escape принимаются.
    const ok = '{"a": "x\\n\\t\\"\\\\\\/\\b\\f\\r\\u0422"}'
    expect(() => JSON.parse(ok)).not.toThrow()
    expect(formatJsonValue(ok).error).toBeNull()
  })

  /**
   * Оракул-сверка с JSON.parse: valid(f) === !throws(JSON.parse) на корпусе
   * ≥ 500 строк — фрагменты (валидные/невалидные), детерминированные
   * посимвольные мутации фикстуры stages (удаление/замена/вставка) и её
   * усечения. Никакой случайности — позиции и алфавит фиксированы индексами.
   */
  it('oracle: valid(formatJsonValue) === !throws(JSON.parse) on a ≥500-string corpus', () => {
    const corpus: string[] = [
      '{}',
      '[]',
      '{"a":1}',
      '[1,2,3]',
      '{"a":{"b":[true,false,null,0,-0,1.5,-2.5e-3,1E+10]}}',
      '"str"',
      '42',
      'true',
      'false',
      'null',
      '  \n {"a": 1} \n ',
      ...invalidNine,
      '{',
      '[',
      '{"a"',
      '{"a":',
      '{"a":1,}',
      '[1,]',
      '[,1]',
      '{"a" 1}',
      '{1: 2}',
      '{"a":01}',
      '{"a":1.}',
      '{"a":+.5}',
      '{"a":NaN}',
      '{"a":Infinity}',
      "{'a':1}",
      '{"a":undefined}',
      '{"a":0x10}',
      '{"a":"\\q"}',
      '{"a":"\\u12"}',
      '',
      '   ',
      'tru',
      'nul',
      'fals',
      '[truely]',
      '{} {}',
      '[1] [2]',
      '{"a":1} garbage',
    ]
    // Детерминированные мутации фикстуры stages: каждые ~11 символов —
    // удаление, замена (алфавит по индексу), вставка; плюс усечения.
    const alphabet = ['{', '}', '[', ']', ':', ',', '"', '0', '1', 'e', 'n', 'u', 't', 'r', '.', '-', ' ', 'x', '\\', 'f']
    const step = Math.max(1, Math.floor(ownerJson.length / 160))
    for (let p = 0; p < ownerJson.length; p += step) {
      corpus.push(ownerJson.slice(0, p) + ownerJson.slice(p + 1)) // удаление
      corpus.push(ownerJson.slice(0, p) + alphabet[(p / step) % alphabet.length | 0] + ownerJson.slice(p + 1)) // замена
      corpus.push(ownerJson.slice(0, p) + alphabet[(p * 7) % alphabet.length | 0] + ownerJson.slice(p)) // вставка
      corpus.push(ownerJson.slice(0, p)) // усечение
    }
    expect(corpus.length).toBeGreaterThanOrEqual(500)

    const parses = (s: string): boolean => {
      try {
        JSON.parse(s)
        return true
      } catch {
        return false
      }
    }
    const divergent: string[] = []
    for (const s of corpus) {
      const expected = parses(s)
      const f = formatJsonValue(s)
      const m = minifyJsonValue(s)
      const e = jsonErrorAt(s)
      const ours = f.error === null
      const mOurs = m.error === null
      const eOurs = e === null
      if (ours !== expected || mOurs !== expected || eOurs !== expected) {
        divergent.push(`${JSON.stringify(s.slice(0, 80))} parse=${expected} format=${ours} minify=${mOurs} errAt=${eOurs}`)
      }
    }
    expect(divergent.slice(0, 10)).toEqual([])
    expect(corpus.length).toBeGreaterThanOrEqual(500)
  })
})
