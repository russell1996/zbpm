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
