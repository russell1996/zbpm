// WO-OPS-18 round 2 — CSP font-src: bpmn-js icon font (data: base64) was blocked
// by `default-src 'self'` fallback because `font-src` was never set.
// Read via node:fs (same pattern as nginx.cidr.test.ts): the snippet is not part
// of the Vite module graph, so it cannot be imported.
// Working pattern already in-tree: snippets/security-headers-neighbor-app.conf:15
// carries `font-src 'self' data:;` and does not break.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const snippet = readFileSync(
  fileURLToPath(new URL('../snippets/security-headers.conf', import.meta.url)),
  'utf8',
)
const nginxConf = readFileSync(fileURLToPath(new URL('../nginx.conf', import.meta.url)), 'utf8')

// Byte-exact expectation: round-1 CSP line + `font-src 'self' data:;` inserted
// right after `img-src 'self' data:;` (same position as the neighbor-app pattern).
// Anything else (weakened script-src, dropped object-src/frame-ancestors) fails here.
const EXPECTED_CSP =
  'add_header Content-Security-Policy "default-src \'self\'; script-src \'self\'; ' +
  "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; " +
  'object-src \'none\'; base-uri \'self\'; frame-ancestors \'none\'" always;'

describe('security-headers.conf WO-OPS-18 round 2', () => {
  it('criterion 1: CSP declares font-src \'self\' data:', () => {
    expect(snippet).toContain("font-src 'self' data:;")
  })

  it('criterion 2: every other CSP directive is byte-for-byte as before', () => {
    expect(snippet).toContain(EXPECTED_CSP)
  })

  it('criterion 2: script-src/object-src/frame-ancestors not weakened', () => {
    expect(snippet).toContain("script-src 'self';")
    expect(snippet).not.toContain('script-src \'self\' \'unsafe-inline\'')
    expect(snippet).not.toContain('script-src \'self\' \'unsafe-eval\'')
    expect(snippet).toContain("object-src 'none'")
    expect(snippet).toContain("frame-ancestors 'none'")
  })

  it('guard: snippet stays the single source of truth (3 nginx include points)', () => {
    const hits = nginxConf.match(/include snippets\/security-headers\.conf;/g) ?? []
    expect(hits).toHaveLength(3)
  })
})
