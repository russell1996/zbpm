// @vitest-environment node
/**
 * WO-UI-27 доп.2 (критерий 9): «Дублировать» убрано СОВСЕМ (код, i18n
 * en/ru/kz, тесты), подтверждений удаления переменной нет («Отменить
 * нельзя», confirm-плашки). Страж читает исходники widgets/presets/** и
 * locales/*.json БЕЗ комментариев и падает на запрещённых строках.
 * RED-мутация: вернуть пункт «Дублировать» или confirm → красный.
 * Исключение: ключ `presetDuplicateName` (валидация уникальности имён) —
 * легитимен, матчится только точный ключ действия `"presetDuplicate":`.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const presetsDir = resolve(dirname(fileURLToPath(import.meta.url)))
const localesDir = resolve(presetsDir, '../../locales')

function stripComments(src: string): string {
  return src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

const FORBIDDEN_SRC = [
  'Дублировать',
  'дублировать',
  'Отменить нельзя',
  'отменить нельзя',
  '"presetDuplicate":',
  'presetDuplicateRow',
  'presetDeleteRowConfirm',
  'window.confirm',
]

const FORBIDDEN_I18N = [
  'Дублировать',
  'дублировать',
  'Отменить нельзя',
  'отменить нельзя',
  'presetDuplicateRow',
  'presetDeleteRowConfirm',
]

describe('WO-UI-27 no-duplicate/no-confirm guard (criterion 9)', () => {
  it('presets sources contain no duplicate/confirm UI (comments stripped)', () => {
    const offenders: string[] = []
    for (const f of readdirSync(presetsDir).filter((x) => x.endsWith('.vue'))) {
      const clean = stripComments(readFileSync(join(presetsDir, f), 'utf-8'))
      for (const bad of FORBIDDEN_SRC) {
        if (clean.includes(bad)) offenders.push(`${f}: ${bad}`)
      }
    }
    expect(offenders).toEqual([])
  })

  it('locales en/ru/kz contain no duplicate/confirm strings', () => {
    const offenders: string[] = []
    for (const loc of ['en.json', 'ru.json', 'kz.json']) {
      const raw = readFileSync(join(localesDir, loc), 'utf-8')
      for (const bad of FORBIDDEN_I18N) {
        if (raw.includes(bad)) offenders.push(`${loc}: ${bad}`)
      }
    }
    expect(offenders).toEqual([])
  })
})
