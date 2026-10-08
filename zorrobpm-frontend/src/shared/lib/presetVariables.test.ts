// @vitest-environment node
/**
 * WO-VT-1 (фронт): табличные тесты чистой логики шаблонов. Зеркалят серверный
 * VariablePresetValidator (§4 WO: валидация теми же правилами, что в движке).
 */
import { describe, it, expect, beforeEach } from 'vitest'
import {
  checkTypedValue,
  checkPlaceholders,
  containsPlaceholder,
  validatePresetRow,
  isAskAtLaunch,
  missingAskVariables,
  duplicateVariableNames,
  expandPlaceholders,
  applyPresetVariables,
  variablesToRawJson,
  rawJsonToVariables,
  elementToPresetTarget,
  resetPresetSeqCounter,
  MAX_PRESET_VARIABLES,
} from './presetVariables'
import type { PresetVariable } from '@/types/presets'

function v(
  name: string,
  type: PresetVariable['type'],
  value: string,
  allowEmptyString?: boolean | null,
): PresetVariable {
  return { name, type, value, allowEmptyString }
}

describe('checkTypedValue — таблица по КАЖДОМУ типу (как в движке)', () => {
  const cases: Array<{
    type: PresetVariable['type']
    valid: string[]
    invalid: string[]
  }> = [
    { type: 'LONG', valid: ['0', '-5', '42', '9223372036854775807'], invalid: ['4.2', 'abc', '1e3', ''] },
    { type: 'DOUBLE', valid: ['4.2', '-0.5', '1e3', '42'], invalid: ['abc', 'NaN', 'Infinity'] },
    { type: 'BOOLEAN', valid: ['true', 'false'], invalid: ['yes', '1', 'True '] },
    {
      type: 'UUID',
      valid: ['123e4567-e89b-12d3-a456-426614174000'],
      invalid: ['not-a-uuid', '123', ''],
    },
    { type: 'JSON', valid: ['{}', '{"a":[1,2]}', '"str"', '42'], invalid: ['{bad', ''] },
    { type: 'STRING', valid: ['', 'anything', '123', '{{uuid}}'], invalid: [] },
  ]
  for (const c of cases) {
    for (const value of c.valid) {
      // NOTE: '' для LONG/UUID/JSON — «спросить при запуске», тоже валидно.
      const val = value === '' && c.type !== 'STRING' ? '(empty=ask)' : value
      it(`${c.type} accepts ${val}`, () => {
        expect(checkTypedValue(c.type, value)).toBeNull()
      })
    }
    for (const value of c.invalid.filter((x) => x !== '')) {
      it(`${c.type} rejects '${value}'`, () => {
        expect(checkTypedValue(c.type, value)).not.toBeNull()
      })
    }
  }

  it('empty value is allowed for EVERY type (ask-at-launch, формат не проверяется)', () => {
    for (const t of ['LONG', 'DOUBLE', 'BOOLEAN', 'UUID', 'JSON', 'STRING'] as const) {
      expect(checkTypedValue(t, '')).toBeNull()
    }
  })
})

describe('placeholders', () => {
  it('containsPlaceholder — маркеры {{ и }}', () => {
    expect(containsPlaceholder('prefix-{{uuid}}')).toBe(true)
    expect(containsPlaceholder('has }} only')).toBe(true)
    expect(containsPlaceholder('plain')).toBe(false)
  })

  it('known placeholders pass', () => {
    for (const ph of ['{{uuid}}', '{{now}}', '{{random:long}}', '{{seq}}', 'a-{{uuid}}-b']) {
      expect(checkPlaceholders(ph)).toBeNull()
    }
  })

  it('unknown and unbalanced braces fail', () => {
    expect(checkPlaceholders('{{nope}}')).toContain('unknown placeholder')
    expect(checkPlaceholders('{{uuid}')).toContain('unbalanced')
    expect(checkPlaceholders('plain }}')).toContain('unbalanced')
    expect(checkPlaceholders('{{uuid')).toContain('unbalanced')
  })

  it('expandPlaceholders resolves every known placeholder', () => {
    resetPresetSeqCounter()
    const uuid = expandPlaceholders('{{uuid}}')
    expect(uuid).toMatch(/^[0-9a-f-]{36}$/)
    const now = expandPlaceholders('{{now}}')
    expect(now).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/)
    const rnd = expandPlaceholders('{{random:long}}')
    expect(rnd).toMatch(/^\d+$/)
    expect(expandPlaceholders('a-{{seq}}-{{seq}}')).toBe('a-1-2')
  })
})

