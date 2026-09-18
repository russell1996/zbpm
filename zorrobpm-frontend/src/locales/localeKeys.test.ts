// @vitest-environment node
/**
 * WO-ACL-10 criterion 20: every locale key used through t('…') in the app source
 * must exist in ALL THREE locales (ru/en/kz).
 *
 * The parity check (ru vs en vs kz) cannot catch a key missing from every locale —
 * it looks identical in all three. This scanner checks USAGE instead: it greps the
 * source tree for t('key') calls and asserts each key is resolvable in every locale.
 *
 * File walking uses vite's import.meta.glob (raw) — no node:fs, so the build's tsc
 * stays free of node type definitions.
 */
import { describe, it, expect } from 'vitest'
import ru from './ru.json'
import en from './en.json'
import kz from './kz.json'

// All app source files (vue/ts), excluding test/spec files, as raw text.
// query: '?raw' (vite 8 recommended form; 'as: raw' is deprecated).
const sourceFiles: Record<string, string> = import.meta.glob(
  '../**/*.{vue,ts}',
  { query: '?raw', import: 'default', eager: true },
)

// t('key'), t("key"), t(\n 'key' \n) — single static string args only.
// \b word boundary: without it the regex matches t('…') INSIDE import(…),
// emit(…), get(…), post(…), mount(…), split(…), createElement(…) — 43 false
// positives on the first run.
const T_CALL = /\bt\(\s*['"]([^'"]+)['"]\s*\)/g

function usedKeys(): Map<string, string[]> {
  const byKey = new Map<string, string[]>()
  for (const [path, content] of Object.entries(sourceFiles)) {
    if (/\.(test|spec)\.(ts|vue)$/.test(path)) continue
    for (const m of content.matchAll(T_CALL)) {
      const key = m[1]
      if (!byKey.has(key)) byKey.set(key, [])
      byKey.get(key)!.push(path)
    }
  }
  return byKey
}

const locales: Record<string, Record<string, unknown>> = { ru, en, kz }

describe('WO-ACL-10 criterion 20: every t() key used in src exists in all locales', () => {
  const byKey = usedKeys()

  it('the scanner finds t() calls (sanity — at least 100 keys used)', () => {
    expect(byKey.size).toBeGreaterThan(100)
  })

  for (const [key, files] of byKey) {
    it(`t('${key}') used in ${files.slice(0, 3).join(', ')} exists in ru/en/kz`, () => {
      for (const [name, messages] of Object.entries(locales)) {
        expect(messages, `missing key '${key}' in ${name}.json`).toHaveProperty(key)
      }
    })
  }

  it('statusPending — the key that reached the stage as a raw literal — is present in all locales with a real translation', () => {
    for (const [name, messages] of Object.entries(locales)) {
      const value = messages['statusPending'] as string
      expect(value, `statusPending in ${name}.json`).toBeTruthy()
      // a translation, not the raw key echoed back
      expect(value).not.toBe('statusPending')
    }
  })
})