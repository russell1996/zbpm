// @vitest-environment jsdom
// Smoke test: real @bpmn-io/form-js with EMPTY_SCHEMA — no mock (P-22).
// Verifies preact dedup: single preact instance → no "Cannot read ... context" error.
import { describe, it, expect, beforeAll } from 'vitest'
import { EMPTY_SCHEMA } from './formSchema'

let FormEditorClass: any

beforeAll(async () => {
  // Dynamic import to avoid module-level side effects
  const mod = await import('@bpmn-io/form-js')
  FormEditorClass = mod.FormEditor
})

describe('FormEditor smoke (real form-js, no mock)', () => {
  it('creates FormEditor and importSchema(EMPTY_SCHEMA) does not throw', async () => {
    // jsdom provides a basic DOM — form-js may need a real container
    const container = document.createElement('div')
    document.body.appendChild(container)

    const editor = new FormEditorClass({ container })
    try {
      // importSchema is async; if preact context is broken this throws:
      // "Cannot read properties of undefined (reading 'context')"
      await editor.importSchema(EMPTY_SCHEMA)
      // If we reach here, no context error — preact dedup works
      expect(true).toBe(true)
    } catch (e: any) {
      // If the error is the preact context bug, fail explicitly
      if (e.message?.includes('context')) {
        throw new Error(
          `PREACT DEDUP FAILED: ${e.message}. ` +
          'Two preact copies still present — check npm ls preact.',
        )
      }
      // Other errors (e.g. missing browser APIs in jsdom) — report honestly
      throw new Error(
        `jsdom limitation (NOT preact bug): ${e.message}. ` +
        'CTO should verify in real browser after deploy.',
      )
    } finally {
      editor.destroy()
      document.body.removeChild(container)
    }
  })
})