describe('validatePresetRow', () => {
  it('empty name + empty value is a draft row, not an error', () => {
    expect(validatePresetRow({ name: '', type: 'STRING', value: '' })).toBeNull()
  })
  it('empty name with a value requires a name', () => {
    expect(validatePresetRow({ name: '  ', type: 'STRING', value: 'x' })?.name).toBeTruthy()
  })
  it('allowEmptyString on non-STRING is rejected (как сервер)', () => {
    expect(
      validatePresetRow({ name: 'n', type: 'LONG', value: '', allowEmptyString: true })?.value,
    ).toContain('only for STRING')
  })
  it('placeholder bypasses the type check (тип проверит клиент после подстановки)', () => {
    expect(validatePresetRow({ name: 'u', type: 'UUID', value: '{{uuid}}' })).toBeNull()
    expect(validatePresetRow({ name: 'n', type: 'LONG', value: '{{seq}}' })).toBeNull()
  })
  it('bad placeholder fails even on STRING', () => {
    expect(validatePresetRow({ name: 's', type: 'STRING', value: '{{nope}}' })?.value).toContain(
      'unknown placeholder',
    )
  })
  it('256 KB limit', () => {
    expect(validatePresetRow({ name: 's', type: 'STRING', value: 'x'.repeat(256 * 1024 + 1) })?.value).toContain(
      '256 KB',
    )
  })
})

describe('ask-at-launch (§1-бис п.2-тер)', () => {
  it('empty value of any type is "ask", except STRING with allowEmptyString', () => {
    expect(isAskAtLaunch(v('a', 'LONG', ''))).toBe(true)
    expect(isAskAtLaunch(v('b', 'UUID', ''))).toBe(true)
    expect(isAskAtLaunch(v('c', 'STRING', ''))).toBe(true)
    expect(isAskAtLaunch(v('d', 'STRING', '', true))).toBe(false)
    expect(isAskAtLaunch(v('e', 'STRING', 'x'))).toBe(false)
    expect(isAskAtLaunch(v('f', 'LONG', '{{seq}}'))).toBe(false)
  })

  it('missingAskVariables lists exactly the ask-fields', () => {
    const vars = [v('a', 'LONG', ''), v('b', 'STRING', 'x'), v('c', 'STRING', '', true)]
    expect(missingAskVariables(vars)).toEqual(['a'])
  })

  it('applyPresetVariables expands placeholders but keeps ask-fields empty', () => {
    resetPresetSeqCounter()
    const out = applyPresetVariables([v('u', 'UUID', '{{uuid}}'), v('n', 'LONG', ''), v('s', 'LONG', '{{seq}}')])
    expect(out[0].value).toMatch(/^[0-9a-f-]{36}$/)
    expect(out[1].value).toBe('')
    expect(out[2].value).toBe('1')
  })
})

describe('duplicates and limits', () => {
  it('duplicateVariableNames finds trimmed dupes', () => {
    expect(duplicateVariableNames([{ name: 'a' }, { name: ' a ' }, { name: 'b' }])).toEqual(['a'])
    expect(duplicateVariableNames([{ name: 'a' }, { name: 'b' }])).toEqual([])
  })
})

describe('raw JSON toggle', () => {
  it('round-trip table → raw → table keeps allowEmptyString', () => {
    const vars = [v('a', 'STRING', '', true), v('n', 'LONG', '5')]
    const raw = variablesToRawJson(vars)
    expect(rawJsonToVariables(raw).variables).toEqual(vars)
  })
  it('raw parse rejects non-array, bad rows, over-limit', () => {
    expect(rawJsonToVariables('{}').error).toBeTruthy()
    expect(rawJsonToVariables('[{"name":"","type":"STRING","value":""}]').error).toContain('.name')
    expect(rawJsonToVariables('[{"name":"a","type":"NOPE","value":""}]').error).toContain('.type')
    expect(rawJsonToVariables('[{"name":"a","type":"STRING"}]').error).toContain('.value')
    expect(rawJsonToVariables('not json').error).toBeTruthy()
    const big = `[${Array.from({ length: MAX_PRESET_VARIABLES + 1 }, (_, i) => `{"name":"v${i}","type":"STRING","value":""}`).join(',')}]`
    expect(rawJsonToVariables(big).error).toContain('too many variables')
  })
})

describe('elementToPresetTarget', () => {
  it('maps start/user/service elements, null otherwise', () => {
    expect(elementToPresetTarget({ id: 's', type: 'startEvent' })).toEqual({ kind: 'START', ref: null })
    expect(elementToPresetTarget({ id: 't1', type: 'userTask' })).toEqual({ kind: 'USER_TASK', ref: 't1' })
    expect(elementToPresetTarget({ id: 's1', type: 'serviceTask' })).toEqual({ kind: 'SERVICE_TASK', ref: 's1' })
    expect(elementToPresetTarget({ id: 'g1', type: 'exclusiveGateway' })).toBeNull()
    expect(elementToPresetTarget({ id: 'e1', type: 'endEvent' })).toBeNull()
  })
})

describe('POF sanity: the suite actually executes', () => {
  beforeEach(() => resetPresetSeqCounter())
  it(' suite is non-empty', () => {
    expect(true).toBe(true)
  })
})
