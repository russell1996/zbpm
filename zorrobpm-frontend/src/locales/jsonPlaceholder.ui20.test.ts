// @vitest-environment jsdom
/**
 * WO-UI-20 — choosing variable type "JSON" crashed the page: vue-i18n's
 * message compiler threw on `jsonPlaceholder` because raw `{...}` in the
 * locale value is parsed as an interpolation slot, and `"key":"val"` is not
 * a valid placeholder token.
 *
 * These tests resolve the key through a REAL vue-i18n instance (not by
 * reading the JSON file as text) — a text-level "key exists" check would
 * stay green while the compiler still throws. Note on the assertions: when
 * compilation fails vue-i18n falls back to echoing the raw string, so the
 * rendered text alone CANNOT distinguish broken from fixed — the observable
 * difference of this bug class is the compilation error on the console
 * (the "cascade of console errors" from the live report). Hence the
 * console.error spy is the primary RED/GREEN signal here, and the exact
 * rendered text is asserted as the secondary one.
 */
import { describe, it, expect, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import en from './en.json'
import ru from './ru.json'
import kz from './kz.json'

const messages = { en, ru, kz } as const
type Locale = keyof typeof messages

// Target render per WO-UI-20: literal braces, no escape garbage.
const expected: Record<Locale, string> = {
  en: 'e.g. ["u1","u2"] or {"key":"val"}',
  ru: 'напр. ["u1","u2"] или {"key":"val"}',
  kz: 'мыс. ["u1","u2"] немесе {"key":"val"}',
}

function makeI18n(locale: Locale) {
  return createI18n({ legacy: false, locale, fallbackLocale: 'en', messages })
}

describe('WO-UI-20: jsonPlaceholder compiles in a real vue-i18n instance', () => {
  for (const locale of ['en', 'ru', 'kz'] as const) {
    it(`criterion 1+2 [${locale}]: t('jsonPlaceholder') compiles silently and renders literal {"key":"val"}`, () => {
      const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        const i18n = makeI18n(locale)
        const rendered = i18n.global.t('jsonPlaceholder') as unknown as string
        // Criterion 1: the compiler never complained (this is what crashed
        // the page — the FIRST t() call compiled the message and threw).
        expect(errSpy).not.toHaveBeenCalled()
        // Criterion 2: exact target render — literal braces, no raw escape
        // garbage (e.g. stray single quotes from a wrong escaping attempt).
        expect(rendered).toBe(expected[locale])
        expect(rendered).toContain('{"key":"val"}')
      } finally {
        errSpy.mockRestore()
      }
    })
  }

  it('no other locale key trips the message compiler (jsonPlaceholder was the only offender)', () => {
    // Sweep: vue-i18n compiles lazily — only resolved keys are compiled —
    // so resolve EVERY key through the real compiler. Real `{name}`
    // placeholders get dummy values; the sweep fails on any compilation
    // error, present or future.
    for (const locale of ['en', 'ru', 'kz'] as const) {
      const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        const i18n = makeI18n(locale)
        const dict = messages[locale] as unknown as Record<string, unknown>
        for (const key of Object.keys(dict)) {
          const raw: unknown = dict[key]
          // Nested dicts (e.g. "errors": {...}) are not messages — the
          // compiler never sees them as one string.
          if (typeof raw !== 'string') continue
          const named: Record<string, string> = {}
          // Literal-quote forms ({'{'}, {'{'}, ...}) start with a quote
          // inside the braces and never match this pattern — only real
          // interpolation slots are collected.
          for (const m of raw.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g)) {
            if (!(m[1] in named)) named[m[1]] = 'X'
          }
          const out = (/\{\d+\}/.test(raw)
            ? i18n.global.t(key, ['X', 'X', 'X'])
            : i18n.global.t(key, named)) as unknown as string
          expect(typeof out, `${locale}.${key}`).toBe('string')
        }
        expect(errSpy, `console.error during ${locale} sweep`).not.toHaveBeenCalled()
      } finally {
        errSpy.mockRestore()
      }
    }
  })
})
