/**
 * WO-UI-31 (CONTRIBUTING.md, G24) — guard: в изменённых файлах шапки нет новых
 * «голых» UI-контролей (<button>/<input>/<textarea>/<select>, самодельных
 * fixed inset-0 оверлеев). shadcn-vue (`@/components/ui/*`) — единственный
 * путь для новых видимых контролей.
 *
 * Гейт ci/wo-report-gate.sh (G24) смотрит только ДОБАВЛЕННЫЕ строки диффа —
 * на чистом checkout/в CI он не видит «до/после». Этот тест прибивает то же
 * на диске: baseline «продолжение долга» (pre-existing голые кнопки
 * HeaderBar: мобильное меню, переключатель темы, триггер меню пользователя)
 * фиксирован ПОЛНОСТЬЮ — любая новая голая строка в этих двух файлах
 * красит тест. Перенос долга на shadcn Button — отдельная задача; при нём
 * baseline сокращается (тест обновляется в том же коммите).
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))

const BARE = /<button|<input|<textarea|<select|fixed inset-0/

function bareTagLines(rel: string): string[] {
  const src = readFileSync(join(here, rel), 'utf-8')
  const out: string[] = []
  let inComment = false
  for (const raw of src.split('\n')) {
    const line = raw.trim()
    if (inComment) {
      if (line.includes('-->')) inComment = false
      continue
    }
    if (line.startsWith('<!--') && !line.includes('-->')) {
      inComment = true
      continue
    }
    if (BARE.test(line)) out.push(line)
  }
  return out
}

describe('WO-UI-31 G24 guard: no new bare controls in header files', () => {
  it('ChannelStatusDot.vue — ноль голых <button>/<input>/<textarea>/<select>', () => {
    expect(bareTagLines('./ChannelStatusDot.vue')).toEqual([])
  })

  it('HeaderBar.vue — только зафиксированный pre-existing долг (3 голых <button>)', () => {
    // Три строки — ровно три pre-existing голых <button> master'а
    // (показательное меню / тема / меню пользователя). Четыре-я — провал.
    expect(bareTagLines('./HeaderBar.vue')).toEqual(['<button', '<button', '<button'])
  })
})
