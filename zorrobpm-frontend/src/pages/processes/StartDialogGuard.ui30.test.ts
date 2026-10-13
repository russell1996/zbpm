// @vitest-environment node
/**
 * WO-UI-30 часть A (критерий 1) + часть B: grep-страж удаления тумблера
 * и наличия новых ключей.
 * - Кнопки «Шире/Уже», `startWide`/`toggleStartWide`, localStorage-ключа
 *   `zbpm-start-dialog-wide` и трёх i18n-ключей presetDialogWider/
 *   presetDialogNarrower/presetDialogWiderHint нет ни в прод-коде, ни в
 *   локалях (мутация «вернуть кнопку» → красный);
 * - новые ключи goToSubprocess/openSubprocess/backToParent/
 *   noChildInstanceYet/inSubprocessPlane есть во ВСЕХ трёх локалях
 *   (G18-паритет; мутация «убрать ключ» → красный здесь и в localeKeys).
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '../..')
const detailVue = readFileSync(resolve(here, 'ProcessDefinitionDetail.vue'), 'utf-8')
const locales = (['en', 'ru', 'kz'] as const).map((l) => ({
  lang: l,
  raw: readFileSync(resolve(root, 'locales', `${l}.json`), 'utf-8'),
}))

const REMOVED = [
  'startWide',
  'toggleStartWide',
  'zbpm-start-dialog-wide',
  'presetDialogWider',
  'presetDialogNarrower',
]

const ADDED_KEYS = [
  'goToSubprocess',
  'openSubprocess',
  'backToParent',
  'noChildInstanceYet',
  'inSubprocessPlane',
]

describe('WO-UI-30 criterion 1: wider/narrower toggle is gone', () => {
  it.each(REMOVED)('прод-код не содержит %s', (token) => {
    expect(detailVue.includes(token), `ProcessDefinitionDetail.vue still mentions ${token}`).toBe(false)
  })

  it('ни одна из трёх локалей не содержит удалённые ключи', () => {
    for (const { lang, raw } of locales) {
      for (const token of ['presetDialogWider', 'presetDialogNarrower', 'presetDialogWiderHint']) {
        expect(raw.includes(`"${token}"`), `${lang}.json still has ${token}`).toBe(false)
      }
    }
  })
})

describe('WO-UI-30 part B: new i18n keys in all locales', () => {
  it.each(ADDED_KEYS)('ключ %s есть в en/ru/kz', (key) => {
    for (const { lang, raw } of locales) {
      expect(raw.includes(`"${key}"`), `${lang}.json misses ${key}`).toBe(true)
    }
  })
})
